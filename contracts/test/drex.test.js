// Functional baseline of the Drex pilot contracts on the standard EVM (Hardhat network).
// The same flows run later on the besu-pqc-ppi network, signed with post-quantum DSAs, through
// the Java core. Flow numbers refer to docs/use-case-flows.md.
const { expect } = require("chai");
const { ethers } = require("hardhat");
const { loadFixture, time } = require("@nomicfoundation/hardhat-toolbox/network-helpers");

const role = (name) => ethers.id(name);
const CNPJ_A = 11111111n;
const CNPJ_B = 22222222n;

async function deployNetwork() {
  const [bcb, bankA, reserveA, bankB, reserveB, clientX, clientY, outsider] = await ethers.getSigners();

  // Flow 0: BCB deploys every contract, using one wallet as authority and admin.
  const RealDigital = await ethers.getContractFactory("RealDigital");
  const rd = await RealDigital.deploy("Real Digital", "BRL", bcb.address, bcb.address);
  const str = await (await ethers.getContractFactory("STR")).deploy(rd);
  const enableAccount = await (await ethers.getContractFactory("RealDigitalEnableAccount")).deploy(rd);
  const defaultAccount = await (await ethers.getContractFactory("RealDigitalDefaultAccount")).deploy(
    rd, bcb.address, bcb.address);
  const keyDictionary = await (await ethers.getContractFactory("KeyDictionary")).deploy(rd);
  const swapOne = await (await ethers.getContractFactory("SwapOneStep")).deploy(rd);
  const swapTwo = await (await ethers.getContractFactory("SwapTwoSteps")).deploy(rd);
  const discovery = await (await ethers.getContractFactory("AddressDiscovery")).deploy(bcb.address, bcb.address);

  await rd.grantRole(role("MINTER_ROLE"), str);
  await rd.grantRole(role("ACCESS_ROLE"), enableAccount);
  await rd.grantRole(role("MOVER_ROLE"), swapOne);
  await rd.grantRole(role("MOVER_ROLE"), swapTwo);
  await discovery.updateAddress(ethers.id("RealDigital"), rd);

  // Flow 1: onboard two participants, with their reserves.
  for (const [bank, reserve, cnpj] of [[bankA, reserveA, CNPJ_A], [bankB, reserveB, CNPJ_B]]) {
    await rd.enableAccount(bank.address);
    await rd.enableAccount(reserve.address);
    await defaultAccount.addDefaultAccount(cnpj, bank.address);
  }

  // Flow 7: each bank's Real Tokenizado, administered by the bank itself.
  const RealTokenizado = await ethers.getContractFactory("RealTokenizado");
  const rtA = await RealTokenizado.connect(bankA).deploy(
    "Real Tokenizado Bank A", "BRLA", bankA.address, bankA.address, "Bank A", CNPJ_A, reserveA.address);
  const rtB = await RealTokenizado.connect(bankB).deploy(
    "Real Tokenizado Bank B", "BRLB", bankB.address, bankB.address, "Bank B", CNPJ_B, reserveB.address);
  for (const [rt, bank] of [[rtA, bankA], [rtB, bankB]]) {
    await rt.connect(bank).grantRole(role("MINTER_ROLE"), swapOne);
    await rt.connect(bank).grantRole(role("MINTER_ROLE"), swapTwo);
  }

  // Flow 8: onboard one client per bank.
  await rtA.connect(bankA).enableAccount(clientX.address);
  await rtB.connect(bankB).enableAccount(clientY.address);
  const keyX = ethers.id("cpf:12345678901");
  const keyY = ethers.id("cpf:98765432100");
  await keyDictionary.connect(bankA).addAccount(keyX, 12345678901n, 1n, 100n, 1n, clientX.address);
  await keyDictionary.connect(bankB).addAccount(keyY, 98765432100n, 2n, 200n, 1n, clientY.address);

  return {
    bcb, bankA, reserveA, bankB, reserveB, clientX, clientY, outsider,
    rd, str, enableAccount, defaultAccount, keyDictionary, swapOne, swapTwo, discovery, rtA, rtB, keyX, keyY,
  };
}

/** Deploys the network, then funds reserve A with Real Digital and client X with Real Tokenizado A. */
async function fundedNetwork() {
  const n = await deployNetwork();
  await n.str.connect(n.reserveA).requestToMint(100000n); // R$ 1.000,00
  await n.rtA.connect(n.bankA).mint(n.clientX.address, 50000n); // R$ 500,00
  return n;
}

describe("Drex pilot contracts", function () {
  describe("network setup and onboarding (flows 0, 1, 7, 8, 18)", function () {
    it("registers participants, default wallets, clients and addresses", async function () {
      const n = await loadFixture(deployNetwork);
      expect(await n.rd.verifyAccount(n.bankA.address)).to.equal(true);
      expect(await n.rd.verifyAccount(n.outsider.address)).to.equal(false);
      expect(await n.defaultAccount.defaultAccount(CNPJ_B)).to.equal(n.bankB.address);
      expect(await n.keyDictionary.getWallet(n.keyY)).to.equal(n.clientY.address);
      expect(await n.discovery.addressDiscovery(ethers.id("RealDigital"))).to.equal(await n.rd.getAddress());
      expect(await n.rd.decimals()).to.equal(2n);
    });

    it("lets an enabled participant enable another wallet (flow 2)", async function () {
      const n = await loadFixture(deployNetwork);
      await n.enableAccount.connect(n.bankA).enableAccount(n.outsider.address);
      expect(await n.rd.verifyAccount(n.outsider.address)).to.equal(true);
    });
  });

  describe("STR (flows 4 and 5)", function () {
    it("mints Real Digital to a participant and burns it back", async function () {
      const n = await loadFixture(deployNetwork);
      await n.str.connect(n.bankA).requestToMint(10050n);
      expect(await n.rd.balanceOf(n.bankA.address)).to.equal(10050n);
      await n.str.connect(n.bankA).requestToBurn(50n);
      expect(await n.rd.balanceOf(n.bankA.address)).to.equal(10000n);
    });

    it("refuses a wallet that is not a participant", async function () {
      const n = await loadFixture(deployNetwork);
      await expect(n.str.connect(n.outsider).requestToMint(1n)).to.be.reverted;
    });
  });

  describe("transfers", function () {
    it("moves Real Digital between participants (flow 6)", async function () {
      const n = await loadFixture(fundedNetwork);
      await n.rd.connect(n.reserveA).transfer(n.bankB.address, 2500n);
      expect(await n.rd.balanceOf(n.bankB.address)).to.equal(2500n);
    });

    it("moves Real Tokenizado between clients of the same bank (flow 11)", async function () {
      const n = await loadFixture(fundedNetwork);
      await n.rtA.connect(n.bankA).enableAccount(n.outsider.address);
      await n.rtA.connect(n.clientX).transfer(n.outsider.address, 1000n);
      expect(await n.rtA.balanceOf(n.outsider.address)).to.equal(1000n);
    });

    it("settles an inter-bank client payment in one step (flow 12)", async function () {
      const n = await loadFixture(fundedNetwork);
      const walletY = await n.keyDictionary.getWallet(n.keyY);
      await n.rtA.connect(n.clientX).approve(n.swapOne, 3000n);
      await expect(n.swapOne.connect(n.clientX).executeSwap(n.rtA, n.rtB, walletY, 3000n))
        .to.emit(n.swapOne, "SwapExecuted")
        .withArgs(CNPJ_A, CNPJ_B, n.clientX.address, walletY, 3000n);

      expect(await n.rtA.balanceOf(n.clientX.address)).to.equal(47000n);
      expect(await n.rtB.balanceOf(n.clientY.address)).to.equal(3000n);
      expect(await n.rd.balanceOf(n.reserveA.address)).to.equal(97000n);
      expect(await n.rd.balanceOf(n.reserveB.address)).to.equal(3000n);
    });

    it("settles an inter-bank payment in two steps, accepted by the receiver (flow 13)", async function () {
      const n = await loadFixture(fundedNetwork);
      await n.rtA.connect(n.clientX).approve(n.swapTwo, 4000n);
      const receipt = await (await n.swapTwo.connect(n.clientX)
        .startSwap(n.rtA, n.rtB, n.clientY.address, 4000n)).wait();
      const proposalId = n.swapTwo.interface.parseLog(receipt.logs[0]).args.proposalId;
      expect(proposalId).to.equal(1n); // ids start at 1
      expect(await n.rtB.balanceOf(n.clientY.address)).to.equal(0n);

      await n.swapTwo.connect(n.clientY).executeSwap(proposalId);
      expect(await n.rtB.balanceOf(n.clientY.address)).to.equal(4000n);
      expect((await n.swapTwo.swapProposals(proposalId)).status).to.equal(1n); // EXECUTED
    });

    it("refuses a two-step proposal older than 7 days", async function () {
      const n = await loadFixture(fundedNetwork);
      await n.rtA.connect(n.clientX).approve(n.swapTwo, 4000n);
      await n.swapTwo.connect(n.clientX).startSwap(n.rtA, n.rtB, n.clientY.address, 4000n);
      await time.increase(7 * 24 * 3600 + 1);
      await expect(n.swapTwo.connect(n.clientY).executeSwap(1n)).to.be.revertedWith(
        "Proposal expired");
    });
  });

  describe("regulatory controls (flows 15, 16, 17)", function () {
    it("freezes part of a balance", async function () {
      const n = await loadFixture(fundedNetwork);
      await n.rtA.connect(n.bankA).increaseFrozenBalance(n.clientX.address, 50000n);
      expect(await n.rtA.frozenBalanceOf(n.clientX.address)).to.equal(50000n);
      await expect(n.rtA.connect(n.clientX).transfer(n.bankA.address, 1n)).to.be.reverted;
      await n.rtA.connect(n.bankA).decreaseFrozenBalance(n.clientX.address, 50000n);
      await n.rtA.connect(n.clientX).transfer(n.bankA.address, 1n);
    });

    it("forces a redemption only once the token contract itself is enabled", async function () {
      const n = await loadFixture(fundedNetwork);
      // moveAndBurn moves the funds to the token contract and burns them there, and the burn
      // requires the contract's own address to be enabled in its allow-list.
      await expect(n.rtA.connect(n.bankA).moveAndBurn(n.clientX.address, 10000n))
        .to.be.revertedWith("Sender is not enabled");
      await n.rtA.connect(n.bankA).enableAccount(await n.rtA.getAddress());
      await n.rtA.connect(n.bankA).moveAndBurn(n.clientX.address, 10000n);
      expect(await n.rtA.balanceOf(n.clientX.address)).to.equal(40000n);
    });

    it("pauses Real Digital, which also blocks STR", async function () {
      const n = await loadFixture(fundedNetwork);
      await n.rd.connect(n.bcb).pause();
      await expect(n.str.connect(n.bankA).requestToMint(1n)).to.be.reverted;
      await n.rd.connect(n.bcb).unpause();
      await n.str.connect(n.bankA).requestToMint(1n);
    });
  });

  describe("access control", function () {
    it("refuses privileged calls from wallets without the role", async function () {
      const n = await loadFixture(fundedNetwork);
      await expect(n.rd.connect(n.outsider).mint(n.bankA.address, 1n)).to.be.reverted;
      await expect(n.rd.connect(n.bankA).move(n.reserveA.address, n.bankA.address, 1n)).to.be.reverted;
      await expect(n.rd.connect(n.outsider).increaseFrozenBalance(n.bankA.address, 1n)).to.be.reverted;
      await expect(n.rd.connect(n.outsider).pause()).to.be.reverted;
      await expect(n.rtA.connect(n.bankB).mint(n.clientY.address, 1n)).to.be.reverted;
    });
  });
});

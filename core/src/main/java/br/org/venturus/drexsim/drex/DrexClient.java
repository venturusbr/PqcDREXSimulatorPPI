package br.org.venturus.drexsim.drex;

import br.org.venturus.drexsim.chain.Chain;
import br.org.venturus.drexsim.contracts.AddressDiscovery;
import br.org.venturus.drexsim.contracts.KeyDictionary;
import br.org.venturus.drexsim.contracts.RealDigital;
import br.org.venturus.drexsim.contracts.RealDigitalDefaultAccount;
import br.org.venturus.drexsim.contracts.RealDigitalEnableAccount;
import br.org.venturus.drexsim.contracts.RealTokenizado;
import br.org.venturus.drexsim.contracts.STR;
import br.org.venturus.drexsim.contracts.SwapOneStep;
import br.org.venturus.drexsim.contracts.SwapTwoSteps;
import br.org.venturus.drexsim.keystore.Account;
import java.math.BigInteger;
import java.util.function.Consumer;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

/**
 * Runs the Drex use-case flows (docs/use-case-flows.md) on chain, each call signed by the
 * {@link Account} that the flow names, with that account's DSA.
 */
public final class DrexClient {

  public static final byte[] MINTER_ROLE = Hash.sha3("MINTER_ROLE".getBytes());
  public static final byte[] ACCESS_ROLE = Hash.sha3("ACCESS_ROLE".getBytes());
  public static final byte[] MOVER_ROLE = Hash.sha3("MOVER_ROLE".getBytes());

  private final Chain chain;
  private final DrexDeployment deployment;
  private final Consumer<String> log;

  public DrexClient(Chain chain, DrexDeployment deployment, Consumer<String> log) {
    this.chain = chain;
    this.deployment = deployment;
    this.log = log == null ? s -> {} : log;
  }

  public DrexDeployment deployment() {
    return deployment;
  }

  /** Flow 0: deploys every contract and grants the roles the services need. */
  public static DrexDeployment deployNetwork(Chain chain, Account bcb, Consumer<String> log) throws Exception {
    Consumer<String> out = log == null ? s -> {} : log;
    var tm = chain.managerFor(bcb);
    var gas = chain.gasProvider();
    DrexDeployment d = new DrexDeployment();
    d.chainId = chain.chainId();

    out.accept("Deploying RealDigital");
    RealDigital rd = RealDigital.deploy(chain.web3j(), tm, gas, "Real Digital", "BRL", bcb.address(), bcb.address()).send();
    d.realDigital = rd.getContractAddress();
    out.accept("Deploying STR, RealDigitalEnableAccount, RealDigitalDefaultAccount, KeyDictionary");
    d.str = STR.deploy(chain.web3j(), tm, gas, d.realDigital).send().getContractAddress();
    d.realDigitalEnableAccount = RealDigitalEnableAccount.deploy(chain.web3j(), tm, gas, d.realDigital).send().getContractAddress();
    d.realDigitalDefaultAccount =
        RealDigitalDefaultAccount.deploy(chain.web3j(), tm, gas, d.realDigital, bcb.address(), bcb.address()).send().getContractAddress();
    d.keyDictionary = KeyDictionary.deploy(chain.web3j(), tm, gas, d.realDigital).send().getContractAddress();
    out.accept("Deploying SwapOneStep, SwapTwoSteps, AddressDiscovery");
    d.swapOneStep = SwapOneStep.deploy(chain.web3j(), tm, gas, d.realDigital).send().getContractAddress();
    d.swapTwoSteps = SwapTwoSteps.deploy(chain.web3j(), tm, gas, d.realDigital).send().getContractAddress();
    d.addressDiscovery = AddressDiscovery.deploy(chain.web3j(), tm, gas, bcb.address(), bcb.address()).send().getContractAddress();

    out.accept("Granting roles: STR minter, EnableAccount access, swaps movers");
    rd.grantRole(MINTER_ROLE, d.str).send();
    rd.grantRole(ACCESS_ROLE, d.realDigitalEnableAccount).send();
    rd.grantRole(MOVER_ROLE, d.swapOneStep).send();
    rd.grantRole(MOVER_ROLE, d.swapTwoSteps).send();

    out.accept("Registering addresses in AddressDiscovery");
    AddressDiscovery discovery = AddressDiscovery.load(d.addressDiscovery, chain.web3j(), tm, gas);
    register(discovery, "RealDigital", d.realDigital);
    register(discovery, "STR", d.str);
    register(discovery, "RealDigitalEnableAccount", d.realDigitalEnableAccount);
    register(discovery, "RealDigitalDefaultAccount", d.realDigitalDefaultAccount);
    register(discovery, "KeyDictionary", d.keyDictionary);
    register(discovery, "SwapOneStep", d.swapOneStep);
    register(discovery, "SwapTwoSteps", d.swapTwoSteps);
    return d;
  }

  private static void register(AddressDiscovery discovery, String name, String address) throws Exception {
    discovery.updateAddress(Hash.sha3(name.getBytes()), address).send();
  }

  /** Flow 1: BCB enables a participant and its reserve wallet and records its default wallet. */
  public void onboardParticipant(Account bcb, Account participant, Account reserve, BigInteger cnpj8) throws Exception {
    log.accept("Onboarding participant " + participant.name() + " (CNPJ8 " + cnpj8 + ")");
    RealDigital rd = realDigital(bcb);
    rd.enableAccount(participant.address()).send();
    if (!reserve.address().equals(participant.address())) {
      rd.enableAccount(reserve.address()).send();
    }
    RealDigitalDefaultAccount.load(deployment.realDigitalDefaultAccount, chain.web3j(), chain.managerFor(bcb), chain.gasProvider())
        .addDefaultAccount(cnpj8, participant.address())
        .send();
  }

  /**
   * Flow 7: the participant deploys its Real Tokenizado and authorizes the swaps to mint it; BCB
   * registers it in AddressDiscovery. Also enables the token's own address, without which {@code
   * moveAndBurn} reverts (see docs/contracts-reference.md).
   */
  public DrexDeployment.Tokenized deployRealTokenizado(
      Account bcb, Account bank, Account reserve, String participantName, String symbol, BigInteger cnpj8) throws Exception {
    log.accept("Deploying Real Tokenizado " + symbol + " of " + participantName);
    var tm = chain.managerFor(bank);
    RealTokenizado rt =
        RealTokenizado.deploy(
                chain.web3j(), tm, chain.gasProvider(), "Real Tokenizado " + participantName, symbol,
                bank.address(), bank.address(), participantName, cnpj8, reserve.address())
            .send();
    rt.grantRole(MINTER_ROLE, deployment.swapOneStep).send();
    rt.grantRole(MINTER_ROLE, deployment.swapTwoSteps).send();
    rt.enableAccount(rt.getContractAddress()).send();
    AddressDiscovery.load(deployment.addressDiscovery, chain.web3j(), chain.managerFor(bcb), chain.gasProvider())
        .updateAddress(Hash.sha3(("RealTokenizado@" + cnpj8).getBytes()), rt.getContractAddress())
        .send();

    var token = new DrexDeployment.Tokenized(
        participantName, symbol, cnpj8, rt.getContractAddress(), bank.address(), reserve.address());
    deployment.realTokenizados.removeIf(t -> t.symbol().equalsIgnoreCase(symbol));
    deployment.realTokenizados.add(token);
    return token;
  }

  /** Flow 8: the bank enables its client in its Real Tokenizado and registers the client's key. */
  public byte[] onboardClient(Account bank, String symbol, Account client, BigInteger cpf, BigInteger bankNumber,
      BigInteger accountNumber, BigInteger branch) throws Exception {
    log.accept("Onboarding client " + client.name() + " at " + symbol);
    realTokenizado(symbol, bank).enableAccount(client.address()).send();
    byte[] key = clientKey(cpf);
    keyDictionary(bank).addAccount(key, cpf, bankNumber, accountNumber, branch, client.address()).send();
    return key;
  }

  /** The KeyDictionary key of a client: {@code keccak256("cpf:" + cpf)}. */
  public static byte[] clientKey(BigInteger cpf) {
    return Hash.sha3(("cpf:" + cpf).getBytes());
  }

  /** Flow 4: a participant converts reserves into Real Digital through STR. */
  public TransactionReceipt strMint(Account participant, BigInteger amount) throws Exception {
    log.accept("STR mint " + Amounts.format(amount) + " to " + participant.name());
    return STR.load(deployment.str, chain.web3j(), chain.managerFor(participant), chain.gasProvider())
        .requestToMint(amount)
        .send();
  }

  /** Flow 9: a client deposit, issued as Real Tokenizado by the bank. */
  public TransactionReceipt deposit(Account bank, String symbol, Account client, BigInteger amount) throws Exception {
    log.accept("Deposit " + Amounts.format(amount) + " " + symbol + " to " + client.name());
    return realTokenizado(symbol, bank).mint(client.address(), amount).send();
  }

  /** Flow 15: the token's authority freezes part of a wallet's balance. */
  public TransactionReceipt freeze(Account authority, String symbol, Account wallet, BigInteger amount) throws Exception {
    log.accept("Freeze " + Amounts.format(amount) + " " + symbol + " of " + wallet.name());
    return realTokenizado(symbol, authority).increaseFrozenBalance(wallet.address(), amount).send();
  }

  /** Flow 12: inter-bank client payment in one step through SwapOneStep. */
  public TransactionReceipt payOneStep(Account payer, String fromSymbol, String toSymbol, String receiver, BigInteger amount)
      throws Exception {
    log.accept("Swap " + Amounts.format(amount) + " " + fromSymbol + " -> " + toSymbol + " from " + payer.name());
    realTokenizado(fromSymbol, payer).approve(deployment.swapOneStep, amount).send();
    return SwapOneStep.load(deployment.swapOneStep, chain.web3j(), chain.managerFor(payer), chain.gasProvider())
        .executeSwap(token(fromSymbol).address(), token(toSymbol).address(), receiver, amount)
        .send();
  }

  // ---------------------------------------------------------------- contract handles

  public RealDigital realDigital(Account as) {
    return RealDigital.load(deployment.realDigital, chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  public RealTokenizado realTokenizado(String symbol, Account as) {
    return RealTokenizado.load(token(symbol).address(), chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  public KeyDictionary keyDictionary(Account as) {
    return KeyDictionary.load(deployment.keyDictionary, chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  private DrexDeployment.Tokenized token(String symbol) {
    return deployment.tokenized(symbol)
        .orElseThrow(() -> new IllegalArgumentException("No Real Tokenizado with symbol " + symbol));
  }
}

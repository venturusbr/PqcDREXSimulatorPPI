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
import java.util.List;
import java.util.function.Consumer;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.exceptions.TransactionException;
import org.web3j.utils.Numeric;

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
    d.authority = bcb.address();

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

  /**
   * Flow 1: BCB enables a participant and its reserve wallet and records its default wallet.
   *
   * <p>{@code RealDigitalDefaultAccount.addDefaultAccount} overwrites the default wallet a CNPJ8 already has,
   * so a CNPJ8 whose default wallet is another one is refused before any transaction (issue #18). Steps
   * already done are not sent again, so running this again finishes an onboarding that stopped halfway.
   *
   * @return the receipts of the transactions sent, in order
   */
  public List<TransactionReceipt> onboardParticipant(Account bcb, Account participant, Account reserve, BigInteger cnpj8)
      throws Exception {
    log.accept("Onboarding participant " + participant.name() + " (CNPJ8 " + cnpj8 + ")");
    RealDigital rd = realDigital(bcb);
    RealDigitalDefaultAccount defaults = RealDigitalDefaultAccount.load(deployment.realDigitalDefaultAccount,
        chain.web3j(), chain.managerFor(bcb), chain.gasProvider());
    String current = defaults.defaultAccount(cnpj8).send();
    boolean recorded = !isZero(current);
    if (recorded && !current.equalsIgnoreCase(participant.address())) {
      throw new IllegalStateException(String.format(
          "CNPJ8 %s already has the default wallet %s. RealDigitalDefaultAccount would replace it with %s's, so "
              + "nothing was sent", cnpj8, current, participant.name()));
    }
    List<TransactionReceipt> receipts = new java.util.ArrayList<>();
    for (Account wallet : participant.address().equalsIgnoreCase(reserve.address())
        ? List.of(participant) : List.of(participant, reserve)) {
      if (!rd.verifyAccount(wallet.address()).send()) {
        receipts.add(rd.enableAccount(wallet.address()).send());
      }
    }
    if (!recorded) {
      receipts.add(defaults.addDefaultAccount(cnpj8, participant.address()).send());
    }
    if (receipts.isEmpty()) {
      throw new IllegalStateException(String.format("%s is already onboarded with CNPJ8 %s%s, so nothing was sent",
          participant.name(), cnpj8,
          participant.address().equalsIgnoreCase(reserve.address()) ? "" : " and its reserve " + reserve.name() + " is enabled"));
    }
    return receipts;
  }

  /** A Real Tokenizado and the receipts of the transactions sent for it, in order. */
  public record DeployedToken(DrexDeployment.Tokenized token, List<TransactionReceipt> receipts) {}

  /**
   * Flow 7: the participant deploys its Real Tokenizado and authorizes the swaps to mint it; BCB
   * registers it in AddressDiscovery. Also enables the token's own address, without which {@code
   * moveAndBurn} reverts (see docs/contracts-reference.md).
   *
   * <p>A symbol or CNPJ8 that already has a Real Tokenizado is refused before any transaction (issue #17):
   * the simulator would lose track of that token, and AddressDiscovery would point its CNPJ8 elsewhere. The
   * token is recorded in the deployment as soon as it is deployed, so a setup that fails halfway does not
   * lose it; running this again with the same values finishes that setup.
   */
  public DeployedToken deployRealTokenizado(
      Account bcb, Account bank, Account reserve, String participantName, String symbol, BigInteger cnpj8) throws Exception {
    AddressDiscovery discovery =
        AddressDiscovery.load(deployment.addressDiscovery, chain.web3j(), chain.managerFor(bcb), chain.gasProvider());
    byte[] entry = Hash.sha3(("RealTokenizado@" + cnpj8).getBytes());
    var known = deployment.tokenized(symbol);
    if (known.isPresent()) {
      DrexDeployment.Tokenized t = known.get();
      if (!t.symbol().equals(symbol) || !t.cnpj8().equals(cnpj8) || !t.authority().equalsIgnoreCase(bank.address())) {
        throw new IllegalStateException(String.format(
            "Symbol %s is already used by the Real Tokenizado of %s (CNPJ8 %s) at %s, so nothing was sent",
            t.symbol(), t.participant(), t.cnpj8(), t.address()));
      }
      log.accept("Finishing the setup of Real Tokenizado " + symbol + " at " + t.address());
      List<TransactionReceipt> receipts = finishSetup(t, bank, discovery, entry);
      if (receipts.isEmpty()) {
        throw new IllegalStateException(String.format(
            "Real Tokenizado %s of %s is already deployed and set up at %s, so nothing was sent", symbol,
            t.participant(), t.address()));
      }
      return new DeployedToken(t, receipts);
    }
    var sameCnpj8 = deployment.realTokenizados.stream().filter(t -> t.cnpj8().equals(cnpj8)).findFirst();
    if (sameCnpj8.isPresent()) {
      throw new IllegalStateException(String.format(
          "CNPJ8 %s already has a Real Tokenizado, %s of %s at %s, so nothing was sent", cnpj8,
          sameCnpj8.get().symbol(), sameCnpj8.get().participant(), sameCnpj8.get().address()));
    }
    String registered = discovery.addressDiscovery(entry).send();
    if (!isZero(registered)) {
      throw new IllegalStateException(String.format(
          "AddressDiscovery already has a Real Tokenizado for CNPJ8 %s at %s, so nothing was sent", cnpj8, registered));
    }

    log.accept("Deploying Real Tokenizado " + symbol + " of " + participantName);
    RealTokenizado rt =
        RealTokenizado.deploy(
                chain.web3j(), chain.managerFor(bank), chain.gasProvider(), "Real Tokenizado " + participantName, symbol,
                bank.address(), bank.address(), participantName, cnpj8, reserve.address())
            .send();
    var token = new DrexDeployment.Tokenized(
        participantName, symbol, cnpj8, rt.getContractAddress(), bank.address(), reserve.address());
    deployment.realTokenizados.add(token);
    List<TransactionReceipt> receipts = new java.util.ArrayList<>();
    rt.getTransactionReceipt().ifPresent(receipts::add);
    try {
      receipts.addAll(finishSetup(token, bank, discovery, entry));
    } catch (Exception e) {
      Throwable root = e;
      while (root.getCause() != null && root.getCause() != root) {
        root = root.getCause();
      }
      IllegalStateException halfway = new IllegalStateException(String.format(
          "Real Tokenizado %s was deployed at %s, but its setup did not finish (%s). Run this again with the same "
              + "values to finish it", symbol, rt.getContractAddress(), root.getMessage()));
      halfway.addSuppressed(e);
      throw halfway;
    }
    return new DeployedToken(token, receipts);
  }

  /**
   * The steps after a Real Tokenizado is deployed, each sent only if it is not done yet: the swaps may mint
   * it, its own address is enabled, and AddressDiscovery points its CNPJ8 to it.
   */
  private List<TransactionReceipt> finishSetup(DrexDeployment.Tokenized token, Account bank, AddressDiscovery discovery,
      byte[] entry) throws Exception {
    RealTokenizado rt = RealTokenizado.load(token.address(), chain.web3j(), chain.managerFor(bank), chain.gasProvider());
    List<TransactionReceipt> receipts = new java.util.ArrayList<>();
    for (String swap : List.of(deployment.swapOneStep, deployment.swapTwoSteps)) {
      if (!rt.hasRole(MINTER_ROLE, swap).send()) {
        receipts.add(rt.grantRole(MINTER_ROLE, swap).send());
      }
    }
    if (!rt.verifyAccount(token.address()).send()) {
      receipts.add(rt.enableAccount(token.address()).send());
    }
    if (!token.address().equalsIgnoreCase(discovery.addressDiscovery(entry).send())) {
      receipts.add(discovery.updateAddress(entry, token.address()).send());
    }
    return receipts;
  }

  /**
   * Flow 8: the bank enables its client in its Real Tokenizado and registers the client's key (CPF) in
   * KeyDictionary.
   *
   * <p>{@code KeyDictionary.addAccount} overwrites an existing key (a contract flaw, see the README), so
   * this refuses, before any transaction, a CPF registered to another wallet and a wallet registered under
   * another CPF (issue #16). A client whose key is already this wallet's, registered by any bank, is only
   * enabled in this bank's token, as {@link #enableClient} does. Steps already done are not sent again.
   *
   * @return the receipts of the transactions sent, in order
   */
  public List<TransactionReceipt> onboardClient(Account bank, String symbol, Account client, BigInteger cpf,
      BigInteger bankNumber, BigInteger accountNumber, BigInteger branch) throws Exception {
    log.accept("Onboarding client " + client.name() + " at " + symbol);
    RealTokenizado token = realTokenizado(symbol, bank);
    KeyDictionary keys = keyDictionary(bank);
    byte[] key = clientKey(cpf);
    String keyWallet = keys.getWallet(key).send();
    boolean keyFree = isZero(keyWallet);
    if (!keyFree && !keyWallet.equalsIgnoreCase(client.address())) {
      KeyDictionary.CustomerData holder = keys.getCustomerData(key).send();
      throw new IllegalStateException(String.format(
          "CPF %s is already registered to wallet %s, by participant %s. KeyDictionary would move the key to %s and "
              + "take it from that client, so nothing was sent", cpf, keyWallet, holder.owner, client.name()));
    }
    byte[] walletKey = keys.getKey(client.address()).send();
    if (!isZero(walletKey) && !java.util.Arrays.equals(walletKey, key)) {
      KeyDictionary.CustomerData registered = keys.getCustomerData(walletKey).send();
      throw new IllegalStateException(String.format(
          "%s is already registered under CPF %s, by participant %s, so nothing was sent", client.name(),
          registered.taxId, registered.owner));
    }
    boolean enabled = token.verifyAccount(client.address()).send();
    if (enabled && !keyFree) {
      throw new IllegalStateException(String.format(
          "%s is already a client of %s with CPF %s, so nothing was sent", client.name(), symbol, cpf));
    }
    List<TransactionReceipt> receipts = new java.util.ArrayList<>();
    if (!enabled) {
      receipts.add(token.enableAccount(client.address()).send());
    }
    if (keyFree) {
      receipts.add(keys.addAccount(key, cpf, bankNumber, accountNumber, branch, client.address()).send());
    } else {
      log.accept("CPF " + cpf + " is already " + client.name() + "'s key: only enabling it at " + symbol);
    }
    return receipts;
  }

  private static boolean isZero(String address) {
    return address == null || Numeric.toBigInt(address).signum() == 0;
  }

  private static boolean isZero(byte[] bytes) {
    for (byte b : bytes) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Flow 8 for a client the KeyDictionary already knows from another bank: only enables it in this bank's
   * Real Tokenizado. KeyDictionary keeps one record per CPF, so registering the key again would move it here.
   */
  public TransactionReceipt enableClient(Account bank, String symbol, Account client) throws Exception {
    log.accept("Enabling client " + client.name() + " at " + symbol);
    return realTokenizado(symbol, bank).enableAccount(client.address()).send();
  }

  /** The bank disables a client in its Real Tokenizado. Its balance stays where it was. */
  public TransactionReceipt disableClient(Account bank, String symbol, Account client) throws Exception {
    log.accept("Disabling client " + client.name() + " at " + symbol);
    return realTokenizado(symbol, bank).disableAccount(client.address()).send();
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

  /** Flow 11: transfer between clients of the same bank (only Real Tokenizado moves). */
  public TransactionReceipt transfer(Account from, String symbol, String to, BigInteger amount) throws Exception {
    log.accept("Transfer " + Amounts.format(amount) + " " + symbol + " from " + from.name());
    return realTokenizado(symbol, from).transfer(to, amount).send();
  }

  /**
   * Flow 12: inter-bank client payment in one step through SwapOneStep. SwapOneStep burns the payer's Real
   * Tokenizado with {@code burnFrom}, so the payer first approves it, in a transaction of its own.
   *
   * <p>So that a refused payment does not leave that approval on chain (issue #13): the usual reasons for a
   * refusal are checked first, before any transaction; no approval is sent when the allowance already
   * covers the amount; and if the swap is refused anyway, the allowance is set back to what it was.
   */
  public TransactionReceipt payOneStep(Account payer, String fromSymbol, String toSymbol, String receiver, BigInteger amount)
      throws Exception {
    log.accept("Swap " + Amounts.format(amount) + " " + fromSymbol + " -> " + toSymbol + " from " + payer.name());
    RealTokenizado from = realTokenizado(fromSymbol, payer);
    checkSwap(payer, from, fromSymbol, realTokenizado(toSymbol, payer), toSymbol, amount);
    BigInteger allowance = from.allowance(payer.address(), deployment.swapOneStep).send();
    boolean approved = allowance.compareTo(amount) < 0;
    if (approved) {
      from.approve(deployment.swapOneStep, amount).send();
    }
    try {
      return SwapOneStep.load(deployment.swapOneStep, chain.web3j(), chain.managerFor(payer), chain.gasProvider())
          .executeSwap(token(fromSymbol).address(), token(toSymbol).address(), receiver, amount)
          .send();
    } catch (Exception e) {
      if (approved && !mayStillBeIncluded(e)) {
        log.accept("Swap refused: setting the allowance of SwapOneStep back to " + Amounts.format(allowance) + " "
            + fromSymbol);
        try {
          from.approve(deployment.swapOneStep, allowance).send();
        } catch (Exception undo) {
          e.addSuppressed(undo);
        }
      }
      throw e;
    }
  }

  /**
   * Whether a transaction that failed with {@code e} may still be included: it was sent (web3j knows its
   * hash) but no receipt came in time, or the connection failed, perhaps after the node took it. A refusal
   * by {@code eth_estimateGas} or by the node has no hash, and a revert on chain has a receipt; such a
   * transaction is over.
   */
  static boolean mayStillBeIncluded(Exception e) {
    if (e instanceof TransactionException te) {
      return te.getTransactionHash().isPresent() && te.getTransactionReceipt().isEmpty();
    }
    return e instanceof java.io.IOException;
  }

  /**
   * Refuses, before any transaction, a swap that the contracts would refuse for a reason the payer can see:
   * a paused token, too little balance once frozen funds are set aside, or a bank reserve without the
   * Real Digital to move. Messages start like the contracts' own.
   */
  private void checkSwap(Account payer, RealTokenizado from, String fromSymbol, RealTokenizado to, String toSymbol,
      BigInteger amount) throws Exception {
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("The amount must be positive, not " + Amounts.format(amount));
    }
    RealDigital rd = realDigital(payer);
    if (from.paused().send()) {
      throw new IllegalStateException("Pausable: paused (" + fromSymbol + " is paused)");
    }
    if (to.paused().send()) {
      throw new IllegalStateException("Pausable: paused (" + toSymbol + " is paused)");
    }
    if (rd.paused().send()) {
      throw new IllegalStateException("Pausable: paused (Real Digital is paused)");
    }
    BigInteger balance = from.balanceOf(payer.address()).send();
    BigInteger frozen = from.frozenBalanceOf(payer.address()).send();
    BigInteger available = balance.subtract(frozen).max(BigInteger.ZERO);
    if (available.compareTo(amount) < 0) {
      throw new IllegalStateException(String.format(
          "RealDigital: insufficient balance (%s has %s %s available, %s of %s frozen; the payment is %s)",
          payer.name(), Amounts.format(available), fromSymbol, Amounts.format(frozen), Amounts.format(balance),
          Amounts.format(amount)));
    }
    String reserve = from.reserve().send();
    BigInteger reserveAvailable = rd.balanceOf(reserve).send().subtract(rd.frozenBalanceOf(reserve).send());
    if (reserveAvailable.compareTo(amount) < 0) {
      throw new IllegalStateException(String.format(
          "RealDigital: insufficient balance (the reserve of %s's bank has %s Real Digital available; the payment is %s)",
          fromSymbol, Amounts.format(reserveAvailable.max(BigInteger.ZERO)), Amounts.format(amount)));
    }
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

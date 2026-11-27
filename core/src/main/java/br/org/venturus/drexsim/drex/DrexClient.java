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
    d.deployedAt = chain.blockNumber();

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
      // The name and reserve are fixed when the token is deployed: finishing its setup cannot change them.
      if (!t.participant().equals(participantName) || !t.reserve().equalsIgnoreCase(reserve.address())) {
        throw new IllegalStateException(String.format(
            "Real Tokenizado %s was deployed for %s with reserve wallet %s, not %s with %s, so nothing was sent. "
                + "To finish its setup, use the values it was deployed with", symbol, t.participant(), t.reserve(),
            participantName, reserve.name()));
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
      IllegalStateException halfway = new IllegalStateException(setupDidNotFinish(symbol, rt.getContractAddress(), e));
      halfway.addSuppressed(e);
      throw halfway;
    }
    return new DeployedToken(token, receipts);
  }

  private static final java.util.regex.Pattern REVERT =
      java.util.regex.Pattern.compile("[Ee]xecution reverted:? ?\\(?(.*?)\\)?$");

  /**
   * The error of a Real Tokenizado deployed whose setup then failed with {@code e}. A revert is reduced to
   * its reason: callers such as the GUI shorten any message that contains a raw revert, which would drop
   * the token's address and how to finish its setup.
   */
  static String setupDidNotFinish(String symbol, String address, Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String cause = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    java.util.regex.Matcher revert = REVERT.matcher(cause);
    if (revert.find()) {
      String reason = revert.group(1).trim();
      cause = "the contract refused a step" + (reason.isEmpty() ? "" : ": " + reason);
    }
    return String.format("Real Tokenizado %s was deployed at %s, but its setup did not finish, so run this again with "
        + "the same values to finish it. Cause: %s", symbol, address, cause);
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
    checkSwap(payer, payer.address(), payer.name(), from, fromSymbol, realTokenizado(toSymbol, payer), toSymbol, receiver,
        amount);
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

  // ---------------------------------------------------------------- flow 3

  /**
   * Flow 3: the current default wallet of a CNPJ8 hands that role to another wallet. The contract only
   * checks that the caller is the current default wallet; the new one must also be enabled in Real
   * Digital (flow 2) to operate, so a wallet that is not is refused before sending.
   */
  public TransactionReceipt changeDefaultWallet(Account current, BigInteger cnpj8, String newWallet) throws Exception {
    log.accept("Changing the default wallet of CNPJ8 " + cnpj8 + " to " + newWallet);
    RealDigitalDefaultAccount defaults = RealDigitalDefaultAccount.load(deployment.realDigitalDefaultAccount,
        chain.web3j(), chain.managerFor(current), chain.gasProvider());
    String recorded = defaults.defaultAccount(cnpj8).send();
    if (isZero(recorded)) {
      throw new IllegalStateException(String.format(
          "CNPJ8 %s has no default wallet: onboard its participant first (flow 1), so nothing was sent", cnpj8));
    }
    if (!recorded.equalsIgnoreCase(current.address())) {
      throw new IllegalStateException(String.format(
          "Must be current default account: the default wallet of CNPJ8 %s is %s, not %s, so nothing was sent",
          cnpj8, recorded, current.address()));
    }
    if (recorded.equalsIgnoreCase(newWallet)) {
      throw new IllegalStateException(String.format(
          "%s is already the default wallet of CNPJ8 %s, so nothing was sent", newWallet, cnpj8));
    }
    RealDigital rd = realDigital(current);
    if (!rd.verifyAccount(current.address()).send()) {
      throw new IllegalStateException(String.format(
          "Must be participant: %s is not enabled in Real Digital, so nothing was sent", current.address()));
    }
    if (!rd.verifyAccount(newWallet).send()) {
      throw new IllegalStateException(String.format(
          "%s is not enabled in Real Digital: enable it first (flow 2), so that it can operate as the default "
              + "wallet. Nothing was sent", newWallet));
    }
    return defaults.updateDefaultWallet(cnpj8, newWallet).send();
  }

  // ---------------------------------------------------------------- flow 13

  /** How long SwapTwoSteps and KeyDictionary proposals can be accepted: 7 days. */
  public static final long PROPOSAL_LIFETIME_SECONDS = 7L * 24 * 3600;

  /** A SwapTwoSteps proposal, as {@code swapProposals(id)} returns it. */
  public record SwapProposal(BigInteger id, String tokenSender, String tokenReceiver, String sender, String receiver,
      BigInteger amount, Status status, long timestamp) {

    public enum Status { PENDING, EXECUTED, CANCELLED }

    public long expiresAt() {
      return timestamp + PROPOSAL_LIFETIME_SECONDS;
    }

    /** Whether the contract refuses to execute it at block time {@code now}. */
    public boolean expiredAt(long now) {
      return now >= expiresAt();
    }
  }

  /** A proposal and the receipts of the transactions sent to make it, in order. */
  public record Proposed(BigInteger proposalId, List<TransactionReceipt> receipts) {}

  /** A SwapTwoSteps proposal, or empty if there is none with that id. */
  public java.util.Optional<SwapProposal> swapProposal(BigInteger id) throws Exception {
    var t = SwapTwoSteps.load(deployment.swapTwoSteps, chain.web3j(), chain.readOnly(), chain.gasProvider())
        .swapProposals(id).send();
    if (isZero(t.component3())) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(new SwapProposal(id, t.component1(), t.component2(), t.component3(), t.component4(),
        t.component5(), SwapProposal.Status.values()[t.component6().intValueExact()], t.component7().longValueExact()));
  }

  /**
   * Executed and cancelled proposals, which never change again, per chain (its genesis block) and SwapTwoSteps
   * contract, by id.
   */
  private static final java.util.Map<String, java.util.Map<BigInteger, SwapProposal>> FINISHED =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Every SwapTwoSteps proposal, oldest first. The contract keeps its counter private, but numbers them from 1
   * with no gaps, so they are read until the first empty one. Finished proposals are read once: only the
   * pending ones and the first empty id are read each time.
   */
  public List<SwapProposal> swapProposals() throws Exception {
    String contract = EventLogs.genesis(chain.web3j()) + "/" + deployment.swapTwoSteps.toLowerCase();
    var finished = FINISHED.computeIfAbsent(contract, k -> new java.util.concurrent.ConcurrentHashMap<>());
    List<SwapProposal> all = new java.util.ArrayList<>();
    for (BigInteger id = BigInteger.ONE; ; id = id.add(BigInteger.ONE)) {
      SwapProposal known = finished.get(id);
      if (known != null) {
        all.add(known);
        continue;
      }
      var p = swapProposal(id);
      if (p.isEmpty()) {
        return all;
      }
      if (p.get().status() != SwapProposal.Status.PENDING) {
        finished.put(id, p.get());
      }
      all.add(p.get());
    }
  }

  /** Time of the latest block, in seconds: the contracts compare proposals' times with it. */
  public long blockTime() throws java.io.IOException {
    return chain.web3j().ethGetBlockByNumber(org.web3j.protocol.core.DefaultBlockParameterName.LATEST, false).send()
        .getBlock().getTimestamp().longValueExact();
  }

  /**
   * What {@code sender}'s pending proposals that can still be executed will burn of the token at {@code
   * tokenAddress}: the allowance SwapTwoSteps needs over that token.
   */
  static BigInteger pendingAmount(List<SwapProposal> proposals, long now, String sender, String tokenAddress) {
    return proposals.stream()
        .filter(p -> p.status() == SwapProposal.Status.PENDING && !p.expiredAt(now))
        .filter(p -> p.sender().equalsIgnoreCase(sender) && p.tokenSender().equalsIgnoreCase(tokenAddress))
        .map(SwapProposal::amount).reduce(BigInteger.ZERO, BigInteger::add);
  }

  /**
   * Flow 13, step 1: the payer proposes a payment to a client of another bank; nothing moves until the
   * receiver accepts it. SwapTwoSteps' {@code startSwap} checks nothing, so what would make the acceptance
   * fail is checked now, as for a one-step payment. The payer's allowance to SwapTwoSteps must cover this
   * payment and its other pending ones from the same token, since accepting any of them burns from it: it is
   * raised to that total only if it is lower.
   */
  public Proposed startTwoStepPayment(Account payer, String fromSymbol, String toSymbol, String receiver, BigInteger amount)
      throws Exception {
    log.accept("Two-step payment of " + Amounts.format(amount) + " " + fromSymbol + " -> " + toSymbol + " from "
        + payer.name());
    RealTokenizado from = realTokenizado(fromSymbol, payer);
    checkSwap(payer, payer.address(), payer.name(), from, fromSymbol, realTokenizado(toSymbol, payer), toSymbol, receiver,
        amount);
    String fromAddress = token(fromSymbol).address();
    BigInteger allowance = from.allowance(payer.address(), deployment.swapTwoSteps).send();
    BigInteger needed = pendingAmount(swapProposals(), blockTime(), payer.address(), fromAddress).add(amount);
    List<TransactionReceipt> receipts = new java.util.ArrayList<>();
    boolean approved = allowance.compareTo(needed) < 0;
    if (approved) {
      receipts.add(from.approve(deployment.swapTwoSteps, needed).send());
    }
    try {
      TransactionReceipt started = swapTwoSteps(payer)
          .startSwap(fromAddress, token(toSymbol).address(), receiver, amount).send();
      receipts.add(started);
      List<SwapTwoSteps.SwapStartedEventResponse> events = SwapTwoSteps.getSwapStartedEvents(started);
      return new Proposed(events.isEmpty() ? null : events.get(0).proposalId, receipts);
    } catch (Exception e) {
      if (approved && !mayStillBeIncluded(e)) {
        log.accept("Proposal refused: setting the allowance of SwapTwoSteps back to " + Amounts.format(allowance));
        try {
          from.approve(deployment.swapTwoSteps, allowance).send();
        } catch (Exception undo) {
          e.addSuppressed(undo);
        }
      }
      throw e;
    }
  }

  /** A pending proposal, or a refusal that says why it cannot be acted on. */
  private SwapProposal pendingProposal(BigInteger id) throws Exception {
    SwapProposal p = swapProposal(id).orElseThrow(() -> new IllegalStateException(
        "There is no two-step payment " + id + ", so nothing was sent"));
    if (p.status() != SwapProposal.Status.PENDING) {
      throw new IllegalStateException(String.format("Two-step payment %s was already %s, so nothing was sent", id,
          p.status().name().toLowerCase()));
    }
    return p;
  }

  /**
   * Flow 13, step 2: the receiver accepts a proposal, which burns the payer's Real Tokenizado, moves Real
   * Digital between the reserves and mints the receiver's. Days may have passed since the proposal, so the
   * payer's balance and allowance and the reserve are checked again, before sending.
   */
  public TransactionReceipt acceptTwoStepPayment(Account receiver, BigInteger id) throws Exception {
    log.accept("Accepting two-step payment " + id + " as " + receiver.name());
    SwapProposal p = pendingProposal(id);
    if (!p.receiver().equalsIgnoreCase(receiver.address())) {
      throw new IllegalStateException(String.format(
          "Only the receiver can execute the swap: payment %s goes to %s, not %s, so nothing was sent", id, p.receiver(),
          receiver.address()));
    }
    if (p.expiredAt(blockTime())) {
      throw new IllegalStateException(String.format("Proposal expired: payment %s could be accepted until %s, so "
          + "nothing was sent", id, java.time.Instant.ofEpochSecond(p.expiresAt())));
    }
    RealTokenizado from = rtAt(p.tokenSender(), receiver);
    checkSwap(receiver, p.sender(), p.sender(), from, symbolAt(p.tokenSender()), rtAt(p.tokenReceiver(), receiver),
        symbolAt(p.tokenReceiver()), p.receiver(), p.amount());
    BigInteger allowance = from.allowance(p.sender(), deployment.swapTwoSteps).send();
    if (allowance.compareTo(p.amount()) < 0) {
      throw new IllegalStateException(String.format(
          "ERC20: insufficient allowance: the payer %s lets SwapTwoSteps burn %s %s, and the payment is %s. The payer "
              + "must approve it again, so nothing was sent", p.sender(), Amounts.format(allowance),
          symbolAt(p.tokenSender()), Amounts.format(p.amount())));
    }
    return swapTwoSteps(receiver).executeSwap(id).send();
  }

  /**
   * Flow 13, step 2 instead: the payer or the receiver cancels a pending proposal. When the payer cancels, the
   * share of its allowance to SwapTwoSteps that the payment needed is withdrawn, down to what its other
   * pending payments still need. The receiver cannot change the payer's allowance, so it stays.
   *
   * @return the receipts of the transactions sent, in order
   */
  public List<TransactionReceipt> cancelTwoStepPayment(Account by, BigInteger id, String reason) throws Exception {
    log.accept("Cancelling two-step payment " + id + " as " + by.name());
    SwapProposal p = pendingProposal(id);
    boolean payer = p.sender().equalsIgnoreCase(by.address());
    if (!payer && !p.receiver().equalsIgnoreCase(by.address())) {
      throw new IllegalStateException(String.format(
          "Only the sender or receiver can cancel the swap: payment %s is from %s to %s, so nothing was sent", id,
          p.sender(), p.receiver()));
    }
    List<TransactionReceipt> receipts = new java.util.ArrayList<>();
    receipts.add(swapTwoSteps(by).cancelSwap(id, reason == null ? "" : reason.trim()).send());
    if (payer) {
      RealTokenizado from = rtAt(p.tokenSender(), by);
      BigInteger allowance = from.allowance(by.address(), deployment.swapTwoSteps).send();
      BigInteger stillNeeded = pendingAmount(swapProposals(), blockTime(), by.address(), p.tokenSender());
      BigInteger lowered = allowance.subtract(p.amount()).max(stillNeeded);
      if (lowered.compareTo(allowance) < 0) {
        try {
          receipts.add(from.approve(deployment.swapTwoSteps, lowered).send());
        } catch (Exception e) {
          throw new IllegalStateException(String.format(
              "Two-step payment %s was cancelled, but the allowance of SwapTwoSteps over %s stays at %s: lowering it "
                  + "to %s failed: %s", id, symbolAt(p.tokenSender()), Amounts.format(allowance),
              Amounts.format(lowered), e.getMessage()), e);
        }
      }
    }
    return receipts;
  }

  // ---------------------------------------------------------------- flow 14

  /**
   * A KeyDictionary key request that is still open: the key's current owner is not the bank that asked.
   * {@code cpf} and {@code wallet} are read from the request's transaction, and are null if it cannot be read.
   */
  public record KeyRequest(BigInteger id, byte[] key, BigInteger cpf, String requester, String wallet, long timestamp,
      String owner) {

    public long expiresAt() {
      return timestamp + PROPOSAL_LIFETIME_SECONDS;
    }

    public boolean expiredAt(long now) {
      return now >= expiresAt();
    }
  }

  /**
   * Open key requests, oldest first. KeyDictionary keeps them private, so they are found from the {@code
   * KeyRequested} events; that event names the bank that asked, so the key's owner is read too. A request is
   * open while its key's owner is not the bank that asked: once authorized, the owner is that bank.
   *
   * <p>The contract never closes a request, and has no event for an authorization, so a request authorized
   * long ago looks open again once the key moves back. A request made before the current owner's own latest
   * request for the key was made before the key last moved, so it is taken as closed: the contract would
   * still accept it, and move the key again. The contract builds the id from the bank and the block time, so
   * a later request with the same id replaces the earlier one, as it does in the contract.
   */
  public List<KeyRequest> keyRequests() throws Exception {
    var web3j = chain.web3j();
    KeyDictionary keys = KeyDictionary.load(deployment.keyDictionary, web3j, chain.readOnly(), chain.gasProvider());
    List<Requested> events = new java.util.ArrayList<>();
    for (var entry : EventLogs.of(web3j, deployment.keyDictionary, deployment.deployedAt,
        org.web3j.abi.EventEncoder.encode(KeyDictionary.KEYREQUESTED_EVENT))) {
      var event = KeyDictionary.getKeyRequestedEventFromLog(entry);
      events.add(new Requested(event.proposalId, event.key, event.owner, entry));
    }
    java.util.Map<String, String> owners = new java.util.HashMap<>();
    java.util.function.Function<byte[], String> ownerOf = key -> owners.computeIfAbsent(Numeric.toHexString(key), k -> {
      try {
        return keys.getCustomerData(key).send().owner;
      } catch (Exception e) {
        throw new IllegalStateException("Could not read the owner of key " + k + ": " + e.getMessage(), e);
      }
    });
    List<KeyRequest> open = new java.util.ArrayList<>();
    for (Requested event : stillOpen(events, ownerOf)) {
      var entry = event.log();
      long time = web3j.ethGetBlockByHash(entry.getBlockHash(), false).send().getBlock().getTimestamp().longValueExact();
      BigInteger cpf = null;
      String wallet = null;
      try {
        String input = web3j.ethGetTransactionByHash(entry.getTransactionHash()).send().getTransaction().orElseThrow().getInput();
        var args = org.web3j.abi.FunctionReturnDecoder.decode(input.substring(10), REQUEST_KEY_ARGS);
        cpf = (BigInteger) args.get(1).getValue();
        wallet = (String) args.get(5).getValue();
      } catch (Exception ignored) {
        // shown without the CPF and the wallet
      }
      open.add(new KeyRequest(event.id(), event.key(), cpf, event.requester(), wallet, time, ownerOf.apply(event.key())));
    }
    return open;
  }

  /** A {@code KeyRequested} event: the request's id, key and the bank that asked, and its log. */
  record Requested(BigInteger id, byte[] key, String requester, org.web3j.protocol.core.methods.response.Log log) {}

  /**
   * Of the requests, oldest first, those still open, given each key's current owner: see {@link #keyRequests}.
   */
  static List<Requested> stillOpen(List<Requested> events, java.util.function.Function<byte[], String> ownerOf) {
    java.util.Map<BigInteger, Requested> byId = new java.util.LinkedHashMap<>();
    for (Requested event : events) {
      byId.remove(event.id());
      if (ownerOf.apply(event.key()).equalsIgnoreCase(event.requester())) {
        // The owner's own request: authorized, so the requests for this key made before it are over too.
        byId.values().removeIf(r -> java.util.Arrays.equals(r.key(), event.key()));
      } else {
        byId.put(event.id(), event);
      }
    }
    return List.copyOf(byId.values());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static final List<org.web3j.abi.TypeReference<org.web3j.abi.datatypes.Type>> REQUEST_KEY_ARGS =
      (List) List.of(new org.web3j.abi.TypeReference<org.web3j.abi.datatypes.generated.Bytes32>() {},
          new org.web3j.abi.TypeReference<org.web3j.abi.datatypes.generated.Uint256>() {},
          new org.web3j.abi.TypeReference<org.web3j.abi.datatypes.generated.Uint256>() {},
          new org.web3j.abi.TypeReference<org.web3j.abi.datatypes.generated.Uint256>() {},
          new org.web3j.abi.TypeReference<org.web3j.abi.datatypes.generated.Uint256>() {},
          new org.web3j.abi.TypeReference<org.web3j.abi.datatypes.Address>() {});

  /**
   * Flow 14, step 1: a bank asks for the key of a client that another bank registered, for the client's
   * wallet at this bank, which is enabled in this bank's Real Tokenizado if it is not yet. Refused before
   * sending: a CPF without a key, a key this bank already owns, a wallet registered under another CPF (the
   * authorization would point that wallet to a second key), and a request this bank already has open.
   */
  public Proposed requestKey(Account bank, String symbol, Account wallet, BigInteger cpf, BigInteger bankNumber,
      BigInteger accountNumber, BigInteger branch) throws Exception {
    log.accept("Requesting the key of CPF " + cpf + " for " + wallet.name() + " at " + symbol);
    KeyDictionary keys = keyDictionary(bank);
    byte[] key = clientKey(cpf);
    if (isZero(keys.getWallet(key).send())) {
      throw new IllegalStateException(String.format(
          "CPF %s has no key in KeyDictionary, so there is nothing to port: onboard the client instead (flow 8). "
              + "Nothing was sent", cpf));
    }
    String owner = keys.getCustomerData(key).send().owner;
    if (owner.equalsIgnoreCase(bank.address())) {
      throw new IllegalStateException(String.format(
          "Cannot request own key: CPF %s is already registered by %s, so nothing was sent", cpf, bank.address()));
    }
    if (!realDigital(bank).verifyAccount(bank.address()).send()) {
      throw new IllegalStateException(String.format(
          "Must be participant: %s is not enabled in Real Digital, so nothing was sent", bank.address()));
    }
    checkWalletKey(keys, wallet.address(), key);
    long now = blockTime();
    for (KeyRequest r : keyRequests()) {
      if (r.requester().equalsIgnoreCase(bank.address()) && java.util.Arrays.equals(r.key(), key) && !r.expiredAt(now)) {
        throw new IllegalStateException(String.format(
            "%s already asked for the key of CPF %s: request %s waits for %s to authorize it, so nothing was sent",
            bank.address(), cpf, r.id(), r.owner()));
      }
    }
    RealTokenizado token = realTokenizado(symbol, bank);
    List<TransactionReceipt> receipts = new java.util.ArrayList<>();
    if (!token.verifyAccount(wallet.address()).send()) {
      receipts.add(token.enableAccount(wallet.address()).send());
    }
    TransactionReceipt requested = keys.requestKey(key, cpf, bankNumber, accountNumber, branch, wallet.address()).send();
    receipts.add(requested);
    List<KeyDictionary.KeyRequestedEventResponse> events = KeyDictionary.getKeyRequestedEvents(requested);
    return new Proposed(events.isEmpty() ? null : events.get(0).proposalId, receipts);
  }

  /** Refuses a wallet that KeyDictionary already maps to a key other than {@code key}. */
  private static void checkWalletKey(KeyDictionary keys, String wallet, byte[] key) throws Exception {
    byte[] walletKey = keys.getKey(wallet).send();
    if (!isZero(walletKey) && !java.util.Arrays.equals(walletKey, key)) {
      KeyDictionary.CustomerData registered = keys.getCustomerData(walletKey).send();
      throw new IllegalStateException(String.format(
          "%s is already registered under CPF %s, by participant %s, so nothing was sent", wallet, registered.taxId,
          registered.owner));
    }
  }

  /**
   * Flow 14, step 2: the bank that owns a key authorizes another bank's request for it, which moves the key
   * to the new wallet and bank. The client's balance does not move with it.
   */
  public TransactionReceipt authorizeKey(Account owner, BigInteger id) throws Exception {
    return authorizeKey(owner, openKeyRequest(id));
  }

  /** The open key request {@code id}, or a refusal. */
  public KeyRequest openKeyRequest(BigInteger id) throws Exception {
    return keyRequests().stream().filter(k -> k.id().equals(id)).findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "There is no open key request " + id + ": it does not exist or was already authorized, so nothing was sent"));
  }

  /** {@link #authorizeKey(Account, BigInteger)} for a request already read with {@link #openKeyRequest}. */
  public TransactionReceipt authorizeKey(Account owner, KeyRequest r) throws Exception {
    BigInteger id = r.id();
    log.accept("Authorizing key request " + id + " as " + owner.name());
    if (!r.owner().equalsIgnoreCase(owner.address())) {
      throw new IllegalStateException(String.format(
          "Only owner can authorize key transfer: the key of request %s is registered by %s, not %s, so nothing was sent",
          id, r.owner(), owner.address()));
    }
    if (r.expiredAt(blockTime())) {
      throw new IllegalStateException(String.format("Proposal expired: request %s could be authorized until %s, so "
          + "nothing was sent", id, java.time.Instant.ofEpochSecond(r.expiresAt())));
    }
    if (!realDigital(owner).verifyAccount(owner.address()).send()) {
      throw new IllegalStateException(String.format(
          "Must be participant: %s is not enabled in Real Digital, so nothing was sent", owner.address()));
    }
    KeyDictionary keys = keyDictionary(owner);
    if (r.wallet() != null) {
      checkWalletKey(keys, r.wallet(), r.key());
    }
    return keys.authorizeKey(id, r.key()).send();
  }

  // ---------------------------------------------------------------- checks

  /**
   * Refuses, before any transaction, a swap that the contracts would refuse for a reason the payer can see:
   * a paused token, a payer or receiver not enabled in its token, too little balance once frozen funds are
   * set aside, or a bank reserve without the Real Digital to move. Messages start like the contracts' own.
   * {@code reader} only reads; {@code payer} and {@code receiver} are addresses.
   */
  private void checkSwap(Account reader, String payer, String payerName, RealTokenizado from, String fromSymbol,
      RealTokenizado to, String toSymbol, String receiver, BigInteger amount) throws Exception {
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("The amount must be positive, not " + Amounts.format(amount));
    }
    RealDigital rd = realDigital(reader);
    if (from.paused().send()) {
      throw new IllegalStateException("Pausable: paused (" + fromSymbol + " is paused)");
    }
    if (to.paused().send()) {
      throw new IllegalStateException("Pausable: paused (" + toSymbol + " is paused)");
    }
    if (rd.paused().send()) {
      throw new IllegalStateException("Pausable: paused (Real Digital is paused)");
    }
    // RealTokenizado's checkAccess: burning needs the holder enabled, minting the receiver.
    if (!from.verifyAccount(payer).send()) {
      throw new IllegalStateException("Sender is not enabled (" + payerName + " is not enabled in " + fromSymbol + ")");
    }
    if (!to.verifyAccount(receiver).send()) {
      throw new IllegalStateException("Recipient is not enabled (" + receiver + " is not enabled in " + toSymbol + ")");
    }
    BigInteger balance = from.balanceOf(payer).send();
    BigInteger frozen = from.frozenBalanceOf(payer).send();
    BigInteger available = balance.subtract(frozen).max(BigInteger.ZERO);
    if (available.compareTo(amount) < 0) {
      throw new IllegalStateException(String.format(
          "RealDigital: insufficient balance (%s has %s %s available, %s of %s frozen; the payment is %s)",
          payerName, Amounts.format(available), fromSymbol, Amounts.format(frozen), Amounts.format(balance),
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

  public SwapTwoSteps swapTwoSteps(Account as) {
    return SwapTwoSteps.load(deployment.swapTwoSteps, chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  /** The Real Tokenizado at {@code address}, known to the deployment or not. */
  private RealTokenizado rtAt(String address, Account as) {
    return RealTokenizado.load(address, chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  /** The symbol of the Real Tokenizado at {@code address}, or the address if the deployment does not know it. */
  public String symbolAt(String address) {
    return deployment.realTokenizados.stream().filter(t -> t.address().equalsIgnoreCase(address))
        .map(DrexDeployment.Tokenized::symbol).findFirst().orElse(address);
  }

  private DrexDeployment.Tokenized token(String symbol) {
    return deployment.tokenized(symbol)
        .orElseThrow(() -> new IllegalArgumentException("No Real Tokenizado with symbol " + symbol));
  }
}

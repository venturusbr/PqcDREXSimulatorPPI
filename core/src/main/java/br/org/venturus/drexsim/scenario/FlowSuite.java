package br.org.venturus.drexsim.scenario;

import br.org.venturus.drexsim.chain.Chain;
import br.org.venturus.drexsim.chain.SentTransaction;
import br.org.venturus.drexsim.contracts.AddressDiscovery;
import br.org.venturus.drexsim.contracts.KeyDictionary;
import br.org.venturus.drexsim.contracts.RealDigital;
import br.org.venturus.drexsim.contracts.RealDigitalDefaultAccount;
import br.org.venturus.drexsim.contracts.RealDigitalEnableAccount;
import br.org.venturus.drexsim.contracts.RealTokenizado;
import br.org.venturus.drexsim.contracts.STR;
import br.org.venturus.drexsim.contracts.SwapOneStep;
import br.org.venturus.drexsim.contracts.SwapTwoSteps;
import br.org.venturus.drexsim.drex.DrexClient;
import br.org.venturus.drexsim.drex.DrexDeployment;
import br.org.venturus.drexsim.keystore.Account;
import br.org.venturus.drexsim.scenario.FlowResult.Status;
import br.org.venturus.drexsim.scenario.FlowResult.TxStat;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

/**
 * Runs the Drex use-case flows 0 to 18 (docs/use-case-flows.md) on a live network with every actor
 * signing under one {@link SignerProfile}. Each flow checks the on-chain state it must produce,
 * including the calls that must be refused; the transactions it sends are reported with their
 * receipts so the flows can be compared across DSAs.
 *
 * <p>Each run uses fresh accounts and a fresh deployment, so suites for different profiles can run
 * at the same time on the same network.
 */
public final class FlowSuite {

  public static final List<String> FLOW_NAMES =
      List.of(
          "Network setup",
          "Onboard participant",
          "Enable/disable own wallets",
          "Change default wallet",
          "STR mint",
          "STR burn",
          "Inter-bank RD transfer",
          "Deploy Real Tokenizado",
          "Onboard client",
          "Client deposit",
          "Client withdrawal",
          "Same-bank transfer",
          "Inter-bank swap, one step",
          "Inter-bank swap, two steps",
          "Key portability",
          "Freeze and unfreeze",
          "Forced transfer and redemption",
          "Emergency pause",
          "Address discovery");

  /** Flows that every later flow needs; when one fails the rest are skipped. */
  private static final Set<Integer> FOUNDATION = Set.of(0, 1, 7, 8);

  private static final BigInteger CNPJ_A = BigInteger.valueOf(11111111);
  private static final BigInteger CNPJ_B = BigInteger.valueOf(22222222);
  private static final BigInteger CPF_X = new BigInteger("12345678901");
  private static final BigInteger CPF_X2 = new BigInteger("23456789012");
  private static final BigInteger CPF_Y = new BigInteger("34567890123");

  private final String rpcUrl;
  private final SignerProfile profile;
  private final Consumer<String> log;
  private final List<SentTransaction> sent = new ArrayList<>();

  private Chain chain;
  private DrexDeployment d;
  private DrexClient drex;
  private Account bcb, bankA, reserveA, bankB, reserveB, clientX, clientX2, clientY, spare, bankANew, clientXatB;

  public FlowSuite(String rpcUrl, SignerProfile profile, Consumer<String> log) {
    this.rpcUrl = rpcUrl;
    this.profile = profile;
    this.log = log == null ? s -> {} : log;
  }

  public SignerProfile profile() {
    return profile;
  }

  public List<FlowResult> run() throws Exception {
    List<FlowResult> results = new ArrayList<>();
    try (Chain c = new Chain(rpcUrl)) {
      this.chain = c;
      chain.addListener(this::record);
      createActors();
      boolean foundationFailed = false;
      for (int flow = 0; flow < FLOW_NAMES.size(); flow++) {
        if (foundationFailed) {
          results.add(new FlowResult(profile.label(), flow, FLOW_NAMES.get(flow), Status.SKIPPED,
              "a setup flow failed", List.of(), 0));
          continue;
        }
        FlowResult result = runFlow(flow);
        results.add(result);
        log.accept(String.format("[%s] flow %2d %-32s %s %s", profile.label(), flow, FLOW_NAMES.get(flow),
            result.status(), result.detail() == null ? "" : result.detail()));
        foundationFailed = result.status() == Status.FAIL && FOUNDATION.contains(flow);
      }
    }
    return results;
  }

  private FlowResult runFlow(int flow) {
    synchronized (sent) {
      sent.clear();
    }
    long t0 = System.currentTimeMillis();
    Status status = Status.PASS;
    String detail = null;
    try {
      switch (flow) {
        case 0 -> flow0NetworkSetup();
        case 1 -> flow1OnboardParticipants();
        case 2 -> flow2EnableDisableOwnWallets();
        case 3 -> flow3ChangeDefaultWallet();
        case 4 -> flow4StrMint();
        case 5 -> flow5StrBurn();
        case 6 -> flow6InterBankTransfer();
        case 7 -> flow7DeployRealTokenizado();
        case 8 -> flow8OnboardClients();
        case 9 -> flow9Deposits();
        case 10 -> flow10Withdrawals();
        case 11 -> flow11SameBankTransfer();
        case 12 -> flow12SwapOneStep();
        case 13 -> flow13SwapTwoSteps();
        case 14 -> flow14KeyPortability();
        case 15 -> flow15FreezeUnfreeze();
        case 16 -> flow16ForcedTransferAndRedemption();
        case 17 -> flow17Pause();
        case 18 -> flow18AddressDiscovery();
        default -> throw new IllegalArgumentException("No flow " + flow);
      }
    } catch (Exception | AssertionError e) {
      status = Status.FAIL;
      detail = rootMessage(e);
    }
    long millis = System.currentTimeMillis() - t0;
    return new FlowResult(profile.label(), flow, FLOW_NAMES.get(flow), status, detail, receipts(), millis);
  }

  // ------------------------------------------------------------------ flows

  private void flow0NetworkSetup() throws Exception {
    d = DrexClient.deployNetwork(chain, bcb, null);
    drex = new DrexClient(chain, d, null);
    AddressDiscovery discovery = AddressDiscovery.load(d.addressDiscovery, chain.web3j(), chain.readOnly(), chain.gasProvider());
    expectEquals(d.realDigital, discovery.addressDiscovery(Hash.sha3("RealDigital".getBytes())).send(), "RealDigital in AddressDiscovery");
    expect(rd(bcb).hasRole(DrexClient.MINTER_ROLE, d.str).send(), "STR has MINTER_ROLE");
    expect(rd(bcb).hasRole(DrexClient.MOVER_ROLE, d.swapTwoSteps).send(), "SwapTwoSteps has MOVER_ROLE");
  }

  private void flow1OnboardParticipants() throws Exception {
    drex.onboardParticipant(bcb, bankA, reserveA, CNPJ_A);
    drex.onboardParticipant(bcb, bankB, reserveB, CNPJ_B);
    expect(rd(bcb).verifyAccount(bankA.address()).send(), "bank A enabled");
    expect(rd(bcb).verifyAccount(reserveB.address()).send(), "reserve B enabled");
    expectEquals(bankA.address(), defaultAccount(bcb).defaultAccount(CNPJ_A).send(), "default wallet of bank A");
  }

  private void flow2EnableDisableOwnWallets() throws Exception {
    enableAccount(bankA).enableAccount(spare.address()).send();
    expect(rd(bcb).verifyAccount(spare.address()).send(), "wallet enabled by its participant");
    enableAccount(spare).disableAccount().send();
    expect(!rd(bcb).verifyAccount(spare.address()).send(), "wallet disabled by itself");
  }

  private void flow3ChangeDefaultWallet() throws Exception {
    enableAccount(bankA).enableAccount(bankANew.address()).send();
    defaultAccount(bankA).updateDefaultWallet(CNPJ_A, bankANew.address()).send();
    expectEquals(bankANew.address(), defaultAccount(bcb).defaultAccount(CNPJ_A).send(), "new default wallet");
    expectRefused("old default wallet changes it again",
        () -> defaultAccount(bankA).updateDefaultWallet(CNPJ_A, bankA.address()).send());
  }

  private void flow4StrMint() throws Exception {
    drex.strMint(reserveA, reais(100_000));
    drex.strMint(reserveB, reais(100_000));
    drex.strMint(bankA, reais(5_000));
    expectEquals(reais(5_000), rd(bcb).balanceOf(bankA.address()).send(), "bank A RD");
    expectEquals(reais(5_000), rd(bcb).allowance(bankA.address(), d.str).send(), "STR allowance over bank A");
    expectRefused("STR mint by a non-participant", () -> str(clientX).requestToMint(BigInteger.ONE).send());
  }

  private void flow5StrBurn() throws Exception {
    str(bankA).requestToBurn(reais(1_000)).send();
    expectEquals(reais(4_000), rd(bcb).balanceOf(bankA.address()).send(), "bank A RD after burn");
  }

  private void flow6InterBankTransfer() throws Exception {
    expect(rd(bcb).verifyAccount(bankB.address()).send(), "recipient enabled");
    rd(bankA).transfer(bankB.address(), reais(500)).send();
    expectEquals(reais(500), rd(bcb).balanceOf(bankB.address()).send(), "bank B RD");
    expectRefused("transfer from a wallet that is not enabled", () -> rd(clientX).transfer(bankB.address(), BigInteger.ONE).send());
  }

  private void flow7DeployRealTokenizado() throws Exception {
    drex.deployRealTokenizado(bcb, bankA, reserveA, "Banco A", "BRLA", CNPJ_A);
    drex.deployRealTokenizado(bcb, bankB, reserveB, "Banco B", "BRLB", CNPJ_B);
    expectEquals(CNPJ_A, rt("BRLA", bcb).cnpj8().send(), "BRLA cnpj8");
    expectEquals(reserveB.address(), rt("BRLB", bcb).reserve().send(), "BRLB reserve");
    expect(rt("BRLB", bcb).hasRole(DrexClient.MINTER_ROLE, d.swapOneStep).send(), "SwapOneStep mints BRLB");
  }

  private void flow8OnboardClients() throws Exception {
    drex.onboardClient(bankA, "BRLA", clientX, CPF_X, BigInteger.ONE, BigInteger.valueOf(101), BigInteger.ONE);
    drex.onboardClient(bankA, "BRLA", clientX2, CPF_X2, BigInteger.ONE, BigInteger.valueOf(102), BigInteger.ONE);
    drex.onboardClient(bankB, "BRLB", clientY, CPF_Y, BigInteger.TWO, BigInteger.valueOf(201), BigInteger.ONE);
    expectEquals(clientY.address(), keyDictionary(bcb).getWallet(DrexClient.clientKey(CPF_Y)).send(), "client Y key");
    KeyDictionary.CustomerData data = keyDictionary(bcb).getCustomerData(DrexClient.clientKey(CPF_X)).send();
    expectEquals(CPF_X, data.taxId, "client X CPF");
    expectEquals(bankA.address(), data.owner, "client X key owner");
  }

  private void flow9Deposits() throws Exception {
    drex.deposit(bankA, "BRLA", clientX, reais(10_000));
    drex.deposit(bankA, "BRLA", clientX2, reais(2_000));
    drex.deposit(bankB, "BRLB", clientY, reais(1_000));
    expectEquals(reais(10_000), rt("BRLA", bcb).balanceOf(clientX.address()).send(), "client X BRLA");
    expectEquals(reais(1_000), rt("BRLB", bcb).balanceOf(clientY.address()).send(), "client Y BRLB");
  }

  private void flow10Withdrawals() throws Exception {
    // Option A: the client approves, the bank burns.
    rt("BRLA", clientX).approve(bankA.address(), reais(500)).send();
    rt("BRLA", bankA).burnFrom(clientX.address(), reais(500)).send();
    expectEquals(reais(9_500), rt("BRLA", bcb).balanceOf(clientX.address()).send(), "client X after withdrawal A");
    // Option B: forced by the bank.
    rt("BRLA", bankA).moveAndBurn(clientX2.address(), reais(200)).send();
    expectEquals(reais(1_800), rt("BRLA", bcb).balanceOf(clientX2.address()).send(), "client X2 after withdrawal B");
  }

  private void flow11SameBankTransfer() throws Exception {
    String receiver = keyDictionary(bcb).getWallet(DrexClient.clientKey(CPF_X2)).send();
    rt("BRLA", clientX).transfer(receiver, reais(1_000)).send();
    expectEquals(reais(8_500), rt("BRLA", bcb).balanceOf(clientX.address()).send(), "client X");
    expectEquals(reais(2_800), rt("BRLA", bcb).balanceOf(clientX2.address()).send(), "client X2");
  }

  private void flow12SwapOneStep() throws Exception {
    BigInteger reserveABefore = rd(bcb).balanceOf(reserveA.address()).send();
    String walletY = keyDictionary(bcb).getWallet(DrexClient.clientKey(CPF_Y)).send();
    drex.payOneStep(clientX, "BRLA", "BRLB", walletY, reais(1_500));
    expectEquals(reais(7_000), rt("BRLA", bcb).balanceOf(clientX.address()).send(), "client X BRLA");
    expectEquals(reais(2_500), rt("BRLB", bcb).balanceOf(clientY.address()).send(), "client Y BRLB");
    expectEquals(reserveABefore.subtract(reais(1_500)), rd(bcb).balanceOf(reserveA.address()).send(), "reserve A RD");
    expectRefused("swap without allowance", () -> swapOne(clientX)
        .executeSwap(d.tokenized("BRLA").orElseThrow().address(), d.tokenized("BRLB").orElseThrow().address(),
            walletY, reais(1)).send());
  }

  private void flow13SwapTwoSteps() throws Exception {
    String brla = d.tokenized("BRLA").orElseThrow().address();
    String brlb = d.tokenized("BRLB").orElseThrow().address();

    rt("BRLA", clientX).approve(d.swapTwoSteps, reais(700)).send();
    BigInteger accepted = proposalId(swapTwo(clientX).startSwap(brla, brlb, clientY.address(), reais(700)).send());
    expectEquals(reais(2_500), rt("BRLB", bcb).balanceOf(clientY.address()).send(), "nothing moves at proposal");
    expectRefused("payer executes its own proposal", () -> swapTwo(clientX).executeSwap(accepted).send());
    swapTwo(clientY).executeSwap(accepted).send();
    expectEquals(reais(3_200), rt("BRLB", bcb).balanceOf(clientY.address()).send(), "client Y after acceptance");
    expectEquals(BigInteger.ONE, swapTwo(bcb).swapProposals(accepted).send().component6(), "EXECUTED");

    BigInteger cancelled = proposalId(swapTwo(clientX).startSwap(brla, brlb, clientY.address(), reais(100)).send());
    swapTwo(clientY).cancelSwap(cancelled, "not expected").send();
    expectEquals(BigInteger.TWO, swapTwo(bcb).swapProposals(cancelled).send().component6(), "CANCELLED");
    expectEquals(reais(6_300), rt("BRLA", bcb).balanceOf(clientX.address()).send(), "client X after both");
  }

  private void flow14KeyPortability() throws Exception {
    byte[] key = DrexClient.clientKey(CPF_X);
    rt("BRLB", bankB).enableAccount(clientXatB.address()).send();
    TransactionReceipt request = keyDictionary(bankB)
        .requestKey(key, CPF_X, BigInteger.TWO, BigInteger.valueOf(299), BigInteger.ONE, clientXatB.address()).send();
    List<KeyDictionary.KeyRequestedEventResponse> events = KeyDictionary.getKeyRequestedEvents(request);
    expect(events.size() == 1, "one KeyRequested event");
    expectRefused("a participant that does not own the key authorizes it",
        () -> keyDictionary(bankB).authorizeKey(events.get(0).proposalId, key).send());
    keyDictionary(bankA).authorizeKey(events.get(0).proposalId, key).send();
    KeyDictionary.CustomerData data = keyDictionary(bcb).getCustomerData(key).send();
    expectEquals(bankB.address(), data.owner, "key owner after portability");
    expectEquals(clientXatB.address(), data.wallet, "key wallet after portability");
  }

  private void flow15FreezeUnfreeze() throws Exception {
    rt("BRLA", bankA).increaseFrozenBalance(clientX2.address(), reais(800)).send();
    expectEquals(reais(800), rt("BRLA", bcb).frozenBalanceOf(clientX2.address()).send(), "frozen");
    expectRefused("spending the frozen part", () -> rt("BRLA", clientX2).transfer(clientX.address(), reais(2_100)).send());
    rt("BRLA", clientX2).transfer(clientX.address(), reais(100)).send(); // the unfrozen part still moves
    rt("BRLA", bankA).decreaseFrozenBalance(clientX2.address(), reais(800)).send();
    expectEquals(BigInteger.ZERO, rt("BRLA", bcb).frozenBalanceOf(clientX2.address()).send(), "unfrozen");
    expectRefused("freeze by a wallet without FREEZER_ROLE",
        () -> rt("BRLA", clientX).increaseFrozenBalance(clientX2.address(), BigInteger.ONE).send());
  }

  private void flow16ForcedTransferAndRedemption() throws Exception {
    BigInteger x = rt("BRLA", bcb).balanceOf(clientX.address()).send();
    BigInteger x2 = rt("BRLA", bcb).balanceOf(clientX2.address()).send();
    rt("BRLA", bankA).move(clientX2.address(), clientX.address(), reais(300)).send();
    rt("BRLA", bankA).moveAndBurn(clientX2.address(), reais(100)).send();
    expectEquals(x.add(reais(300)), rt("BRLA", bcb).balanceOf(clientX.address()).send(), "receiver of the forced transfer");
    expectEquals(x2.subtract(reais(400)), rt("BRLA", bcb).balanceOf(clientX2.address()).send(), "holder after both");
    expectRefused("move by a wallet without MOVER_ROLE",
        () -> rt("BRLA", clientX).move(clientX2.address(), clientX.address(), BigInteger.ONE).send());
  }

  private void flow17Pause() throws Exception {
    rd(bcb).pause().send();
    expect(rd(bcb).paused().send(), "paused");
    expectRefused("STR mint while paused", () -> str(bankA).requestToMint(BigInteger.ONE).send());
    rd(bcb).unpause().send();
    expect(!rd(bcb).paused().send(), "unpaused");
    expectRefused("pause by a wallet without PAUSER_ROLE", () -> rd(bankA).pause().send());
  }

  private void flow18AddressDiscovery() throws Exception {
    AddressDiscovery discovery = AddressDiscovery.load(d.addressDiscovery, chain.web3j(), chain.readOnly(), chain.gasProvider());
    Map<String, String> expected = Map.of(
        "RealDigital", d.realDigital, "STR", d.str, "KeyDictionary", d.keyDictionary,
        "SwapOneStep", d.swapOneStep, "SwapTwoSteps", d.swapTwoSteps,
        "RealTokenizado@" + CNPJ_A, d.tokenized("BRLA").orElseThrow().address());
    for (Map.Entry<String, String> e : expected.entrySet()) {
      expectEquals(e.getValue(), discovery.addressDiscovery(Hash.sha3(e.getKey().getBytes())).send(), e.getKey());
    }
    expectRefused("updateAddress by a wallet without ACCESS_ROLE",
        () -> AddressDiscovery.load(d.addressDiscovery, chain.web3j(), chain.managerFor(bankA), chain.gasProvider())
            .updateAddress(Hash.sha3("RealDigital".getBytes()), bankA.address()).send());
  }

  // ------------------------------------------------------------------ helpers

  private void createActors() {
    bcb = actor("bcb");
    bankA = actor("bank-a");
    reserveA = actor("reserve-a");
    bankB = actor("bank-b");
    reserveB = actor("reserve-b");
    clientX = actor("client-x");
    clientX2 = actor("client-x2");
    clientY = actor("client-y");
    spare = actor("spare");
    bankANew = actor("bank-a-new");
    clientXatB = actor("client-x@b");
  }

  private Account actor(String name) {
    return Account.generate(profile.label() + "/" + name, profile.dsa(), profile.format());
  }

  private static BigInteger reais(long reais) {
    return BigInteger.valueOf(reais).multiply(BigInteger.valueOf(100));
  }

  private RealDigital rd(Account as) {
    return RealDigital.load(d.realDigital, chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  private RealTokenizado rt(String symbol, Account as) {
    return drex.realTokenizado(symbol, as);
  }

  private STR str(Account as) {
    return STR.load(d.str, chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  private RealDigitalEnableAccount enableAccount(Account as) {
    return RealDigitalEnableAccount.load(d.realDigitalEnableAccount, chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  private RealDigitalDefaultAccount defaultAccount(Account as) {
    return RealDigitalDefaultAccount.load(d.realDigitalDefaultAccount, chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  private KeyDictionary keyDictionary(Account as) {
    return drex.keyDictionary(as);
  }

  private SwapOneStep swapOne(Account as) {
    return SwapOneStep.load(d.swapOneStep, chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  private SwapTwoSteps swapTwo(Account as) {
    return SwapTwoSteps.load(d.swapTwoSteps, chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  private static BigInteger proposalId(TransactionReceipt receipt) {
    List<SwapTwoSteps.SwapStartedEventResponse> events = SwapTwoSteps.getSwapStartedEvents(receipt);
    if (events.size() != 1) {
      throw new AssertionError("expected one SwapStarted event, got " + events.size());
    }
    return events.get(0).proposalId;
  }

  @FunctionalInterface
  private interface Call {
    void run() throws Exception;
  }

  /**
   * Asserts the call is refused. The node rejects it when the gas is estimated, so nothing is
   * mined, as it would be for a wallet that checks before sending.
   */
  private static void expectRefused(String what, Call call) {
    try {
      call.run();
    } catch (Exception expected) {
      return;
    }
    throw new AssertionError("expected to be refused: " + what);
  }

  private static void expect(Boolean condition, String what) {
    if (!Boolean.TRUE.equals(condition)) {
      throw new AssertionError("check failed: " + what);
    }
  }

  private static void expectEquals(Object expected, Object actual, String what) {
    boolean equal = expected instanceof String e && actual instanceof String a ? e.equalsIgnoreCase(a) : Objects.equals(expected, actual);
    if (!equal) {
      throw new AssertionError(what + ": expected " + expected + ", got " + actual);
    }
  }

  private static String rootMessage(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    return message.length() > 200 ? message.substring(0, 200) + "..." : message;
  }

  private void record(SentTransaction tx) {
    synchronized (sent) {
      sent.add(tx);
    }
  }

  private List<TxStat> receipts() {
    List<SentTransaction> copy;
    synchronized (sent) {
      copy = new ArrayList<>(sent);
    }
    List<TxStat> stats = new ArrayList<>();
    for (SentTransaction tx : copy) {
      if (!tx.accepted()) {
        continue;
      }
      try {
        TransactionReceipt r = chain.web3j().ethGetTransactionReceipt(tx.hash()).send().getTransactionReceipt().orElse(null);
        stats.add(new TxStat(tx.hash(), tx.account(), r == null ? 0 : r.getGasUsed().longValueExact(), tx.size(),
            tx.signatureLength(), tx.publicKeyLength(), r != null && r.isStatusOK()));
      } catch (Exception e) {
        stats.add(new TxStat(tx.hash(), tx.account(), 0, tx.size(), tx.signatureLength(), tx.publicKeyLength(), false));
      }
    }
    return stats;
  }
}

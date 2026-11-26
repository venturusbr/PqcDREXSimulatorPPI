package br.org.venturus.drexsim.gui;

import br.org.venturus.drexsim.bench.BenchReport;
import br.org.venturus.drexsim.bench.LoadTest;
import br.org.venturus.drexsim.chain.Chain;
import br.org.venturus.drexsim.contracts.RealDigital;
import br.org.venturus.drexsim.contracts.RealDigitalEnableAccount;
import br.org.venturus.drexsim.contracts.STR;
import br.org.venturus.drexsim.crypto.Dsa;
import br.org.venturus.drexsim.drex.Amounts;
import br.org.venturus.drexsim.drex.BalanceService;
import br.org.venturus.drexsim.drex.DemoSetup;
import br.org.venturus.drexsim.drex.DrexClient;
import br.org.venturus.drexsim.drex.DrexDeployment;
import br.org.venturus.drexsim.keystore.Account;
import br.org.venturus.drexsim.keystore.AccountStore;
import br.org.venturus.drexsim.scenario.FlowMatrix;
import br.org.venturus.drexsim.scenario.SignerProfile;
import br.org.venturus.drexsim.security.SecurityReport;
import br.org.venturus.drexsim.security.SecurityResult;
import br.org.venturus.drexsim.security.SecuritySuite;
import br.org.venturus.drexsim.tx.TxFormat;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;

/** What the web API does: network state, accounts, balances, Drex operations and evaluation jobs. */
final class SimulatorService implements AutoCloseable {

  /** A field of an operation form. Types: account, wallet (account or address), token, rt, amount, text, number. */
  record Field(String name, String label, String type) {}

  /** An operation the GUI offers, tied to its use-case flow in docs/use-case-flows.md. */
  record Operation(String id, String label, int flow, String signer, String description, List<Field> fields) {}

  /** Result of an operation. */
  record OpResult(String operation, boolean ok, String message, String signer, String node, List<String> transactions) {}

  record AccountView(String name, String address, String dsa, String format, String signing, boolean quantumResistant,
      int publicKeyBytes) {}

  static final List<Operation> OPERATIONS = List.of(
      new Operation("str-mint", "Issue Real Digital (STR)", 4, "the participant",
          "Converts the participant's reserves at BCB into Real Digital.",
          List.of(new Field("account", "Participant", "account"), new Field("amount", "Amount (R$)", "amount"))),
      new Operation("str-burn", "Redeem Real Digital (STR)", 5, "the participant",
          "Burns Real Digital previously issued through STR.",
          List.of(new Field("account", "Participant", "account"), new Field("amount", "Amount (R$)", "amount"))),
      new Operation("rd-transfer", "Transfer Real Digital", 6, "the sender",
          "Wholesale transfer between participants.",
          List.of(new Field("from", "From", "account"), new Field("to", "To", "wallet"), new Field("amount", "Amount (R$)", "amount"))),
      new Operation("deposit", "Client deposit (mint RT)", 9, "the bank of the token",
          "The bank issues Real Tokenizado to its client.",
          List.of(new Field("token", "Real Tokenizado", "rt"), new Field("client", "Client", "account"), new Field("amount", "Amount (R$)", "amount"))),
      new Operation("withdraw", "Client withdrawal (forced redemption)", 10, "the bank of the token",
          "The bank burns Real Tokenizado from its client (moveAndBurn).",
          List.of(new Field("token", "Real Tokenizado", "rt"), new Field("client", "Client", "wallet"), new Field("amount", "Amount (R$)", "amount"))),
      new Operation("rt-transfer", "Transfer between clients of the same bank", 11, "the paying client",
          "Only Real Tokenizado moves.",
          List.of(new Field("from", "From", "account"), new Field("token", "Real Tokenizado", "rt"), new Field("to", "To", "wallet"),
              new Field("amount", "Amount (R$)", "amount"))),
      new Operation("swap", "Payment to a client of another bank", 12, "the paying client",
          "SwapOneStep: burns the payer's Real Tokenizado, moves Real Digital between the bank reserves, mints the receiver's.",
          List.of(new Field("from", "From", "account"), new Field("fromToken", "Payer's Real Tokenizado", "rt"),
              new Field("toToken", "Receiver's Real Tokenizado", "rt"), new Field("to", "Receiver", "wallet"),
              new Field("amount", "Amount (R$)", "amount"))),
      new Operation("freeze", "Freeze balance", 15, "the token's authority",
          "Judicial block: part of the balance can no longer be spent.",
          List.of(new Field("token", "Token", "token"), new Field("wallet", "Wallet", "wallet"), new Field("amount", "Amount (R$)", "amount"))),
      new Operation("unfreeze", "Unfreeze balance", 15, "the token's authority", "Releases a frozen amount.",
          List.of(new Field("token", "Token", "token"), new Field("wallet", "Wallet", "wallet"), new Field("amount", "Amount (R$)", "amount"))),
      new Operation("forced-transfer", "Forced transfer", 16, "the token's authority",
          "Moves a balance without the holder's signature (court order).",
          List.of(new Field("token", "Token", "token"), new Field("from", "From", "wallet"), new Field("to", "To", "wallet"),
              new Field("amount", "Amount (R$)", "amount"))),
      new Operation("pause", "Pause token", 17, "the token's authority", "Emergency pause: blocks every movement.",
          List.of(new Field("token", "Token", "token"))),
      new Operation("unpause", "Resume token", 17, "the token's authority", "Ends the emergency pause.",
          List.of(new Field("token", "Token", "token"))),
      new Operation("onboard-participant", "Onboard a participant", 1, "BCB",
          "Enables a bank and its reserve wallet in Real Digital and records its default wallet.",
          List.of(new Field("participant", "Participant", "account"), new Field("reserve", "Reserve wallet", "account"),
              new Field("cnpj8", "CNPJ8", "number"))),
      new Operation("deploy-rt", "Deploy a participant's Real Tokenizado", 7, "the participant",
          "The bank deploys its tokenized deposit and authorizes the swaps; BCB registers it.",
          List.of(new Field("participant", "Participant", "account"), new Field("reserve", "Reserve wallet", "account"),
              new Field("name", "Participant name", "text"), new Field("symbol", "Symbol", "text"),
              new Field("cnpj8", "CNPJ8", "number"))),
      new Operation("onboard-client", "Onboard a client", 8, "the bank of the token",
          "Enables the client in the bank's Real Tokenizado and registers its key (CPF) in KeyDictionary. A CPF "
              + "registered to another wallet is refused; a client whose CPF is already its key is only enabled.",
          List.of(new Field("token", "Real Tokenizado", "rt"), new Field("client", "Client", "account"), new Field("cpf", "CPF", "number"))),
      new Operation("enable-wallet", "Enable a wallet in Real Digital", 2, "an enabled participant",
          "A participant enables another wallet through RealDigitalEnableAccount.",
          List.of(new Field("participant", "Participant", "account"), new Field("wallet", "Wallet", "wallet"))));

  private final String rpcUrl;
  private final Path dataDir;
  private final Path reportsDir;
  private final Chain chain;
  private final AccountStore store;
  private final EventHub events;
  private final TxLog txLog;
  private final JobRunner jobs;
  private final NodeDirectory nodes;
  private final Map<String, Chain> chains = new java.util.concurrent.ConcurrentHashMap<>();
  private final AbiCatalog.Catalog abi;
  private final ThreadLocal<String> currentOperation = ThreadLocal.withInitial(() -> "");
  private final ScheduledExecutorService blocks = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "block-poller");
    t.setDaemon(true);
    return t;
  });
  private volatile DrexDeployment deployment;
  private volatile long lastBlock = -1;

  SimulatorService(String rpcUrl, List<String> nodeUrls, Path dataDir, Path reportsDir, EventHub events) throws IOException {
    this.rpcUrl = rpcUrl;
    this.dataDir = dataDir;
    this.reportsDir = reportsDir;
    this.events = events;
    this.chain = new Chain(rpcUrl);
    this.store = AccountStore.open(dataDir);
    // Every besu-qbft network has the same chain id, so also check that the contracts exist here.
    this.deployment = DrexDeployment.load(dataDir)
        .filter(d -> d.chainId == chain.chainId() && hasCode(d.realDigital)).orElse(null);
    this.txLog = new TxLog(chain.web3j(), events);
    this.jobs = new JobRunner(events);
    this.nodes = new NodeDirectory(rpcUrl, nodeUrls);
    nodes.discover();
    this.abi = AbiCatalog.load();
    chains.put(rpcUrl, chain);
    chain.addListener(tx -> txLog.add(currentOperation.get(), nodeName(rpcUrl), tx));
    blocks.scheduleWithFixedDelay(this::pollBlock, 0, 1, TimeUnit.SECONDS);
  }

  private boolean hasCode(String address) {
    try {
      String code = chain.web3j().ethGetCode(address, org.web3j.protocol.core.DefaultBlockParameterName.LATEST).send().getCode();
      return code != null && code.length() > 2;
    } catch (Exception e) {
      return false;
    }
  }

  private void pollBlock() {
    try {
      long block = chain.blockNumber();
      if (block != lastBlock) {
        lastBlock = block;
        events.send("block", Map.of("number", block));
      }
    } catch (Exception ignored) {
      // node unreachable for a moment
    }
  }

  // ------------------------------------------------------------------ nodes

  List<NodeDirectory.Node> nodeList() {
    return nodes.nodes();
  }

  List<NodeDirectory.NodeStatus> nodeStatus() {
    return nodes.status(validators());
  }

  List<NodeDirectory.Node> rescanNodes() {
    List<NodeDirectory.Node> found = nodes.discover();
    events.send("nodes", Map.of("count", found.size()));
    return found;
  }

  private String nodeName(String url) {
    return nodes.nodes().stream().filter(n -> n.url().equals(url)).map(NodeDirectory.Node::name).findFirst().orElse(url);
  }

  /** The connection to a node. All share the primary's nonce counters, so an account can switch nodes. */
  /** Waits, for up to 10 s, until the primary node has imported {@code block}. */
  private void awaitPrimary(long block) throws InterruptedException {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < deadline) {
      try {
        if (chain.blockNumber() >= block) return;
      } catch (Exception e) {
        return; // the primary is unreachable: the next block event refreshes the balances anyway
      }
      Thread.sleep(200);
    }
  }

  private Chain chainFor(NodeDirectory.Node node) {
    return chains.computeIfAbsent(node.url(), url -> {
      try {
        Chain c = new Chain(url, chain.nonces());
        if (c.chainId() != chain.chainId()) {
          c.close();
          throw new IllegalStateException(node.name() + " is on chain " + c.chainId() + ", not " + chain.chainId());
        }
        c.addListener(tx -> txLog.add(currentOperation.get(), nodeName(url), tx));
        return c;
      } catch (IOException e) {
        throw new IllegalStateException(node.name() + " (" + url + ") is not reachable: " + e.getMessage(), e);
      }
    });
  }

  AbiCatalog.Catalog abi() {
    return abi;
  }

  // ------------------------------------------------------------------ transaction details

  /**
   * eth_getTransactionByHash and eth_getTransactionReceipt as the node returns them, plus what the
   * page needs to label them: the signature algorithm, the block time and, if it was sent from here,
   * the log entry.
   */
  Map<String, Object> transactionDetails(String hash, String nodeName) throws IOException {
    if (hash == null || !hash.matches("0x[0-9a-fA-F]{64}")) {
      throw new IllegalArgumentException("Not a transaction hash: " + hash);
    }
    NodeDirectory.Node node = nodes.resolve(nodeName);
    JsonNode tx = Rpc.call(node.url(), "eth_getTransactionByHash", hash);
    if (tx.isNull() || tx.isMissingNode()) {
      throw new java.util.NoSuchElementException("Transaction " + hash + " is not known to " + node.name());
    }
    JsonNode receipt = Rpc.call(node.url(), "eth_getTransactionReceipt", hash);
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("node", node.name());
    d.put("nodeUrl", node.url());
    d.put("transaction", tx);
    d.put("receipt", receipt);
    if (receipt.hasNonNull("blockHash")) {
      try {
        JsonNode block = Rpc.call(node.url(), "eth_getBlockByHash", receipt.get("blockHash").asText(), false);
        d.put("blockTimestamp", Rpc.quantity(block.path("timestamp")));
        d.put("blockGasLimit", Rpc.quantity(block.path("gasLimit")));
        d.put("blockTransactions", block.path("transactions").size());
      } catch (Exception ignored) {
        // the details still make sense without the block
      }
    }
    if (tx.hasNonNull("dsaType")) {
      try {
        Dsa dsa = Dsa.fromId(Integer.decode(tx.get("dsaType").asText()).shortValue());
        d.put("dsa", dsa.wireName());
        d.put("quantumResistant", dsa.isQuantumResistant());
        d.put("hybrid", dsa.isHybrid());
      } catch (Exception ignored) {
        // unknown id: the raw value is still shown
      }
    } else {
      d.put("dsa", "SECP256K1");
      d.put("quantumResistant", false);
      d.put("hybrid", false);
    }
    txLog.find(hash).ifPresent(e -> d.put("sent", e));
    return d;
  }

  // ------------------------------------------------------------------ queries

  Map<String, Object> network() {
    Map<String, Object> n = new LinkedHashMap<>();
    n.put("rpc", rpcUrl);
    n.put("chainId", chain.chainId());
    try {
      n.put("block", chain.blockNumber());
      n.put("accountDsas", chain.supportedDsas());
      n.put("validators", validators());
      n.put("reachable", true);
    } catch (Exception e) {
      n.put("reachable", false);
      n.put("error", e.getMessage());
    }
    n.put("deployed", deployment != null);
    return n;
  }

  /** {@code qbft_getValidatorsByBlockNumber} response. */
  public static final class Validators extends Response<List<String>> {}

  private List<String> validators() {
    try {
      // Through the chain's own client: a new HttpService per call left one more idle connection
      // open on the node each time, until the node hit its connection limit.
      Validators r = new Request<>("qbft_getValidatorsByBlockNumber", List.of("latest"), chain.web3jService(),
          Validators.class).send();
      return r.getResult() == null ? List.of() : r.getResult();
    } catch (Exception e) {
      return List.of();
    }
  }

  List<AccountView> accounts() {
    return store.all().stream().map(a -> new AccountView(a.name(), a.address(), a.dsa().wireName(), a.format().name(),
        a.signingLabel(), a.dsa().isQuantumResistant(), a.dsa().publicKeyLength())).toList();
  }

  AccountView createAccount(String name, String dsa, boolean standard) throws IOException {
    if (name == null || !name.matches("[A-Za-z0-9._@-]{1,32}")) {
      throw new IllegalArgumentException("Name: 1 to 32 letters, digits or . _ @ -");
    }
    Account a = store.create(name, Dsa.fromName(dsa), standard ? TxFormat.STANDARD : TxFormat.FLEXIBLE);
    events.send("accounts", Map.of("created", a.name()));
    return accounts().stream().filter(v -> v.name().equals(a.name())).findFirst().orElseThrow();
  }

  Optional<DrexDeployment> deployment() {
    return Optional.ofNullable(deployment);
  }

  /** The last balances read, valid for every request at the same head block, wallets and tokens. */
  private record CachedSnapshot(long head, Object key, BalanceService.Snapshot snapshot) {}

  private CachedSnapshot lastSnapshot;

  /**
   * Balances of every account. Every open page asks at each new block, so requests are served one at a
   * time and share the snapshot of the current block: a block costs one read however many pages are open.
   */
  synchronized Optional<BalanceService.Snapshot> balances() throws Exception {
    if (deployment == null) {
      return Optional.empty();
    }
    List<BalanceService.Wallet> wallets = new ArrayList<>();
    for (Account a : store.all()) {
      wallets.add(new BalanceService.Wallet(a.name(), a.address(), a.signingLabel()));
    }
    Object key = List.of(deployment.realDigital, List.copyOf(deployment.realTokenizados), wallets);
    long head = chain.blockNumber();
    CachedSnapshot cached = lastSnapshot;
    if (cached != null && cached.head() == head && cached.key().equals(key)) {
      return Optional.of(cached.snapshot());
    }
    BalanceService.Snapshot snapshot = new BalanceService(chain, deployment).snapshot(wallets);
    lastSnapshot = new CachedSnapshot(snapshot.blockNumber(), key, snapshot);
    return Optional.of(snapshot);
  }

  List<TxLog.Entry> transactions(int limit) {
    return txLog.recent(limit);
  }

  JobRunner jobs() {
    return jobs;
  }

  // ------------------------------------------------------------------ operations

  OpResult run(String op, Map<String, String> p) {
    Operation spec = OPERATIONS.stream().filter(o -> o.id().equals(op)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown operation " + op));
    if (deployment == null) {
      return new OpResult(op, false, "No Drex network deployed yet: run the demo setup or deploy first", null, null, List.of());
    }
    NodeDirectory.Node node = nodes.resolve(p.get("node"));
    currentOperation.set(spec.label());
    List<String> hashes = new ArrayList<>();
    Account signer = null;
    try {
      Chain chain = chainFor(node);
      DrexClient drex = new DrexClient(chain, deployment, null);
      switch (op) {
        case "str-mint" -> {
          signer = account(p, "account");
          hashes.add(drex.strMint(signer, amount(p)).getTransactionHash());
        }
        case "str-burn" -> {
          signer = account(p, "account");
          hashes.add(str(chain, signer).requestToBurn(amount(p)).send().getTransactionHash());
        }
        case "rd-transfer" -> {
          signer = account(p, "from");
          hashes.add(rd(chain, signer).transfer(wallet(p, "to"), amount(p)).send().getTransactionHash());
        }
        case "deposit" -> {
          signer = tokenAuthority(p.get("token"));
          hashes.add(drex.deposit(signer, p.get("token"), account(p, "client"), amount(p)).getTransactionHash());
        }
        case "withdraw" -> {
          signer = tokenAuthority(p.get("token"));
          hashes.add(drex.realTokenizado(p.get("token"), signer).moveAndBurn(wallet(p, "client"), amount(p)).send()
              .getTransactionHash());
        }
        case "rt-transfer" -> {
          signer = account(p, "from");
          hashes.add(drex.transfer(signer, p.get("token"), wallet(p, "to"), amount(p)).getTransactionHash());
        }
        case "swap" -> {
          signer = account(p, "from");
          hashes.add(drex.payOneStep(signer, p.get("fromToken"), p.get("toToken"), wallet(p, "to"), amount(p))
              .getTransactionHash());
        }
        case "freeze", "unfreeze", "forced-transfer", "pause", "unpause" -> {
          String token = p.get("token");
          signer = tokenAuthority(token);
          RealDigital t = tokenContract(chain, token, signer);
          TransactionReceipt r = switch (op) {
            case "freeze" -> t.increaseFrozenBalance(wallet(p, "wallet"), amount(p)).send();
            case "unfreeze" -> t.decreaseFrozenBalance(wallet(p, "wallet"), amount(p)).send();
            case "forced-transfer" -> t.move(wallet(p, "from"), wallet(p, "to"), amount(p)).send();
            case "pause" -> t.pause().send();
            default -> t.unpause().send();
          };
          hashes.add(r.getTransactionHash());
        }
        case "onboard-participant" -> {
          signer = bcb();
          drex.onboardParticipant(signer, account(p, "participant"), account(p, "reserve"), number(p, "cnpj8"));
        }
        case "deploy-rt" -> {
          signer = account(p, "participant");
          drex.deployRealTokenizado(bcb(), signer, account(p, "reserve"), required(p, "name"), required(p, "symbol"),
              number(p, "cnpj8"));
          deployment.save(dataDir);
        }
        case "onboard-client" -> {
          signer = tokenAuthority(p.get("token"));
          drex.onboardClient(signer, p.get("token"), account(p, "client"), number(p, "cpf"), BigInteger.ONE,
              BigInteger.ONE, BigInteger.ONE).forEach(r -> hashes.add(r.getTransactionHash()));
        }
        case "enable-wallet" -> {
          signer = account(p, "participant");
          hashes.add(RealDigitalEnableAccount.load(deployment.realDigitalEnableAccount, chain.web3j(),
              chain.managerFor(signer), chain.gasProvider()).enableAccount(wallet(p, "wallet")).send().getTransactionHash());
        }
        default -> throw new IllegalArgumentException("Unknown operation " + op);
      }
      // Balances are read from the primary node: let it catch up with the node that mined the operation,
      // so the refresh the browser makes on this event already shows its effect.
      awaitPrimary(chain.blockNumber());
      events.send("balances-changed", Map.of("operation", op));
      return new OpResult(op, true, spec.label() + " done", signer == null ? null : signer.name(), node.name(), hashes);
    } catch (Exception e) {
      return new OpResult(op, false, withNames(rootMessage(e)), signer == null ? null : signer.name(), node.name(),
          hashes);
    } finally {
      currentOperation.remove();
    }
  }

  private Account account(Map<String, String> p, String field) {
    return store.get(required(p, field));
  }

  /** An account name or a 0x address. */
  private String wallet(Map<String, String> p, String field) {
    String v = required(p, field).trim();
    return v.startsWith("0x") ? v : store.get(v).address();
  }

  private static BigInteger amount(Map<String, String> p) {
    BigInteger a = Amounts.parse(required(p, "amount"));
    if (a.signum() <= 0) {
      throw new IllegalArgumentException("The amount must be positive");
    }
    return a;
  }

  private static BigInteger number(Map<String, String> p, String field) {
    return new BigInteger(required(p, field).replaceAll("[^0-9]", ""));
  }

  private static String required(Map<String, String> p, String field) {
    String v = p.get(field);
    if (v == null || v.isBlank()) {
      throw new IllegalArgumentException("Missing " + field);
    }
    return v;
  }

  private Account bcb() {
    if (deployment.authority == null) {
      return store.get("bcb");
    }
    return store.byAddress(deployment.authority)
        .orElseThrow(() -> new IllegalStateException("The BCB wallet " + deployment.authority + " is not in accounts.json"));
  }

  /** The account that holds the roles of a token: BCB for Real Digital, the issuing bank for a Real Tokenizado. */
  private Account tokenAuthority(String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("Missing token");
    }
    if (token.equalsIgnoreCase(BalanceService.RD)) {
      return bcb();
    }
    DrexDeployment.Tokenized t = deployment.tokenized(token)
        .orElseThrow(() -> new IllegalArgumentException("No Real Tokenizado " + token));
    return store.byAddress(t.authority())
        .orElseThrow(() -> new IllegalStateException("The authority of " + token + " is not in accounts.json"));
  }

  private RealDigital tokenContract(Chain chain, String token, Account as) {
    String address = token.equalsIgnoreCase(BalanceService.RD) ? deployment.realDigital
        : deployment.tokenized(token).orElseThrow().address();
    return RealDigital.load(address, chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  private RealDigital rd(Chain chain, Account as) {
    return RealDigital.load(deployment.realDigital, chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  private STR str(Chain chain, Account as) {
    return STR.load(deployment.str, chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  // ------------------------------------------------------------------ jobs

  JobRunner.Job startDemo() {
    return jobs.start("demo", "Demo network setup", log -> {
      currentOperation.set("Demo setup");
      try {
        deployment = new DemoSetup(chain, store, dataDir, log).run();
      } finally {
        currentOperation.remove();
      }
      events.send("accounts", Map.of());
      events.send("balances-changed", Map.of("operation", "demo"));
      return null;
    });
  }

  JobRunner.Job startDeploy(String bcbName, String bcbDsa) {
    return jobs.start("deploy", "Deploy the Drex contracts", log -> {
      currentOperation.set("Network setup");
      try {
        Account bcb = store.getOrCreate(bcbName, Dsa.fromName(bcbDsa), TxFormat.FLEXIBLE);
        DrexDeployment d = DrexClient.deployNetwork(chain, bcb, log);
        d.save(dataDir);
        deployment = d;
      } finally {
        currentOperation.remove();
      }
      events.send("accounts", Map.of());
      events.send("balances-changed", Map.of("operation", "deploy"));
      return null;
    });
  }

  JobRunner.Job startFlows(List<String> dsas) {
    List<SignerProfile> profiles = dsas == null || dsas.isEmpty() ? SignerProfile.all()
        : dsas.stream().map(SignerProfile::parse).toList();
    return jobs.start("flows", "Drex flows per DSA", log -> {
      FlowMatrix m = FlowMatrix.run(rpcUrl, profiles, profiles.size(), log);
      m.write(reportDir("flows"));
      return m.renderMarkdown();
    });
  }

  JobRunner.Job startSecurity() {
    return jobs.start("security", "Security tests", log -> {
      List<SecurityResult> results = new SecuritySuite(rpcUrl, log).run();
      Path dir = reportDir("security");
      SecurityReport.write(results, rpcUrl, dir);
      log.accept(SecurityReport.summary(results));
      return Files.readString(dir.resolve("security.md"));
    });
  }

  JobRunner.Job startBench(List<String> dsas, List<Double> rates, int seconds, int clients, List<String> nodes) {
    List<String> endpoints = nodes == null || nodes.isEmpty() ? List.of(rpcUrl) : nodes;
    List<String> profiles = dsas == null || dsas.isEmpty() ? List.of("SECP256K1:std", "SECP256K1-MLDSA44") : dsas;
    return jobs.start("bench", "Load test", log -> {
      Path dir = reportDir("bench");
      for (String dsa : profiles) {
        LoadTest test = new LoadTest(new LoadTest.Config(endpoints, SignerProfile.parse(dsa), clients, rates, seconds,
            0.3, 30, 16), log);
        BenchReport.append(dir, test.run());
      }
      return Files.readString(dir.resolve("bench.md"));
    });
  }

  private Path reportDir(String kind) {
    return reportsDir.resolve("gui-" + kind + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
  }

  private static final java.util.regex.Pattern ADDRESS = java.util.regex.Pattern.compile("0x[0-9a-fA-F]{40}(?![0-9a-fA-F])");

  /** {@code message} with the addresses of known accounts followed by their names. */
  String withNames(String message) {
    return ADDRESS.matcher(message).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
        store.byAddress(m.group()).map(a -> m.group() + " (" + a.name() + ")").orElse(m.group())));
  }

  private static final java.util.regex.Pattern REVERT =
      java.util.regex.Pattern.compile("[Ee]xecution reverted:? ?\\(?(.*?)\\)?$");

  /** The cause's message, with contract reverts reduced to their reason. */
  static String rootMessage(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    java.util.regex.Matcher revert = REVERT.matcher(m);
    if (revert.find()) {
      String reason = revert.group(1).trim();
      return "Refused by the contract" + (reason.isEmpty() ? "" : ": " + reason);
    }
    return m.replaceFirst("^JsonRpcError thrown with code -?\\d+\\. Message: ", "");
  }

  @Override
  public void close() {
    blocks.shutdownNow();
    txLog.close();
    jobs.close();
    chains.values().forEach(Chain::close);
  }
}

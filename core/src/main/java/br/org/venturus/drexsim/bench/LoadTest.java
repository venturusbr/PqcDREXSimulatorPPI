package br.org.venturus.drexsim.bench;

import br.org.venturus.drexsim.chain.Chain;
import br.org.venturus.drexsim.chain.RpcBatch;
import br.org.venturus.drexsim.contracts.RealTokenizado;
import br.org.venturus.drexsim.drex.DrexClient;
import br.org.venturus.drexsim.drex.DrexDeployment;
import br.org.venturus.drexsim.keystore.Account;
import br.org.venturus.drexsim.scenario.SignerProfile;
import br.org.venturus.drexsim.tx.SignedTransaction;
import br.org.venturus.drexsim.tx.TransactionSigner;
import br.org.venturus.drexsim.tx.UnsignedTransaction;
import java.io.IOException;
import java.math.BigInteger;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

/**
 * Load test with a realistic Drex workload: clients of two banks paying each other, part inside
 * their bank (Real Tokenizado transfer) and part across banks (SwapOneStep, which burns, moves Real
 * Digital between reserves and mints). Every actor signs with one {@link SignerProfile}.
 *
 * <p>After the setup (deployment, onboarding and funding of the clients), each level submits
 * transactions at a fixed rate for a fixed time, round-robin over the clients, each client always
 * through the same node (clients are spread over the nodes), and
 * measures inclusion latency, throughput, block fill and node resources.
 */
public final class LoadTest {

  /**
   * @param endpoints JSON-RPC URLs; clients are spread over them, each client using one
   * @param clientsPerBank senders per bank; each keeps its own nonce sequence
   * @param swapShare fraction of operations that are inter-bank swaps (the rest are same-bank transfers)
   * @param drainSeconds time to wait after a level for its transactions to be included
   */
  public record Config(List<String> endpoints, SignerProfile profile, int clientsPerBank, List<Double> rates,
      int secondsPerLevel, double swapShare, int drainSeconds, int submitThreads) {

    /** Bounds checked here and mirrored by the GUI's load test form. */
    public static final int MIN_CLIENTS = 2;
    public static final int MAX_CLIENTS = 500;
    public static final double MAX_RATE = 10_000;
    public static final int MIN_SECONDS = 5;
    public static final int MAX_SECONDS = 3_600;

    /**
     * Rejects a configuration that would fail only after the setup or measure nothing: a level that
     * sends no transaction, a single client per bank (it would pay itself), malformed node URLs.
     */
    public Config {
      if (endpoints == null || endpoints.isEmpty()) {
        throw new IllegalArgumentException("No node to send the load to");
      }
      for (String url : endpoints) {
        checkUrl(url);
      }
      if (profile == null) {
        throw new IllegalArgumentException("No signer DSA");
      }
      if (clientsPerBank < MIN_CLIENTS || clientsPerBank > MAX_CLIENTS) {
        throw new IllegalArgumentException(
            "Clients per bank must be from " + MIN_CLIENTS + " to " + MAX_CLIENTS + ", not " + clientsPerBank);
      }
      if (secondsPerLevel < MIN_SECONDS || secondsPerLevel > MAX_SECONDS) {
        throw new IllegalArgumentException(
            "Seconds per rate must be from " + MIN_SECONDS + " to " + MAX_SECONDS + ", not " + secondsPerLevel);
      }
      if (rates == null || rates.isEmpty()) {
        throw new IllegalArgumentException("No rate to test");
      }
      for (Double rate : rates) {
        if (rate == null || !(rate > 0) || rate > MAX_RATE) {
          throw new IllegalArgumentException("Each rate must be above 0 and up to " + (long) MAX_RATE + " TPS, not " + rate);
        }
        if (Math.round(rate * secondsPerLevel) < 1) {
          throw new IllegalArgumentException(
              "At " + rate + " TPS, " + secondsPerLevel + " seconds send no transaction; raise the rate or the seconds");
        }
      }
      if (!(swapShare >= 0 && swapShare <= 1)) {
        throw new IllegalArgumentException("The swap share must be from 0 to 1, not " + swapShare);
      }
      if (drainSeconds < 0) {
        throw new IllegalArgumentException("The drain time cannot be negative");
      }
      if (submitThreads < 1) {
        throw new IllegalArgumentException("At least one submitting thread is needed");
      }
      endpoints = List.copyOf(endpoints);
      rates = List.copyOf(rates);
    }

    private static void checkUrl(String url) {
      try {
        java.net.URI uri = new java.net.URI(url == null ? "" : url.trim());
        String scheme = uri.getScheme();
        if (uri.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
          return;
        }
      } catch (java.net.URISyntaxException e) {
        // reported below
      }
      throw new IllegalArgumentException("Not an http:// or https:// node URL: " + url);
    }
  }

  private static final BigInteger CNPJ_A = BigInteger.valueOf(11111111);
  private static final BigInteger CNPJ_B = BigInteger.valueOf(22222222);
  private static final BigInteger CLIENT_FUNDS = BigInteger.valueOf(1_000_000_00L);
  private static final BigInteger ONE_CENT = BigInteger.ONE;

  private final Config config;
  private final Consumer<String> log;

  private Chain chain;
  private DrexDeployment d;
  private final List<Account> clientsA = new ArrayList<>();
  private final List<Account> clientsB = new ArrayList<>();
  private final Map<String, AtomicLong> nonces = new ConcurrentHashMap<>();
  private BigInteger transferGas;
  private BigInteger swapGas;
  private int validators;

  public LoadTest(Config config, Consumer<String> log) {
    this.config = config;
    this.log = log == null ? s -> {} : log;
  }

  public List<LevelResult> run() throws Exception {
    List<LevelResult> results = new ArrayList<>();
    try (Chain c = new Chain(config.endpoints().get(0))) {
      chain = c;
      validators = validatorCount();
      waitForQuiet(); // a previous run's backlog would distort this one
      setup();
      // One OkHttp client for every node, evicted at the end so its connections do not stay open. Every
      // submitting thread sends to every node, so it keeps a connection idle per thread and node: with
      // OkHttp's default of 5, the others would be opened and closed per transaction, and at a few
      // hundred transactions per second exhaust the local ports.
      OkHttpClient client = HttpService.getOkHttpClientBuilder()
          .connectionPool(new ConnectionPool(config.submitThreads() * config.endpoints().size() + 4, 5, TimeUnit.MINUTES))
          .build();
      List<Web3j> nodes = config.endpoints().stream().map(u -> Web3j.build(new HttpService(u, client))).toList();
      try {
        for (double rate : config.rates()) {
          LevelResult r = level(nodes, rate);
          results.add(r);
          log.accept(String.format(java.util.Locale.ROOT,
              "[%s] %4.0f TPS target: offered %.1f, included %.1f TPS, p50 %d ms, p95 %d ms, %.1f tx/block, fill %.0f%%, backlog %d, rejected %d%s",
              config.profile().label(), rate, r.offeredTps(), r.achievedTps(), r.latencyP50Ms(), r.latencyP95Ms(),
              r.avgTxPerBlock(), r.avgBlockFillPct(), r.pending(), r.rejected(), r.sustained() ? "" : "  <- saturated"));
          waitForQuiet();
        }
      } finally {
        nodes.forEach(Web3j::shutdown);
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
      }
    }
    return results;
  }

  // ------------------------------------------------------------------ setup

  private void setup() throws Exception {
    SignerProfile p = config.profile();
    log.accept("[" + p.label() + "] setup: deploying and onboarding " + 2 * config.clientsPerBank() + " clients");
    Account bcb = Account.generate("bcb", p.dsa(), p.format());
    Account bankA = Account.generate("bank-a", p.dsa(), p.format());
    Account reserveA = Account.generate("reserve-a", p.dsa(), p.format());
    Account bankB = Account.generate("bank-b", p.dsa(), p.format());
    Account reserveB = Account.generate("reserve-b", p.dsa(), p.format());
    d = DrexClient.deployNetwork(chain, bcb, null);
    DrexClient drex = new DrexClient(chain, d, null);
    drex.onboardParticipant(bcb, bankA, reserveA, CNPJ_A);
    drex.onboardParticipant(bcb, bankB, reserveB, CNPJ_B);
    drex.deployRealTokenizado(bcb, bankA, reserveA, "Banco A", "BRLA", CNPJ_A);
    drex.deployRealTokenizado(bcb, bankB, reserveB, "Banco B", "BRLB", CNPJ_B);
    // Reserves back every swap; far more than any level moves at one cent per swap.
    drex.strMint(reserveA, BigInteger.valueOf(1_000_000_000_00L));
    drex.strMint(reserveB, BigInteger.valueOf(1_000_000_000_00L));

    for (int i = 0; i < config.clientsPerBank(); i++) {
      clientsA.add(Account.generate("a" + i, p.dsa(), p.format()));
      clientsB.add(Account.generate("b" + i, p.dsa(), p.format()));
    }
    // Batches of independent transactions, sent concurrently; each batch waits for the previous one.
    batch(clientsA, (c) -> rt("BRLA", bankA).enableAccount(c.address()).sendAsync());
    batch(clientsB, (c) -> rt("BRLB", bankB).enableAccount(c.address()).sendAsync());
    batch(clientsA, (c) -> rt("BRLA", bankA).mint(c.address(), CLIENT_FUNDS).sendAsync());
    batch(clientsB, (c) -> rt("BRLB", bankB).mint(c.address(), CLIENT_FUNDS).sendAsync());
    List<Account> all = new ArrayList<>(clientsA);
    all.addAll(clientsB);
    batch(all, (c) -> rt(clientsA.contains(c) ? "BRLA" : "BRLB", c)
        .approve(d.swapOneStep, CLIENT_FUNDS).sendAsync());

    List<Long> pending = pendingNonces(all);
    for (int i = 0; i < all.size(); i++) {
      nonces.put(all.get(i).address(), new AtomicLong(pending.get(i)));
    }
    // Fixed gas per operation, so the load phase sends without estimating (estimates would race the load).
    transferGas = gasFor(clientsA.get(0), tokenOf("BRLA"), transferData(clientsA.get(1 % clientsA.size()).address()));
    swapGas = gasFor(clientsA.get(0), d.swapOneStep, swapData("BRLA", "BRLB", clientsB.get(0).address()));
    log.accept("[" + p.label() + "] setup done: gas limit " + transferGas + " per transfer, " + swapGas + " per swap");
  }

  /**
   * Sends one transaction per client, 20 at a time, retrying a failed one up to 3 times (a node
   * busy with a previous load can fail an estimate with an internal error).
   */
  private static void batch(List<Account> clients, Function<Account, CompletableFuture<TransactionReceipt>> call)
      throws InterruptedException {
    for (int from = 0; from < clients.size(); from += 20) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("Load test stopped");
      }
      List<Account> chunk = clients.subList(from, Math.min(from + 20, clients.size()));
      List<Account> todo = new ArrayList<>(chunk);
      for (int attempt = 1; !todo.isEmpty(); attempt++) {
        List<CompletableFuture<TransactionReceipt>> futures = todo.stream().map(call).toList();
        List<Account> failed = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
          try {
            futures.get(i).join();
          } catch (Exception e) {
            if (attempt >= 3) {
              throw e;
            }
            failed.add(todo.get(i));
          }
        }
        todo = failed;
        if (!todo.isEmpty()) {
          Thread.sleep(2000);
        }
      }
    }
  }

  private BigInteger gasFor(Account from, String to, String data) throws Exception {
    BigInteger estimate = chain.web3j()
        .ethEstimateGas(new Transaction(from.address(), null, null, null, to, BigInteger.ZERO, data)).send().getAmountUsed();
    long shortfall = from.format() == br.org.venturus.drexsim.tx.TxFormat.FLEXIBLE ? from.dsa().estimateGasShortfall() : 0;
    return estimate.add(BigInteger.valueOf(shortfall)).multiply(BigInteger.valueOf(13)).divide(BigInteger.TEN);
  }

  // ------------------------------------------------------------------ load level

  private record Pending(String hash, long submittedMillis, int size, String sender, long nonce, int node) {}

  private LevelResult level(List<Web3j> nodes, double rate) throws Exception {
    BlockWatcher watcher = new BlockWatcher(chain.web3j());
    Thread watcherThread = new Thread(watcher, "block-watcher");
    watcherThread.setDaemon(true);
    watcherThread.start();
    NodeStatsSampler stats = new NodeStatsSampler();
    Thread statsThread = new Thread(stats, "docker-stats");
    statsThread.setDaemon(true);
    statsThread.start();
    ExecutorService workers = Executors.newFixedThreadPool(config.submitThreads());
    try {
      return measure(nodes, rate, watcher, watcherThread, stats, workers);
    } finally {
      // Also when the run is stopped or fails: no thread of the level keeps sending or polling.
      workers.shutdownNow();
      watcher.stop();
      stats.stop();
    }
  }

  private LevelResult measure(List<Web3j> nodes, double rate, BlockWatcher watcher, Thread watcherThread,
      NodeStatsSampler stats, ExecutorService workers) throws Exception {
    List<Pending> accepted = Collections.synchronizedList(new ArrayList<>());
    Map<String, AtomicLong> rejections = new ConcurrentHashMap<>();
    AtomicLong signNanos = new AtomicLong();
    Random random = new Random(42);
    List<Account> all = new ArrayList<>(clientsA);
    all.addAll(clientsB);

    long total = Math.round(rate * config.secondsPerLevel());
    long start = System.nanoTime();
    long startMillis = System.currentTimeMillis();
    for (long i = 0; i < total; i++) {
      long due = start + (long) (i * 1e9 / rate);
      long wait = due - System.nanoTime();
      if (wait > 0) {
        LockSupport.parkNanos(wait);
      }
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("Load test stopped");
      }
      int senderIndex = (int) (i % all.size());
      Account sender = all.get(senderIndex);
      boolean swap = random.nextDouble() < config.swapShare();
      boolean fromA = clientsA.contains(sender);
      List<Account> sameBank = fromA ? clientsA : clientsB;
      List<Account> otherBank = fromA ? clientsB : clientsA;
      String to;
      String data;
      BigInteger gas;
      if (swap) {
        to = d.swapOneStep;
        data = swapData(fromA ? "BRLA" : "BRLB", fromA ? "BRLB" : "BRLA", otherBank.get(random.nextInt(otherBank.size())).address());
        gas = swapGas;
      } else {
        to = tokenOf(fromA ? "BRLA" : "BRLB");
        data = transferData(sameBank.get(random.nextInt(sameBank.size())).address());
        gas = transferGas;
      }
      long nonce = nonces.get(sender.address()).getAndIncrement();
      // Each client always talks to the same node, as a bank's wallet would talk to its own node.
      Web3j node = nodes.get(senderIndex % nodes.size());
      workers.submit(() -> {
        try {
          UnsignedTransaction tx = new UnsignedTransaction(chain.chainId(), BigInteger.valueOf(nonce), BigInteger.ZERO,
              BigInteger.ZERO, gas, to, BigInteger.ZERO, Numeric.hexStringToByteArray(data));
          long t0 = System.nanoTime();
          SignedTransaction signed = TransactionSigner.sign(tx, sender);
          signNanos.addAndGet(System.nanoTime() - t0);
          long submitted = System.currentTimeMillis();
          EthSendTransaction r = node.ethSendRawTransaction(signed.rawHex()).send();
          if (r.hasError()) {
            rejections.computeIfAbsent(r.getError().getMessage(), k -> new AtomicLong()).incrementAndGet();
          } else {
            accepted.add(new Pending(r.getTransactionHash(), submitted, signed.size(), sender.address(), nonce,
                senderIndex % nodes.size()));
          }
        } catch (Exception e) {
          rejections.computeIfAbsent(String.valueOf(e.getMessage()), k -> new AtomicLong()).incrementAndGet();
        }
      });
    }
    workers.shutdown();
    workers.awaitTermination(5, TimeUnit.MINUTES);
    long submitEndMillis = System.currentTimeMillis();
    double offered = total / ((System.nanoTime() - start) / 1e9);

    // Wait for the backlog to drain, up to drainSeconds.
    long deadline = System.currentTimeMillis() + config.drainSeconds() * 1000L;
    List<Pending> snapshot;
    synchronized (accepted) {
      snapshot = new ArrayList<>(accepted);
    }
    while (System.currentTimeMillis() < deadline && snapshot.stream().anyMatch(t -> watcher.includedAt(t.hash()) == null)) {
      Thread.sleep(250);
    }
    watcher.stop();
    stats.stop();
    watcherThread.join(2000);
    reconcileWithReceipts(snapshot, watcher);

    List<Long> latencies = new ArrayList<>();
    long lastInclusion = startMillis;
    long firstInclusion = Long.MAX_VALUE;
    for (Pending t : snapshot) {
      Long at = watcher.includedAt(t.hash());
      if (at != null) {
        latencies.add(at - t.submittedMillis());
        lastInclusion = Math.max(lastInclusion, at);
        firstInclusion = Math.min(firstInclusion, at);
      }
    }
    Collections.sort(latencies);
    long included = latencies.size();
    logStuck(snapshot, watcher, nodes);

    List<BlockWatcher.BlockStat> blocks = watcher.blocksBetween(startMillis, lastInclusion);
    double avgTx = blocks.stream().mapToInt(BlockWatcher.BlockStat::txCount).average().orElse(0);
    int maxTx = blocks.stream().mapToInt(BlockWatcher.BlockStat::txCount).max().orElse(0);
    double fill = blocks.stream().mapToDouble(b -> 100.0 * b.gasUsed() / b.gasLimit()).average().orElse(0);
    double blockKb = blocks.stream().mapToLong(BlockWatcher.BlockStat::sizeBytes).average().orElse(0) / 1024.0;
    long txsInBlocks = blocks.stream().mapToLong(BlockWatcher.BlockStat::txCount).sum();
    double gasPerTx = txsInBlocks == 0 ? 0 : (double) blocks.stream().mapToLong(BlockWatcher.BlockStat::gasUsed).sum() / txsInBlocks;
    double interval = blocks.size() < 2 ? 0
        : (blocks.get(blocks.size() - 1).observedMillis() - blocks.get(0).observedMillis()) / 1000.0 / (blocks.size() - 1);
    long gasLimit = blocks.isEmpty() ? 0 : blocks.get(0).gasLimit();
    double ceiling = gasPerTx == 0 || interval == 0 ? 0 : gasLimit / gasPerTx / interval;
    // Inclusion rate: the level's transactions over the time from the first block that included one of
    // them to the last, but no less than the time they took to be sent. Blocks count time in whole
    // intervals: a level sent for 10 s over 2 s blocks lands in 5 or 6 blocks, 8 or 10 s apart, so the
    // inclusion span alone would make it look faster than it was sent, or slower by a whole block.
    double sendSeconds = (submitEndMillis - startMillis) / 1000.0;
    double span = Math.max(sendSeconds, (lastInclusion - firstInclusion) / 1000.0);
    double achieved = included == 0 || span <= 0 ? 0 : included / span;

    // Receipt status of a sample of included transactions.
    List<Pending> includedTxs = snapshot.stream().filter(t -> watcher.includedAt(t.hash()) != null).collect(Collectors.toList());
    Collections.shuffle(includedTxs, new Random(7));
    List<Pending> sample = includedTxs.subList(0, Math.min(200, includedTxs.size()));
    int failed = 0;
    for (Optional<TransactionReceipt> r : receipts(sample)) {
      if (r.isEmpty() || !r.get().isStatusOK()) {
        failed++;
      }
    }

    // Senders whose submissions were rejected have nonce gaps: realign them with the node.
    if (!rejections.isEmpty()) {
      List<Long> pending = pendingNonces(all);
      for (int i = 0; i < all.size(); i++) {
        nonces.get(all.get(i).address()).set(pending.get(i));
      }
    }

    long rejected = rejections.values().stream().mapToLong(AtomicLong::get).sum();
    String topRejection = rejections.entrySet().stream().max(Map.Entry.comparingByValue((a, b) -> Long.compare(a.get(), b.get())))
        .map(e -> e.getKey() + " (" + e.getValue().get() + ")").orElse("");
    return new LevelResult(config.profile().label(), validators, gasLimit, rate, config.secondsPerLevel(), total, rejected, included,
        snapshot.size() - included, offered, achieved, pct(latencies, 50), pct(latencies, 95), pct(latencies, 99),
        latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1), blocks.size(), avgTx, maxTx, fill, blockKb, gasPerTx, interval, ceiling,
        snapshot.stream().mapToInt(Pending::size).average().orElse(0), total == 0 ? 0 : signNanos.get() / 1e6 / total,
        sample.size(), failed, stats.avgCpu(), stats.maxMemMb(), topRejection);
  }

  /**
   * Transactions the watcher did not see in a block are checked against their receipts: they were
   * included after the drain window ended, at the time their block was observed or, if the watcher
   * had stopped by then, no earlier than now (a lower bound of their latency).
   */
  private void reconcileWithReceipts(List<Pending> snapshot, BlockWatcher watcher) throws Exception {
    int found = 0;
    List<Pending> unseen = snapshot.stream().filter(t -> watcher.includedAt(t.hash()) == null).toList();
    List<Optional<TransactionReceipt>> receipts = receipts(unseen);
    for (int i = 0; i < unseen.size(); i++) {
      if (receipts.get(i).isPresent()) {
        Long observed = watcher.observedAt(receipts.get(i).get().getBlockNumber().longValueExact());
        watcher.recordInclusion(unseen.get(i).hash(), observed != null ? observed : System.currentTimeMillis());
        found++;
      }
    }
    if (found > 0) {
      log.accept("   " + found + " transactions were included after the drain window; their latency is a lower bound");
    }
  }

  /** Logs a few transactions that were accepted but never included, with the sender's nonce on chain. */
  private void logStuck(List<Pending> snapshot, BlockWatcher watcher, List<Web3j> nodes) throws Exception {
    List<Pending> stuck = snapshot.stream().filter(t -> watcher.includedAt(t.hash()) == null).limit(5).toList();
    for (Pending t : stuck) {
      long latest = chain.web3j().ethGetTransactionCount(t.sender(), DefaultBlockParameterName.LATEST).send()
          .getTransactionCount().longValueExact();
      StringBuilder seen = new StringBuilder();
      for (int n = 0; n < nodes.size(); n++) {
        boolean known = nodes.get(n).ethGetTransactionByHash(t.hash()).send().getTransaction().isPresent();
        seen.append(known ? "Y" : "-");
      }
      log.accept(String.format("   not included: %s sender %s nonce %d (chain nonce %d), sent to node %d, known by nodes [%s]",
          t.hash(), t.sender(), t.nonce(), latest, t.node() + 1, seen));
    }
  }

  /**
   * Waits until two consecutive new blocks are empty, which on a network carrying only this load
   * means the backlog of the previous level has been processed, so levels do not overlap.
   * Gives up after 5 minutes.
   */
  private void waitForQuiet() throws Exception {
    long deadline = System.currentTimeMillis() + 300_000;
    long seen = chain.blockNumber();
    int emptyInARow = 0;
    while (emptyInARow < 2 && System.currentTimeMillis() < deadline) {
      long head = chain.blockNumber();
      while (seen < head && emptyInARow < 2) {
        seen++;
        int txs = blockTxCount(seen);
        emptyInARow = txs == 0 ? emptyInARow + 1 : 0;
      }
      Thread.sleep(500);
    }
  }

  /**
   * Transactions in block {@code number}. A busy node can answer with an error or no result instead of
   * the count: the call is retried, and the node's own message reported if it keeps failing.
   */
  private int blockTxCount(long number) throws IOException, InterruptedException {
    String problem = null;
    for (int attempt = 1; attempt <= 5; attempt++) {
      try {
        org.web3j.protocol.core.methods.response.EthGetBlockTransactionCountByNumber r = chain.web3j()
            .ethGetBlockTransactionCountByNumber(DefaultBlockParameter.valueOf(BigInteger.valueOf(number))).send();
        if (r.hasError()) {
          problem = r.getError().getMessage();
        } else if (r.getResult() == null) {
          problem = "the node returned no result";
        } else {
          return r.getTransactionCount().intValueExact();
        }
      } catch (IOException e) {
        problem = e.getMessage();
      }
      Thread.sleep(500L * attempt);
    }
    throw new IOException("Could not read the transaction count of block " + number + " after 5 attempts: " + problem);
  }

  // ------------------------------------------------------------------ batched reads

  /** Pending-block nonce of each account, read in JSON-RPC batches. */
  private List<Long> pendingNonces(List<Account> accounts) throws IOException {
    List<Request<?, EthGetTransactionCount>> requests = new ArrayList<>();
    for (Account a : accounts) {
      requests.add(chain.web3j().ethGetTransactionCount(a.address(), DefaultBlockParameterName.PENDING));
    }
    List<Long> result = new ArrayList<>();
    for (EthGetTransactionCount r : RpcBatch.send(chain.web3j(), requests)) {
      if (r.hasError()) {
        throw new IOException("eth_getTransactionCount: " + r.getError().getMessage());
      }
      result.add(r.getTransactionCount().longValueExact());
    }
    return result;
  }

  /** Receipt of each transaction (empty while not included), read in JSON-RPC batches. */
  private List<Optional<TransactionReceipt>> receipts(List<Pending> txs) throws IOException {
    List<Request<?, EthGetTransactionReceipt>> requests = new ArrayList<>();
    for (Pending t : txs) {
      requests.add(chain.web3j().ethGetTransactionReceipt(t.hash()));
    }
    List<Optional<TransactionReceipt>> result = new ArrayList<>();
    for (EthGetTransactionReceipt r : RpcBatch.send(chain.web3j(), requests)) {
      if (r.hasError()) {
        throw new IOException("eth_getTransactionReceipt: " + r.getError().getMessage());
      }
      result.add(r.getTransactionReceipt());
    }
    return result;
  }

  private static long pct(List<Long> sorted, int p) {
    if (sorted.isEmpty()) {
      return 0;
    }
    int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
  }

  // ------------------------------------------------------------------ helpers

  /** {@code qbft_getValidatorsByBlockNumber}, or 0 when the QBFT API is not enabled. */
  private int validatorCount() {
    try {
      Response<?> r = new Request<>("qbft_getValidatorsByBlockNumber", List.of("latest"),
          chain.web3jService(), ValidatorsResponse.class).send();
      return r.getResult() instanceof List<?> l ? l.size() : 0;
    } catch (Exception e) {
      return 0;
    }
  }

  /** {@code qbft_getValidatorsByBlockNumber} response. */
  public static final class ValidatorsResponse extends Response<List<String>> {}

  private RealTokenizado rt(String symbol, Account as) {
    return RealTokenizado.load(tokenOf(symbol), chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  private String tokenOf(String symbol) {
    return d.tokenized(symbol).orElseThrow().address();
  }

  private static String transferData(String to) {
    return FunctionEncoder.encode(new org.web3j.abi.datatypes.Function("transfer",
        Arrays.asList(new Address(to), new Uint256(ONE_CENT)), List.of()));
  }

  private String swapData(String fromSymbol, String toSymbol, String receiver) {
    return FunctionEncoder.encode(new org.web3j.abi.datatypes.Function("executeSwap",
        Arrays.asList(new Address(tokenOf(fromSymbol)), new Address(tokenOf(toSymbol)), new Address(receiver),
            new Uint256(ONE_CENT)), List.of()));
  }
}

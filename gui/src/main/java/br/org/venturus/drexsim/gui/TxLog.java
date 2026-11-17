package br.org.venturus.drexsim.gui;

import br.org.venturus.drexsim.chain.SentTransaction;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

/**
 * The transactions sent from the GUI, newest first, with their receipts. A transaction is logged
 * when it is submitted and updated when its receipt arrives; each change is pushed to the browser.
 */
final class TxLog implements AutoCloseable {

  /** One logged transaction. Mutable fields change once, when the receipt arrives. */
  static final class Entry {
    public final long id;
    public final Instant time;
    public final String operation;
    public final String node;
    public final String account;
    public final String from;
    public final String to;
    public final String dsa;
    public final String type;
    public final long nonce;
    public final int size;
    public final int signatureBytes;
    public final int publicKeyBytes;
    public final long gasLimit;
    public final double signMs;
    public final String hash;
    public volatile String status;
    public volatile Long gasUsed;
    public volatile Long block;
    public volatile String error;

    Entry(long id, String operation, String node, SentTransaction tx) {
      this.id = id;
      this.time = Instant.now();
      this.operation = operation;
      this.node = node;
      this.account = tx.account();
      this.from = tx.from();
      this.to = tx.to();
      this.dsa = tx.dsa().wireName();
      this.type = tx.format().name().equals("FLEXIBLE") ? "0x42" : "0x02";
      this.nonce = tx.nonce().longValue();
      this.size = tx.size();
      this.signatureBytes = tx.signatureLength();
      this.publicKeyBytes = tx.publicKeyLength();
      this.gasLimit = tx.gasLimit().longValue();
      this.signMs = tx.signNanos() / 1e6;
      this.hash = tx.hash();
      this.status = tx.accepted() ? "pending" : "rejected";
      this.error = tx.error();
    }
  }

  private static final int CAPACITY = 500;

  private final Web3j web3j;
  private final EventHub events;
  private final Deque<Entry> entries = new ArrayDeque<>();
  private final AtomicLong ids = new AtomicLong();
  private final ScheduledExecutorService receipts = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "receipt-poller");
    t.setDaemon(true);
    return t;
  });

  TxLog(Web3j web3j, EventHub events) {
    this.web3j = web3j;
    this.events = events;
    receipts.scheduleWithFixedDelay(this::pollReceipts, 500, 500, TimeUnit.MILLISECONDS);
  }

  void add(String operation, String node, SentTransaction tx) {
    Entry e = new Entry(ids.incrementAndGet(), operation, node, tx);
    synchronized (entries) {
      entries.addFirst(e);
      while (entries.size() > CAPACITY) {
        entries.removeLast();
      }
    }
    events.send("tx", e);
  }

  List<Entry> recent(int limit) {
    synchronized (entries) {
      return entries.stream().limit(limit).toList();
    }
  }

  /** The newest logged entry for a transaction hash, if it was sent from here. */
  java.util.Optional<Entry> find(String hash) {
    synchronized (entries) {
      return entries.stream().filter(e -> e.hash != null && e.hash.equalsIgnoreCase(hash)).findFirst();
    }
  }

  private void pollReceipts() {
    List<Entry> pending;
    synchronized (entries) {
      pending = new ArrayList<>(entries.stream().filter(e -> "pending".equals(e.status)).toList());
    }
    for (Entry e : pending) {
      try {
        TransactionReceipt r = web3j.ethGetTransactionReceipt(e.hash).send().getTransactionReceipt().orElse(null);
        if (r != null) {
          e.gasUsed = r.getGasUsed().longValue();
          e.block = r.getBlockNumber().longValue();
          e.status = r.isStatusOK() ? "success" : "reverted";
          events.send("tx", e);
        }
      } catch (Exception ignored) {
        // try again on the next poll
      }
    }
  }

  @Override
  public void close() {
    receipts.shutdownNow();
  }
}

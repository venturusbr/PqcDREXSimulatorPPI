package br.org.venturus.drexsim.bench;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.EthBlock;

/**
 * Polls new blocks every 100 ms and records when each watched transaction was first seen in a
 * block, plus per-block fill. The observation time, not the block timestamp (1 s resolution), is
 * used for latency.
 */
final class BlockWatcher implements Runnable {

  record BlockStat(long number, long observedMillis, int txCount, long gasUsed, long gasLimit, long sizeBytes) {}

  private final Web3j web3j;
  private final Map<String, Long> inclusionMillis = new ConcurrentHashMap<>();
  private final List<BlockStat> blocks = new CopyOnWriteArrayList<>();
  private final Map<Long, Long> blockOfObservation = new ConcurrentHashMap<>();
  private volatile boolean running = true;
  private long last;

  BlockWatcher(Web3j web3j) throws Exception {
    this.web3j = web3j;
    this.last = web3j.ethBlockNumber().send().getBlockNumber().longValueExact();
  }

  @Override
  public void run() {
    while (running) {
      try {
        long head = web3j.ethBlockNumber().send().getBlockNumber().longValueExact();
        while (last < head) {
          long next = last + 1;
          EthBlock.Block b = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(BigInteger.valueOf(next)), false)
              .send().getBlock();
          if (b == null) {
            break; // not served yet by this node: retry the same block on the next poll
          }
          long now = System.currentTimeMillis();
          for (EthBlock.TransactionResult<?> tx : b.getTransactions()) {
            inclusionMillis.putIfAbsent(((String) tx.get()).toLowerCase(), now);
          }
          blockOfObservation.put(next, now);
          blocks.add(new BlockStat(next, now, b.getTransactions().size(), b.getGasUsed().longValueExact(),
              b.getGasLimit().longValueExact(), b.getSize() == null ? 0 : b.getSize().longValueExact()));
          last = next; // only once the block is fully recorded, so a failed fetch is retried, not skipped
        }
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        // A missed poll only delays observations; try again.
      }
    }
  }

  Long includedAt(String hash) {
    return inclusionMillis.get(hash.toLowerCase());
  }

  /** When block {@code number} was observed, or null if it was not. */
  Long observedAt(long number) {
    return blockOfObservation.get(number);
  }

  /** Records an inclusion found afterwards (from a receipt) for a block observed at {@code millis}. */
  void recordInclusion(String hash, long millis) {
    inclusionMillis.putIfAbsent(hash.toLowerCase(), millis);
  }

  List<BlockStat> blocksBetween(long fromMillis, long toMillis) {
    return blocks.stream().filter(b -> b.observedMillis() >= fromMillis && b.observedMillis() <= toMillis).toList();
  }

  void stop() {
    running = false;
  }
}

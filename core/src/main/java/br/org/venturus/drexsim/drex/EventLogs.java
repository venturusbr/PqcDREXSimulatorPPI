package br.org.venturus.drexsim.drex;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;

/**
 * The logs of one event of one contract, from its deployment if known, else from block 0. Nodes limit how many
 * blocks one {@code eth_getLogs} may cover (Besu: 5000 by default), so the chain is read in ranges, halved while
 * the node refuses them. What was read is kept, per chain (its genesis block) and contract, so later reads only
 * cover the new blocks.
 */
final class EventLogs {

  private static final long FIRST_RANGE = 5000;
  private static final Map<String, EventLogs> READ = new ConcurrentHashMap<>();

  private final List<Log> logs = new ArrayList<>();
  private long next;
  private long range = FIRST_RANGE;

  private EventLogs(long fromBlock) {
    this.next = Math.max(0, fromBlock);
  }

  /**
   * Every log of the event with signature hash {@code topic} emitted by {@code address}, oldest first. {@code
   * fromBlock} is a block the contract did not exist before, or 0 when that is not known.
   */
  static List<Log> of(Web3j web3j, String address, long fromBlock, String topic) throws IOException {
    return READ.computeIfAbsent(genesis(web3j) + "/" + address.toLowerCase() + "/" + topic, k -> new EventLogs(fromBlock))
        .read(web3j, address, topic);
  }

  /**
   * The hash of the chain's genesis block. Contract addresses repeat on a new network deployed by the same
   * accounts, so what is kept about a contract is kept per chain too.
   */
  static String genesis(Web3j web3j) throws IOException {
    return web3j.ethGetBlockByNumber(DefaultBlockParameterName.EARLIEST, false).send().getBlock().getHash();
  }

  private synchronized List<Log> read(Web3j web3j, String address, String topic) throws IOException {
    long latest = web3j.ethBlockNumber().send().getBlockNumber().longValueExact();
    while (next <= latest) {
      long to = Math.min(latest, next + range - 1);
      EthFilter filter = new EthFilter(DefaultBlockParameter.valueOf(BigInteger.valueOf(next)),
          DefaultBlockParameter.valueOf(BigInteger.valueOf(to)), address).addSingleTopic(topic);
      EthLog result = web3j.ethGetLogs(filter).send();
      if (result.hasError()) {
        if (range > 1 && result.getError().getMessage().toLowerCase().contains("range")) {
          range = Math.max(1, range / 2);
          continue;
        }
        throw new IOException("eth_getLogs failed: " + result.getError().getMessage());
      }
      for (EthLog.LogResult<?> r : result.getLogs()) {
        logs.add((Log) r.get());
      }
      next = to + 1;
    }
    return List.copyOf(logs);
  }
}

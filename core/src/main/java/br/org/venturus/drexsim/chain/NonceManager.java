package br.org.venturus.drexsim.chain;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;

/**
 * Hands out consecutive nonces per sender, so one account can have several transactions in flight.
 * The first nonce of an account comes from {@code eth_getTransactionCount(pending)}; after a
 * rejected submission, {@link #resync} reloads it from the node.
 */
public final class NonceManager {

  private final Web3j web3j;
  private final Map<String, AtomicLong> next = new ConcurrentHashMap<>();

  public NonceManager(Web3j web3j) {
    this.web3j = web3j;
  }

  public BigInteger next(String address) throws IOException {
    AtomicLong counter = next.get(key(address));
    if (counter == null) {
      synchronized (this) {
        counter = next.get(key(address));
        if (counter == null) {
          counter = new AtomicLong(fetch(address));
          next.put(key(address), counter);
        }
      }
    }
    return BigInteger.valueOf(counter.getAndIncrement());
  }

  public synchronized void resync(String address) throws IOException {
    next.put(key(address), new AtomicLong(fetch(address)));
  }

  private long fetch(String address) throws IOException {
    return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING)
        .send()
        .getTransactionCount()
        .longValueExact();
  }

  private static String key(String address) {
    return address.toLowerCase();
  }
}

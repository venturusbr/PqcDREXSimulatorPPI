package br.org.venturus.drexsim.chain;

import br.org.venturus.drexsim.crypto.Dsa;
import br.org.venturus.drexsim.tx.TxFormat;
import java.math.BigInteger;

/**
 * One submission through {@link PqcTransactionManager}, reported to its listener. Feeds the
 * transaction log of the simulator and, later, the performance measurements.
 *
 * @param error JSON-RPC error message, or {@code null} when the node accepted the transaction
 */
public record SentTransaction(
    String account,
    String from,
    String to,
    Dsa dsa,
    TxFormat format,
    BigInteger nonce,
    BigInteger gasLimit,
    String hash,
    int size,
    int signatureLength,
    int publicKeyLength,
    long signNanos,
    long submitNanos,
    String error) {

  public boolean accepted() {
    return error == null;
  }
}

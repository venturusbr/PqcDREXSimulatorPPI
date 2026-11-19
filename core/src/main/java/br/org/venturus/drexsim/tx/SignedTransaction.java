package br.org.venturus.drexsim.tx;

import br.org.venturus.drexsim.crypto.Dsa;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

/**
 * A signed transaction ready for {@code eth_sendRawTransaction}.
 *
 * @param raw the typed envelope {@code type || rlp(fields)}
 * @param signatureLength bytes of signature it carries
 * @param publicKeyLength bytes of public key it carries (0 when the key is recovered)
 */
public record SignedTransaction(byte[] raw, Dsa dsa, TxFormat format, int signatureLength, int publicKeyLength) {

  public String rawHex() {
    return Numeric.toHexString(raw);
  }

  /** {@code keccak256(raw)}, the hash the node returns and indexes the transaction by. */
  public String hash() {
    return Numeric.toHexString(Hash.sha3(raw));
  }

  public int size() {
    return raw.length;
  }
}

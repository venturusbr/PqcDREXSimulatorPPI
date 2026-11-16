package br.org.venturus.drexsim.tx;

import br.org.venturus.drexsim.crypto.Dsa;
import br.org.venturus.drexsim.keystore.Account;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.web3j.crypto.Hash;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;
import org.web3j.utils.Numeric;

/**
 * Builds and signs transactions in the wire formats of besu-pqc-ppi (docs/pqc/04-transactions.md).
 *
 * <p>Flexible EIP-1559 ({@code 0x42}):
 *
 * <pre>
 * 0x42 || rlp([dsaType, chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, gasLimit, to, value,
 *              data, accessList, (publicKey), signature])
 * signingHash = keccak256(0x42 || rlp([... same fields without signature ...]))
 * </pre>
 *
 * <p>Standard EIP-1559 ({@code 0x02}) is the upstream format, signed with ECDSA.
 */
public final class TransactionSigner {

  public static final byte FLEXIBLE_EIP1559_TYPE = 0x42;
  public static final byte STANDARD_EIP1559_TYPE = 0x02;

  private TransactionSigner() {}

  public static SignedTransaction sign(UnsignedTransaction tx, Account account) {
    return account.format() == TxFormat.FLEXIBLE ? signFlexible(tx, account) : signStandard(tx, account);
  }

  // ---------------------------------------------------------------- flexible (0x42)

  public static SignedTransaction signFlexible(UnsignedTransaction tx, Account account) {
    Dsa dsa = account.dsa();
    byte[] carriedKey = dsa.carriedPublicKey(account.publicKey());
    byte[] signature = account.sign(flexibleSigningHash(tx, dsa, carriedKey));
    byte[] raw = encodeFlexible(tx, dsa, carriedKey, signature);
    return new SignedTransaction(
        raw, dsa, TxFormat.FLEXIBLE, signature.length, carriedKey == null ? 0 : carriedKey.length);
  }

  /** The hash a flexible transaction's signer signs. It covers {@code dsaType} and the carried key. */
  public static byte[] flexibleSigningHash(UnsignedTransaction tx, Dsa dsa, byte[] carriedKey) {
    return Hash.sha3(encodeFlexible(tx, dsa, carriedKey, null));
  }

  /** Encodes a flexible transaction; without a signature this is the signing preimage. */
  public static byte[] encodeFlexible(UnsignedTransaction tx, Dsa dsa, byte[] carriedKey, byte[] signature) {
    List<RlpType> fields = new ArrayList<>();
    // dsaType is always a 2-byte string, never a minimal scalar (0x0001 -> 0x82 0x00 0x01).
    fields.add(RlpString.create(new byte[] {(byte) (dsa.id() >> 8), (byte) dsa.id()}));
    addCommonFields(fields, tx);
    if (carriedKey != null) {
      fields.add(RlpString.create(carriedKey));
    }
    if (signature != null) {
      fields.add(RlpString.create(signature));
    }
    return typed(FLEXIBLE_EIP1559_TYPE, fields);
  }

  // ---------------------------------------------------------------- standard (0x02)

  public static SignedTransaction signStandard(UnsignedTransaction tx, Account account) {
    if (!account.dsa().supportsStandardTransactions()) {
      throw new IllegalArgumentException(account.dsa() + " cannot sign a standard transaction");
    }
    List<RlpType> fields = new ArrayList<>();
    addCommonFields(fields, tx);
    byte[] signature = account.sign(Hash.sha3(typed(STANDARD_EIP1559_TYPE, fields)));
    fields.add(RlpString.create(Byte.toUnsignedInt(signature[64]))); // yParity
    fields.add(RlpString.create(new BigInteger(1, Arrays.copyOfRange(signature, 0, 32)))); // r
    fields.add(RlpString.create(new BigInteger(1, Arrays.copyOfRange(signature, 32, 64)))); // s
    return new SignedTransaction(
        typed(STANDARD_EIP1559_TYPE, fields), account.dsa(), TxFormat.STANDARD, signature.length, 0);
  }

  // ---------------------------------------------------------------- helpers

  private static void addCommonFields(List<RlpType> fields, UnsignedTransaction tx) {
    fields.add(RlpString.create(BigInteger.valueOf(tx.chainId())));
    fields.add(RlpString.create(tx.nonce()));
    fields.add(RlpString.create(tx.maxPriorityFeePerGas()));
    fields.add(RlpString.create(tx.maxFeePerGas()));
    fields.add(RlpString.create(tx.gasLimit()));
    fields.add(tx.to() == null ? RlpString.create(new byte[0]) : RlpString.create(Numeric.hexStringToByteArray(tx.to())));
    fields.add(RlpString.create(tx.value()));
    fields.add(RlpString.create(tx.data()));
    fields.add(new RlpList()); // empty access list
  }

  private static byte[] typed(byte type, List<RlpType> fields) {
    byte[] rlp = RlpEncoder.encode(new RlpList(fields));
    byte[] out = new byte[rlp.length + 1];
    out[0] = type;
    System.arraycopy(rlp, 0, out, 1, rlp.length);
    return out;
  }
}

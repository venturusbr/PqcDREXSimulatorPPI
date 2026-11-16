package br.org.venturus.drexsim.tx;

import br.org.venturus.drexsim.crypto.Dsa;
import br.org.venturus.drexsim.crypto.EcdsaScheme;
import br.org.venturus.drexsim.crypto.HybridScheme;
import br.org.venturus.drexsim.keystore.Account;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.web3j.rlp.RlpDecoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;
import org.web3j.utils.Numeric;

/**
 * Decodes a flexible EIP-1559 transaction and checks its signature the way besu-pqc-ppi does
 * ({@code MainnetTransactionValidator.validateTransactionSignature}): resolve the signer's key
 * (recover the ECDSA part, take the carried post-quantum part), verify, derive the sender.
 *
 * <p>It is an offline mirror of the node's rules, used to test the encoder and to predict which
 * forged transactions the node must reject. The node remains the authority.
 */
public final class FlexibleTransactionVerifier {

  /** A decoded flexible transaction. */
  public record Decoded(
      Dsa dsa,
      UnsignedTransaction fields,
      byte[] carriedPublicKey,
      byte[] signature) {}

  /** Outcome of a verification: the sender when valid, the reason otherwise. */
  public record Result(boolean valid, String sender, String reason) {
    static Result ok(String sender) {
      return new Result(true, sender, null);
    }

    static Result invalid(String reason) {
      return new Result(false, null, reason);
    }
  }

  private FlexibleTransactionVerifier() {}

  public static Decoded decode(byte[] raw) {
    if (raw.length == 0 || raw[0] != TransactionSigner.FLEXIBLE_EIP1559_TYPE) {
      throw new IllegalArgumentException("Not a flexible EIP-1559 (0x42) transaction");
    }
    List<RlpType> f = ((RlpList) RlpDecoder.decode(Arrays.copyOfRange(raw, 1, raw.length)).getValues().get(0)).getValues();
    byte[] dsaBytes = bytes(f.get(0));
    if (dsaBytes.length != 2) {
      throw new IllegalArgumentException("dsaType must be 2 bytes, got " + dsaBytes.length);
    }
    Dsa dsa = Dsa.fromId((short) (((dsaBytes[0] & 0xff) << 8) | (dsaBytes[1] & 0xff)));
    byte[] to = bytes(f.get(6));
    UnsignedTransaction fields =
        new UnsignedTransaction(
            scalar(f.get(1)).longValueExact(),
            scalar(f.get(2)),
            scalar(f.get(3)),
            scalar(f.get(4)),
            scalar(f.get(5)),
            to.length == 0 ? null : Numeric.toHexString(to),
            scalar(f.get(7)),
            bytes(f.get(8)));
    if (!((RlpList) f.get(9)).getValues().isEmpty()) {
      throw new IllegalArgumentException("Access lists are not supported by this verifier");
    }
    int expectedFields = dsa.carried() == Dsa.Carried.NONE ? 11 : 12;
    if (f.size() != expectedFields) {
      throw new IllegalArgumentException(
          dsa + " transactions have " + expectedFields + " fields, got " + f.size());
    }
    byte[] carried = expectedFields == 12 ? bytes(f.get(10)) : null;
    if (carried != null && carried.length != dsa.carriedPublicKeyLength()) {
      // besu-pqc-ppi's PublicKeyDecoder refuses it at decoding time (JSON-RPC "Invalid params").
      throw new IllegalArgumentException(
          dsa + " carries a " + dsa.carriedPublicKeyLength() + "-byte public key, got " + carried.length);
    }
    return new Decoded(dsa, fields, carried, bytes(f.get(f.size() - 1)));
  }

  public static Result verify(byte[] raw) {
    Decoded tx;
    try {
      tx = decode(raw);
    } catch (RuntimeException e) {
      return Result.invalid("undecodable: " + e.getMessage());
    }
    Dsa dsa = tx.dsa();
    byte[] hash = TransactionSigner.flexibleSigningHash(tx.fields(), dsa, tx.carriedPublicKey());
    byte[] sig = tx.signature();

    return switch (dsa.carried()) {
      case NONE -> {
        byte[] key = ((EcdsaScheme) dsa.scheme()).recover(hash, sig);
        yield key == null ? Result.invalid("ECDSA signer could not be recovered") : Result.ok(Account.addressOf(key));
      }
      case FULL_KEY -> {
        yield dsa.scheme().verify(hash, sig, tx.carriedPublicKey())
            ? Result.ok(Account.addressOf(tx.carriedPublicKey()))
            : Result.invalid(dsa + " signature does not verify");
      }
      case POST_QUANTUM_HALF -> {
        HybridScheme hybrid = (HybridScheme) dsa.scheme();
        if (sig.length <= EcdsaScheme.SIGNATURE_LENGTH) {
          yield Result.invalid("hybrid signature too short");
        }
        byte[] ecdsaSig = Arrays.copyOfRange(sig, 0, EcdsaScheme.SIGNATURE_LENGTH);
        byte[] ecdsaKey = hybrid.classical().recover(hash, ecdsaSig);
        if (ecdsaKey == null) {
          yield Result.invalid("ECDSA half could not be recovered");
        }
        byte[] fullKey = concat(ecdsaKey, tx.carriedPublicKey());
        yield hybrid.verify(hash, sig, fullKey)
            ? Result.ok(Account.addressOf(fullKey))
            : Result.invalid("post-quantum half does not verify");
      }
    };
  }

  private static byte[] bytes(RlpType t) {
    return ((RlpString) t).getBytes();
  }

  private static BigInteger scalar(RlpType t) {
    return ((RlpString) t).asPositiveBigInteger();
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}

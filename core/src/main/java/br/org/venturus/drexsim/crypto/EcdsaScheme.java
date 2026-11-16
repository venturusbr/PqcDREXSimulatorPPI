package br.org.venturus.drexsim.crypto;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9IntegerConverter;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;

/**
 * ECDSA on a 256-bit SEC curve, with the conventions of besu-pqc-ppi's {@code AbstractSECP256}:
 *
 * <ul>
 *   <li>private key: 32 bytes; public key: 64 bytes ({@code X || Y}, no {@code 0x04} prefix);
 *   <li>signature: 65 bytes ({@code r || s || recId}), low-S, RFC 6979 deterministic {@code k}.
 * </ul>
 *
 * <p>Ported from besu-flex-wallet-ppi's {@code AbstractSecp256}, which is tested against the fork.
 */
public final class EcdsaScheme implements SignatureScheme {

  public static final int PRIVATE_KEY_LENGTH = 32;
  public static final int PUBLIC_KEY_LENGTH = 64;
  public static final int SIGNATURE_LENGTH = 65;

  public static final EcdsaScheme SECP256K1 = new EcdsaScheme("secp256k1");
  public static final EcdsaScheme SECP256R1 = new EcdsaScheme("secp256r1");

  private final String curveName;
  private final ECDomainParameters curve;
  private final BigInteger halfCurveOrder;
  private final BigInteger curvePrime;

  private EcdsaScheme(String curveName) {
    X9ECParameters params = CustomNamedCurves.getByName(curveName);
    this.curveName = curveName;
    this.curve = new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
    this.halfCurveOrder = curve.getN().shiftRight(1);
    this.curvePrime = curve.getCurve().getField().getCharacteristic();
  }

  public String curveName() {
    return curveName;
  }

  @Override
  public RawKeyPair generateKeyPair() {
    ECKeyPairGenerator generator = new ECKeyPairGenerator();
    generator.init(new ECKeyGenerationParameters(curve, new SecureRandom()));
    AsymmetricCipherKeyPair kp = generator.generateKeyPair();
    byte[] sk = toFixedLength(((ECPrivateKeyParameters) kp.getPrivate()).getD(), PRIVATE_KEY_LENGTH);
    return new RawKeyPair(sk, publicKeyOf(sk));
  }

  @Override
  public byte[] publicKeyOf(byte[] privateKey) {
    return encodePoint(curve.getG().multiply(scalar(privateKey)));
  }

  @Override
  public byte[] sign(byte[] message, byte[] privateKey) {
    if (message.length != 32) {
      throw new IllegalArgumentException(curveName + " signs a 32-byte hash, got " + message.length + " bytes");
    }
    BigInteger d = scalar(privateKey);
    ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
    signer.init(true, new ECPrivateKeyParameters(d, curve));
    BigInteger[] rs = signer.generateSignature(message);
    BigInteger r = rs[0];
    BigInteger s = rs[1];
    if (s.compareTo(halfCurveOrder) > 0) {
      s = curve.getN().subtract(s); // low-S (EIP-2)
    }

    ECPoint expected = curve.getG().multiply(d).normalize();
    for (int recId = 0; recId < 4; recId++) {
      ECPoint candidate = recoverPoint(recId, r, s, message);
      if (candidate != null && candidate.equals(expected)) {
        byte[] out = new byte[SIGNATURE_LENGTH];
        System.arraycopy(toFixedLength(r, 32), 0, out, 0, 32);
        System.arraycopy(toFixedLength(s, 32), 0, out, 32, 32);
        out[64] = (byte) recId;
        return out;
      }
    }
    throw new IllegalStateException("Unable to compute the " + curveName + " recovery id");
  }

  /** Recovers the 64-byte public key that produced {@code signature}, or {@code null}. */
  public byte[] recover(byte[] message, byte[] signature) {
    if (message.length != 32 || signature.length != SIGNATURE_LENGTH) {
      return null;
    }
    BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature, 0, 32));
    BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature, 32, 64));
    int recId = signature[64];
    if (r.signum() == 0 || s.signum() == 0 || r.compareTo(curve.getN()) >= 0
        || s.compareTo(halfCurveOrder) > 0 || recId < 0 || recId > 3) {
      return null; // besu-pqc-ppi rejects high-S signatures as malleable
    }
    try {
      ECPoint q = recoverPoint(recId, r, s, message);
      return q == null ? null : encodePoint(q);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  @Override
  public boolean verify(byte[] message, byte[] signature, byte[] publicKey) {
    byte[] recovered = recover(message, signature);
    return recovered != null && Arrays.equals(recovered, publicKey);
  }

  private BigInteger scalar(byte[] privateKey) {
    if (privateKey.length != PRIVATE_KEY_LENGTH) {
      throw new IllegalArgumentException(curveName + " private key must be 32 bytes, got " + privateKey.length);
    }
    return new BigInteger(1, privateKey);
  }

  private static byte[] encodePoint(ECPoint q) {
    byte[] uncompressed = q.normalize().getEncoded(false);
    return Arrays.copyOfRange(uncompressed, 1, uncompressed.length);
  }

  private static byte[] toFixedLength(BigInteger value, int length) {
    byte[] raw = value.toByteArray();
    if (raw.length == length) {
      return raw;
    }
    if (raw.length == length + 1 && raw[0] == 0) {
      return Arrays.copyOfRange(raw, 1, raw.length);
    }
    if (raw.length > length) {
      throw new IllegalArgumentException("Value does not fit in " + length + " bytes");
    }
    byte[] padded = new byte[length];
    System.arraycopy(raw, 0, padded, length - raw.length, raw.length);
    return padded;
  }

  /** SEC 1 v2.0 §4.1.6 public key recovery. */
  private ECPoint recoverPoint(int recId, BigInteger r, BigInteger s, byte[] hash) {
    BigInteger n = curve.getN();
    BigInteger x = r.add(BigInteger.valueOf(recId / 2).multiply(n));
    if (x.compareTo(curvePrime) >= 0) {
      return null;
    }
    X9IntegerConverter x9 = new X9IntegerConverter();
    byte[] compressed = x9.integerToBytes(x, 1 + x9.getByteLength(curve.getCurve()));
    compressed[0] = (byte) ((recId & 1) == 1 ? 0x03 : 0x02);
    ECPoint bigR = curve.getCurve().decodePoint(compressed);
    if (!bigR.multiply(n).isInfinity()) {
      return null;
    }
    BigInteger e = new BigInteger(1, hash);
    BigInteger eInv = BigInteger.ZERO.subtract(e).mod(n);
    BigInteger rInv = r.modInverse(n);
    BigInteger srInv = rInv.multiply(s).mod(n);
    BigInteger eInvrInv = rInv.multiply(eInv).mod(n);
    return ECAlgorithms.sumOfTwoMultiplies(curve.getG(), eInvrInv, bigR, srInv).normalize();
  }
}

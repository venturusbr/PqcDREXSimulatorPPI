package br.org.venturus.drexsim.crypto;

import java.security.SecureRandom;
import java.util.Arrays;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.pqc.crypto.falcon.FalconKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.falcon.FalconKeyPairGenerator;
import org.bouncycastle.pqc.crypto.falcon.FalconParameters;
import org.bouncycastle.pqc.crypto.falcon.FalconPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.falcon.FalconPublicKeyParameters;
import org.bouncycastle.pqc.crypto.falcon.FalconSigner;

/**
 * Falcon-512 with besu-pqc-ppi's raw encodings: public key {@code h} (896 bytes), private key
 * {@code f || g || F || h} (2176 bytes). Signatures are variable-length, at most 752 bytes.
 */
public final class Falcon512Scheme implements SignatureScheme {

  public static final Falcon512Scheme INSTANCE = new Falcon512Scheme();
  public static final int PUBLIC_KEY_LENGTH = 896;
  public static final int MAX_SIGNATURE_LENGTH = 752;

  private static final FalconParameters PARAMS = FalconParameters.falcon_512;
  private static final int F_LENGTH = 384;
  private static final int G_LENGTH = 384;
  private static final int CAPITAL_F_LENGTH = 512;
  private static final int PRIVATE_KEY_LENGTH = F_LENGTH + G_LENGTH + CAPITAL_F_LENGTH + PUBLIC_KEY_LENGTH;

  private Falcon512Scheme() {}

  @Override
  public RawKeyPair generateKeyPair() {
    FalconKeyPairGenerator generator = new FalconKeyPairGenerator();
    generator.init(new FalconKeyGenerationParameters(new SecureRandom(), PARAMS));
    AsymmetricCipherKeyPair kp = generator.generateKeyPair();
    FalconPrivateKeyParameters sk = (FalconPrivateKeyParameters) kp.getPrivate();
    byte[] h = ((FalconPublicKeyParameters) kp.getPublic()).getH();
    return new RawKeyPair(concat(sk.getSpolyf(), sk.getG(), sk.getSpolyF(), h), h);
  }

  @Override
  public byte[] sign(byte[] message, byte[] privateKey) {
    FalconSigner signer = new FalconSigner();
    signer.init(true, new ParametersWithRandom(toPrivateParameters(privateKey), new SecureRandom()));
    return signer.generateSignature(message);
  }

  @Override
  public boolean verify(byte[] message, byte[] signature, byte[] publicKey) {
    if (signature.length == 0 || signature.length > MAX_SIGNATURE_LENGTH) {
      return false;
    }
    try {
      FalconSigner verifier = new FalconSigner();
      verifier.init(false, new FalconPublicKeyParameters(PARAMS, publicKey));
      return verifier.verifySignature(message, signature);
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public byte[] publicKeyOf(byte[] privateKey) {
    checkLength(privateKey);
    return Arrays.copyOfRange(privateKey, PRIVATE_KEY_LENGTH - PUBLIC_KEY_LENGTH, PRIVATE_KEY_LENGTH);
  }

  private static FalconPrivateKeyParameters toPrivateParameters(byte[] sk) {
    checkLength(sk);
    int gStart = F_LENGTH;
    int capitalFStart = gStart + G_LENGTH;
    int hStart = capitalFStart + CAPITAL_F_LENGTH;
    return new FalconPrivateKeyParameters(
        PARAMS,
        Arrays.copyOfRange(sk, 0, gStart),
        Arrays.copyOfRange(sk, gStart, capitalFStart),
        Arrays.copyOfRange(sk, capitalFStart, hStart),
        Arrays.copyOfRange(sk, hStart, sk.length));
  }

  private static void checkLength(byte[] sk) {
    if (sk.length != PRIVATE_KEY_LENGTH) {
      throw new IllegalArgumentException(
          "Falcon-512 private key must be " + PRIVATE_KEY_LENGTH + " bytes, got " + sk.length);
    }
  }

  private static byte[] concat(byte[]... parts) {
    int length = 0;
    for (byte[] p : parts) {
      length += p.length;
    }
    byte[] out = new byte[length];
    int offset = 0;
    for (byte[] p : parts) {
      System.arraycopy(p, 0, out, offset, p.length);
      offset += p.length;
    }
    return out;
  }
}

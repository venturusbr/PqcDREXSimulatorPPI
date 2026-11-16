package br.org.venturus.drexsim.crypto;

import java.security.SecureRandom;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner;

/**
 * ML-DSA (FIPS 204), pure variant with an empty context and deterministic signing, as in
 * besu-pqc-ppi. Keys use Bouncy Castle's raw encodings: the expanded private key and the public
 * key {@code rho || t1}. A fresh signer is created per call because it keeps SHAKE state.
 */
public final class MlDsaScheme implements SignatureScheme {

  public static final MlDsaScheme MLDSA44 = new MlDsaScheme(MLDSAParameters.ml_dsa_44);
  public static final MlDsaScheme MLDSA65 = new MlDsaScheme(MLDSAParameters.ml_dsa_65);
  public static final MlDsaScheme MLDSA87 = new MlDsaScheme(MLDSAParameters.ml_dsa_87);

  private final MLDSAParameters parameters;

  private MlDsaScheme(MLDSAParameters parameters) {
    this.parameters = parameters;
  }

  @Override
  public RawKeyPair generateKeyPair() {
    MLDSAKeyPairGenerator generator = new MLDSAKeyPairGenerator();
    generator.init(new MLDSAKeyGenerationParameters(new SecureRandom(), parameters));
    AsymmetricCipherKeyPair kp = generator.generateKeyPair();
    return new RawKeyPair(
        ((MLDSAPrivateKeyParameters) kp.getPrivate()).getEncoded(),
        ((MLDSAPublicKeyParameters) kp.getPublic()).getEncoded());
  }

  @Override
  public byte[] sign(byte[] message, byte[] privateKey) {
    MLDSASigner signer = new MLDSASigner();
    // No ParametersWithRandom: Bouncy Castle then signs deterministically, like besu-pqc-ppi.
    signer.init(true, new MLDSAPrivateKeyParameters(parameters, privateKey));
    signer.update(message, 0, message.length);
    try {
      return signer.generateSignature();
    } catch (CryptoException e) {
      throw new IllegalStateException("ML-DSA signing failed", e);
    }
  }

  @Override
  public boolean verify(byte[] message, byte[] signature, byte[] publicKey) {
    try {
      MLDSASigner verifier = new MLDSASigner();
      verifier.init(false, new MLDSAPublicKeyParameters(parameters, publicKey));
      verifier.update(message, 0, message.length);
      return verifier.verifySignature(signature);
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public byte[] publicKeyOf(byte[] privateKey) {
    return new MLDSAPrivateKeyParameters(parameters, privateKey).getPublicKey();
  }
}

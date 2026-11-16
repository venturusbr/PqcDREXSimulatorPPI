package br.org.venturus.drexsim.crypto;

import java.util.Arrays;

/**
 * Hybrid ECDSA + post-quantum signature by strong nesting concatenation, as in besu-pqc-ppi's
 * {@code HybridStrongNestingConcatenation}: ECDSA signs the message, then the post-quantum
 * algorithm signs {@code message || ecdsaSignature}. The signature is {@code ecdsaSig || pqSig}.
 *
 * <p>Keys are concatenated, ECDSA first: private {@code ecdsaSk (32) || pqSk}, public {@code
 * ecdsaPk (64) || pqPk}. ECDSA always comes first because its lengths are fixed, which lets the
 * post-quantum part (possibly variable-length, like Falcon) take the remainder.
 */
public final class HybridScheme implements SignatureScheme {

  private final EcdsaScheme first;
  private final SignatureScheme second;

  public HybridScheme(EcdsaScheme first, SignatureScheme second) {
    this.first = first;
    this.second = second;
  }

  public EcdsaScheme classical() {
    return first;
  }

  public SignatureScheme postQuantum() {
    return second;
  }

  @Override
  public RawKeyPair generateKeyPair() {
    RawKeyPair a = first.generateKeyPair();
    RawKeyPair b = second.generateKeyPair();
    return new RawKeyPair(concat(a.privateKey(), b.privateKey()), concat(a.publicKey(), b.publicKey()));
  }

  @Override
  public byte[] sign(byte[] message, byte[] privateKey) {
    byte[] firstSignature = first.sign(message, classicalPart(privateKey, EcdsaScheme.PRIVATE_KEY_LENGTH));
    byte[] secondSignature =
        second.sign(concat(message, firstSignature), postQuantumPart(privateKey, EcdsaScheme.PRIVATE_KEY_LENGTH));
    return concat(firstSignature, secondSignature);
  }

  @Override
  public boolean verify(byte[] message, byte[] signature, byte[] publicKey) {
    if (signature.length <= EcdsaScheme.SIGNATURE_LENGTH || publicKey.length <= EcdsaScheme.PUBLIC_KEY_LENGTH) {
      return false;
    }
    byte[] firstSignature = Arrays.copyOfRange(signature, 0, EcdsaScheme.SIGNATURE_LENGTH);
    byte[] secondSignature = Arrays.copyOfRange(signature, EcdsaScheme.SIGNATURE_LENGTH, signature.length);
    return first.verify(message, firstSignature, classicalPart(publicKey, EcdsaScheme.PUBLIC_KEY_LENGTH))
        && second.verify(
            concat(message, firstSignature), secondSignature, postQuantumPart(publicKey, EcdsaScheme.PUBLIC_KEY_LENGTH));
  }

  @Override
  public byte[] publicKeyOf(byte[] privateKey) {
    return concat(
        first.publicKeyOf(classicalPart(privateKey, EcdsaScheme.PRIVATE_KEY_LENGTH)),
        second.publicKeyOf(postQuantumPart(privateKey, EcdsaScheme.PRIVATE_KEY_LENGTH)));
  }

  static byte[] classicalPart(byte[] composite, int classicalLength) {
    return Arrays.copyOfRange(composite, 0, classicalLength);
  }

  static byte[] postQuantumPart(byte[] composite, int classicalLength) {
    return Arrays.copyOfRange(composite, classicalLength, composite.length);
  }

  static byte[] concat(byte[] a, byte[] b) {
    byte[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}

package br.org.venturus.drexsim.crypto;

/**
 * A digital signature algorithm working on raw byte encodings, the same ones besu-pqc-ppi uses on
 * the wire. Implementations are stateless and thread-safe.
 */
public interface SignatureScheme {

  /** Generates a fresh key pair. */
  RawKeyPair generateKeyPair();

  /** Signs {@code message} (a 32-byte hash for transactions) with a raw private key. */
  byte[] sign(byte[] message, byte[] privateKey);

  /** Verifies a signature against a raw public key. Never throws on malformed input. */
  boolean verify(byte[] message, byte[] signature, byte[] publicKey);

  /** Derives the raw public key of a raw private key. */
  byte[] publicKeyOf(byte[] privateKey);
}

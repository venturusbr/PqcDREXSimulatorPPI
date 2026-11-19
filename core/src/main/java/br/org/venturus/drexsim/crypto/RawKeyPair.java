package br.org.venturus.drexsim.crypto;

/**
 * A key pair in raw encoding. For a hybrid, each key is the concatenation of the ECDSA key and the
 * post-quantum key, ECDSA first (see {@link HybridScheme}).
 */
public record RawKeyPair(byte[] privateKey, byte[] publicKey) {}

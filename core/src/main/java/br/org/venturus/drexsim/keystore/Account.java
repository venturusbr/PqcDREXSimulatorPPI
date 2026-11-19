package br.org.venturus.drexsim.keystore;

import br.org.venturus.drexsim.crypto.Dsa;
import br.org.venturus.drexsim.crypto.RawKeyPair;
import br.org.venturus.drexsim.tx.TxFormat;
import java.util.Arrays;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

/**
 * A simulated actor (BCB, bank, reserve, client) and the keys it signs with.
 *
 * <p>The address is {@code keccak256(publicKey)[-20:]} over the full public key, as besu-pqc-ppi
 * derives it. For a hybrid, the key is {@code ecdsaPk || pqPk}, so a hybrid account is a
 * different account from the account of its ECDSA half alone.
 */
public final class Account {

  private final String name;
  private final Dsa dsa;
  private final TxFormat format;
  private final byte[] privateKey;
  private final byte[] publicKey;
  private final String address;

  public Account(String name, Dsa dsa, TxFormat format, byte[] privateKey, byte[] publicKey) {
    if (format == TxFormat.STANDARD && !dsa.supportsStandardTransactions()) {
      throw new IllegalArgumentException(dsa + " cannot sign standard EIP-1559 transactions; use FLEXIBLE");
    }
    this.name = name;
    this.dsa = dsa;
    this.format = format;
    this.privateKey = privateKey.clone();
    this.publicKey = publicKey.clone();
    this.address = addressOf(publicKey);
  }

  /** Generates a fresh account. */
  public static Account generate(String name, Dsa dsa, TxFormat format) {
    RawKeyPair kp = dsa.scheme().generateKeyPair();
    return new Account(name, dsa, format, kp.privateKey(), kp.publicKey());
  }

  /** Rebuilds an account from its private key alone. */
  public static Account fromPrivateKey(String name, Dsa dsa, TxFormat format, byte[] privateKey) {
    return new Account(name, dsa, format, privateKey, dsa.scheme().publicKeyOf(privateKey));
  }

  public static String addressOf(byte[] fullPublicKey) {
    byte[] hash = Hash.sha3(fullPublicKey);
    return Numeric.toHexString(Arrays.copyOfRange(hash, 12, 32));
  }

  public String name() {
    return name;
  }

  public Dsa dsa() {
    return dsa;
  }

  public TxFormat format() {
    return format;
  }

  public byte[] privateKey() {
    return privateKey.clone();
  }

  public byte[] publicKey() {
    return publicKey.clone();
  }

  /** Lower-case {@code 0x}-prefixed address. */
  public String address() {
    return address;
  }

  /** Short description used in tables and logs, e.g. {@code SECP256K1-MLDSA44} or {@code SECP256K1 (std)}. */
  public String signingLabel() {
    return format == TxFormat.STANDARD ? dsa.wireName() + " (std)" : dsa.wireName();
  }

  public byte[] sign(byte[] message) {
    return dsa.scheme().sign(message, privateKey);
  }

  @Override
  public String toString() {
    return name + " " + address + " [" + signingLabel() + "]";
  }
}

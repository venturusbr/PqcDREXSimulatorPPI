package br.org.venturus.drexsim.crypto;

import java.util.Arrays;
import java.util.Locale;

/**
 * Account DSAs accepted by besu-pqc-ppi ({@code DSAParametersSet}), with their wire identifier,
 * what a flexible transaction must carry as {@code publicKey}, and the intrinsic base gas the fork
 * charges for them (docs/pqc/04-transactions.md#gas).
 */
public enum Dsa {
  SECP256K1((short) 0x0001, "SECP256K1", 64, 21_000, EcdsaScheme.SECP256K1, Carried.NONE),
  SECP256R1((short) 0x0002, "SECP256R1", 64, 31_629, EcdsaScheme.SECP256R1, Carried.NONE),
  MLDSA44((short) 0x0030, "MLDSA44", 1312, 82_553, MlDsaScheme.MLDSA44, Carried.FULL_KEY),
  MLDSA65((short) 0x0031, "MLDSA65", 1952, 110_047, MlDsaScheme.MLDSA65, Carried.FULL_KEY),
  MLDSA87((short) 0x0032, "MLDSA87", 2592, 147_047, MlDsaScheme.MLDSA87, Carried.FULL_KEY),
  FALCON512((short) 0x0040, "FALCON512", 896, 46_511, Falcon512Scheme.INSTANCE, Carried.FULL_KEY),
  SECP256K1_MLDSA44(
      (short) 0x0060,
      "SECP256K1-MLDSA44",
      1376,
      86_102,
      new HybridScheme(EcdsaScheme.SECP256K1, MlDsaScheme.MLDSA44),
      Carried.POST_QUANTUM_HALF),
  SECP256K1_FALCON512(
      (short) 0x0070,
      "SECP256K1-FALCON512",
      960,
      50_369,
      new HybridScheme(EcdsaScheme.SECP256K1, Falcon512Scheme.INSTANCE),
      Carried.POST_QUANTUM_HALF);

  /** Intrinsic gas of a standard (non-flexible) transaction. */
  public static final long STANDARD_BASE_GAS = 21_000;

  /** Which part of the signer's public key a flexible transaction carries. */
  public enum Carried {
    /** Recoverable from the signature: nothing is carried. */
    NONE,
    /** Not recoverable: the full key is carried. */
    FULL_KEY,
    /** Hybrid: the ECDSA half is recovered, the post-quantum half is carried. */
    POST_QUANTUM_HALF
  }

  private final short id;
  private final String wireName;
  private final int publicKeyLength;
  private final long baseGas;
  private final SignatureScheme scheme;
  private final Carried carried;

  Dsa(short id, String wireName, int publicKeyLength, long baseGas, SignatureScheme scheme, Carried carried) {
    this.id = id;
    this.wireName = wireName;
    this.publicKeyLength = publicKeyLength;
    this.baseGas = baseGas;
    this.scheme = scheme;
    this.carried = carried;
  }

  public short id() {
    return id;
  }

  /** Name used by besu-pqc-ppi ({@code --accounts-supported-dsas}, {@code vnt_getAccountsSupportedDSAs}). */
  public String wireName() {
    return wireName;
  }

  /** Length of the full public key (for a hybrid, ECDSA and post-quantum halves together). */
  public int publicKeyLength() {
    return publicKeyLength;
  }

  /** Length of the key a flexible transaction carries (0 when nothing is carried). */
  public int carriedPublicKeyLength() {
    return switch (carried) {
      case NONE -> 0;
      case FULL_KEY -> publicKeyLength;
      case POST_QUANTUM_HALF -> publicKeyLength - EcdsaScheme.PUBLIC_KEY_LENGTH;
    };
  }

  public long baseGas() {
    return baseGas;
  }

  /** Gas a flexible transaction costs above what {@code eth_estimateGas} reports. */
  public long estimateGasShortfall() {
    return baseGas - STANDARD_BASE_GAS;
  }

  public SignatureScheme scheme() {
    return scheme;
  }

  public Carried carried() {
    return carried;
  }

  public boolean isHybrid() {
    return carried == Carried.POST_QUANTUM_HALF;
  }

  /** True for the post-quantum and hybrid DSAs, the ones that resist a quantum adversary. */
  public boolean isQuantumResistant() {
    return carried != Carried.NONE;
  }

  /** True when the DSA can also sign standard EIP-1559 (type 0x02) transactions. */
  public boolean supportsStandardTransactions() {
    return this == SECP256K1 || this == SECP256R1;
  }

  /** The part of a full public key that a flexible transaction must carry, or {@code null}. */
  public byte[] carriedPublicKey(byte[] fullPublicKey) {
    return switch (carried) {
      case NONE -> null;
      case FULL_KEY -> fullPublicKey;
      case POST_QUANTUM_HALF ->
          Arrays.copyOfRange(fullPublicKey, EcdsaScheme.PUBLIC_KEY_LENGTH, fullPublicKey.length);
    };
  }

  public static Dsa fromId(short id) {
    for (Dsa dsa : values()) {
      if (dsa.id == id) {
        return dsa;
      }
    }
    throw new IllegalArgumentException(String.format("Unknown dsaType 0x%04x", id));
  }

  /** Accepts the wire name ({@code SECP256K1-MLDSA44}) or the enum name ({@code SECP256K1_MLDSA44}). */
  public static Dsa fromName(String name) {
    String normalized = name.trim().toUpperCase(Locale.ROOT).replace('_', '-');
    for (Dsa dsa : values()) {
      if (dsa.wireName.equals(normalized)) {
        return dsa;
      }
    }
    throw new IllegalArgumentException("Unknown DSA " + name + "; expected one of " + Arrays.toString(wireNames()));
  }

  public static String[] wireNames() {
    return Arrays.stream(values()).map(Dsa::wireName).toArray(String[]::new);
  }

  @Override
  public String toString() {
    return wireName;
  }
}

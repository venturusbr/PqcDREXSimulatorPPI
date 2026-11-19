package br.org.venturus.drexsim.scenario;

import br.org.venturus.drexsim.crypto.Dsa;
import br.org.venturus.drexsim.tx.TxFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * How every actor of a scenario signs: one DSA and one transaction format. Running the same flows
 * under each profile compares the platform's behaviour and cost per signature algorithm.
 */
public record SignerProfile(Dsa dsa, TxFormat format) {

  public SignerProfile {
    if (format == TxFormat.STANDARD && !dsa.supportsStandardTransactions()) {
      throw new IllegalArgumentException(dsa + " cannot sign standard transactions");
    }
  }

  /** Short label, e.g. {@code MLDSA44} or {@code SECP256K1 (std)} for the classical baseline. */
  public String label() {
    return format == TxFormat.STANDARD ? dsa.wireName() + " (std)" : dsa.wireName();
  }

  public boolean isBaseline() {
    return format == TxFormat.STANDARD && dsa == Dsa.SECP256K1;
  }

  /** The classical baseline: standard EIP-1559 transactions signed with ECDSA on secp256k1. */
  public static final SignerProfile BASELINE = new SignerProfile(Dsa.SECP256K1, TxFormat.STANDARD);

  /**
   * The baseline plus a flexible profile for every DSA. Standard secp256r1 is left out: it is only
   * valid on a network whose genesis curve is secp256r1, while flexible SECP256R1 works on any.
   */
  public static List<SignerProfile> all() {
    List<SignerProfile> profiles = new ArrayList<>();
    profiles.add(BASELINE);
    for (Dsa dsa : Dsa.values()) {
      profiles.add(new SignerProfile(dsa, TxFormat.FLEXIBLE));
    }
    return profiles;
  }

  /** Parses {@code MLDSA44}, {@code SECP256K1-MLDSA44} or {@code SECP256K1:std}. */
  public static SignerProfile parse(String text) {
    String t = text.trim();
    boolean standard = t.toLowerCase().endsWith(":std") || t.toLowerCase().endsWith("(std)");
    String name = t.replaceAll("(?i)(:std|\\s*\\(std\\))$", "");
    return new SignerProfile(Dsa.fromName(name), standard ? TxFormat.STANDARD : TxFormat.FLEXIBLE);
  }
}

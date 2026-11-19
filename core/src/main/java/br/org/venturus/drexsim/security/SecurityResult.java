package br.org.venturus.drexsim.security;

/**
 * One security check: an attack attempted against the live network and whether the platform
 * behaved as expected.
 *
 * @param category {@code protocol}, {@code quantum} or {@code contract}
 * @param attack what the attacker tried
 * @param target signer profile of the victim (or of the network for contract checks)
 * @param expected what must happen, e.g. {@link Outcome#BLOCKED}
 * @param observed what happened
 * @param evidence node error, sender address, balances or receipt status backing the observation
 */
public record SecurityResult(
    String category, String attack, String target, Outcome expected, Outcome observed, String evidence) {

  public enum Outcome {
    /** The attack had no effect on the victim: rejected by the node, reverted, or run as another account. */
    BLOCKED,
    /** The attack achieved its goal. */
    SUCCEEDED
  }

  public boolean asExpected() {
    return expected == observed;
  }
}

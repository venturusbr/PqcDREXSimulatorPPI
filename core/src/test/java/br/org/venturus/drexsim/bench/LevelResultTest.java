package br.org.venturus.drexsim.bench;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LevelResultTest {

  /** A level of {@code seconds} at {@code offered} TPS on 2 s blocks. */
  private static LevelResult level(int seconds, double offered, double achieved, long included, long pending, long rejected) {
    long submitted = Math.round(offered * seconds);
    return new LevelResult("MLDSA44", 4, 50_000_000L, offered, seconds, submitted, rejected, included, pending, offered,
        achieved, 1300, 2200, 2500, 3000, 6, 40, 45, 10, 300, 120_000, 2.0, 200, 4400, 1.2, 200, 0, 85, 900, "");
  }

  @Test
  void aLevelThatTookOneBlockMoreIsSustained() {
    // 250 transactions sent in 10 s, included over 12 s: one late block, or one more block boundary.
    assertTrue(level(10, 25, 250 / 12.0, 250, 0, 0).sustained());
    assertTrue(level(30, 100, 100, 3000, 0, 0).sustained());
  }

  @Test
  void aLevelThatFellBehindIsNot() {
    assertFalse(level(10, 25, 250 / 16.0, 250, 0, 0).sustained(), "three blocks late");
    assertFalse(level(30, 100, 80, 3000, 0, 0).sustained(), "included at 80% of the offered rate");
    assertFalse(level(30, 100, 100, 2800, 200, 0).sustained(), "backlog left");
    assertFalse(level(30, 100, 100, 2800, 0, 200).sustained(), "rejected by the nodes");
  }

  @Test
  void aLevelThatSentNothingIsNotSustained() {
    // Issue #11: 0 seconds sent nothing, which used to count as sustained.
    assertFalse(level(0, 25, 0, 0, 0, 0).sustained());
    assertFalse(new LevelResult("MLDSA44", 4, 0, 25, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        -1, -1, "").sustained());
  }
}

package br.org.venturus.drexsim.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchReportTest {

  private static LevelResult level(String profile, double target, double achieved, long pending) {
    return new LevelResult(profile, 4, 50_000_000L, target, 30, (long) (target * 30), 0, (long) (achieved * 30), pending, target, achieved,
        1200, 2200, 2500, 3000, 15, achieved * 2, (int) (achieved * 2.5), 40, 300, 120_000, 2.0, 208, 4400, 1.2, 200, 0, 85,
        900, "");
  }

  @Test
  void reportsTheHighestSustainedLevelPerSigner(@TempDir Path dir) throws Exception {
    BenchReport.append(dir, List.of(level("MLDSA44", 50, 49, 0), level("MLDSA44", 100, 97, 0), level("MLDSA44", 200, 120, 900)));
    BenchReport.append(dir, List.of(level("SECP256K1 (std)", 50, 49, 0)));

    List<String> csv = Files.readAllLines(dir.resolve("bench.csv"));
    assertEquals(5, csv.size(), "header written once, then one line per level");
    String md = Files.readString(dir.resolve("bench.md"));
    assertTrue(md.contains("| MLDSA44 | 4 | 50M | 100 | 97.00 |"), md);
    assertTrue(md.contains("| SECP256K1 (std) | 4 | 50M | 50 |"), md);
    assertTrue(md.contains("**no**"), "the 200 TPS level is not sustained");
  }

  @Test
  void sustainedNeedsNinetyPercentAndLittleBacklog() {
    assertTrue(level("x", 100, 95, 10).sustained());
    assertTrue(!level("x", 100, 80, 0).sustained());
    assertTrue(!level("x", 100, 99, 500).sustained());
  }
}

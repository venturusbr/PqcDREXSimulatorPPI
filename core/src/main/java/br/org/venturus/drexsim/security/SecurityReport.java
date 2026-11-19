package br.org.venturus.drexsim.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Markdown report of a {@link SecuritySuite} run. */
public final class SecurityReport {

  private static final Map<String, String> TITLES = Map.of(
      "protocol", "Protocol attacks",
      "quantum", "Simulated quantum adversary",
      "contract", "Contract findings");

  private static final Map<String, String> INTROS = Map.of(
      "protocol",
      "Forged or altered transactions sent raw with `eth_sendRawTransaction`. Expected: **BLOCKED**, meaning the node "
          + "rejects the transaction or, for recoverable ECDSA, accepts it only as a transaction of some other sender.",
      "quantum",
      "The adversary knows every ECDSA private key, as Shor's algorithm would give it, plus its own keys. Against the "
          + "classical deployment its attacks are expected to **succeed**: that is the risk being addressed. Against the "
          + "hybrid deployment every strategy must be **BLOCKED**, judged on balances and roles, not on error messages.",
      "contract",
      "Flaws of the pilot contracts (docs/contracts-reference.md) that no signature scheme fixes. Expected: the flaw is "
          + "reproduced (**SUCCEEDED**), documenting it for the contract owners.");

  private SecurityReport() {}

  public static boolean allAsExpected(List<SecurityResult> results) {
    return results.stream().allMatch(SecurityResult::asExpected);
  }

  public static String summary(List<SecurityResult> results) {
    StringBuilder out = new StringBuilder();
    for (String category : List.of("protocol", "quantum", "contract")) {
      List<SecurityResult> rs = results.stream().filter(r -> r.category().equals(category)).toList();
      long ok = rs.stream().filter(SecurityResult::asExpected).count();
      out.append(String.format("%-28s %d/%d as expected%n", TITLES.get(category), ok, rs.size()));
    }
    results.stream().filter(r -> !r.asExpected()).forEach(r -> out.append(String.format(
        "UNEXPECTED %s | %s | expected %s, observed %s | %s%n", r.target(), r.attack(), r.expected(), r.observed(), r.evidence())));
    return out.toString();
  }

  public static void write(List<SecurityResult> results, String rpcUrl, Path dir) throws IOException {
    Files.createDirectories(dir);
    StringBuilder md = new StringBuilder("# Security tests\n\n");
    md.append("Run at ").append(Instant.now()).append(" against `").append(rpcUrl).append("`. ")
        .append(results.stream().filter(SecurityResult::asExpected).count()).append(" of ").append(results.size())
        .append(" checks behaved as expected.\n");
    for (String category : List.of("protocol", "quantum", "contract")) {
      List<SecurityResult> rs = results.stream().filter(r -> r.category().equals(category)).toList();
      md.append("\n## ").append(TITLES.get(category)).append("\n\n").append(INTROS.get(category)).append("\n\n");
      if (category.equals("protocol")) {
        // Compact matrix: attack x signer.
        List<String> targets = rs.stream().map(SecurityResult::target).distinct().toList();
        List<String> attacks = rs.stream().map(SecurityResult::attack)
            .map(a -> a.startsWith("dsaType relabelled") ? "dsaType relabelled" : a).distinct().toList();
        md.append("| Attack |");
        targets.forEach(t -> md.append(' ').append(t).append(" |"));
        md.append("\n|---|").append("---|".repeat(targets.size())).append('\n');
        for (String attack : attacks) {
          md.append("| ").append(attack).append(" |");
          for (String t : targets) {
            String cell = rs.stream()
                .filter(r -> r.target().equals(t) && (r.attack().equals(attack) || r.attack().startsWith(attack)))
                .findFirst().map(r -> (r.asExpected() ? "✅ " : "❌ ") + r.observed()).orElse("n/a");
            md.append(' ').append(cell).append(" |");
          }
          md.append('\n');
        }
        md.append("\n<details><summary>Node responses</summary>\n\n| Signer | Attack | Evidence |\n|---|---|---|\n");
        rs.forEach(r -> md.append("| ").append(r.target()).append(" | ").append(r.attack()).append(" | ")
            .append(escape(r.evidence())).append(" |\n"));
        md.append("\n</details>\n");
      } else {
        md.append("| Victim signs with | Attack | Expected | Observed | Evidence |\n|---|---|---|---|---|\n");
        rs.forEach(r -> md.append("| ").append(r.target()).append(" | ").append(r.attack()).append(" | ")
            .append(r.expected()).append(" | ").append(r.asExpected() ? "✅ " : "❌ ").append(r.observed()).append(" | ")
            .append(escape(r.evidence())).append(" |\n"));
      }
    }
    md.append("\n## Not covered here\n\n")
        .append("- Forged QBFT consensus messages and block seals: they need a malicious validator on the p2p layer; ")
        .append("besu-pqc-ppi covers them in its consensus integration tests under every node DSA.\n")
        .append("- A node that does not enable a DSA: the network enables every account DSA, as the simulator needs.\n");
    Files.writeString(dir.resolve("security.md"), md.toString());

    String csv = "category,target,attack,expected,observed,as_expected,evidence\n" + results.stream()
        .map(r -> String.join(",", r.category(), q(r.target()), q(r.attack()), r.expected().name(), r.observed().name(),
            String.valueOf(r.asExpected()), q(r.evidence())))
        .collect(Collectors.joining("\n")) + "\n";
    Files.writeString(dir.resolve("security.csv"), csv);
  }

  private static String escape(String s) {
    return s == null ? "" : s.replace("|", "\\|");
  }

  private static String q(String s) {
    return s == null ? "" : "\"" + s.replace("\"", "\"\"") + "\"";
  }
}

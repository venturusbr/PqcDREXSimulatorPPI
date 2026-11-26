package br.org.venturus.drexsim.scenario;

import br.org.venturus.drexsim.scenario.FlowResult.Status;
import br.org.venturus.drexsim.scenario.FlowResult.TxStat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs {@link FlowSuite} for several signer profiles at once and reports a flow × DSA matrix:
 * pass/fail, transactions, gas used and bytes on the wire, with the overhead of each DSA relative to
 * the classical baseline.
 */
public final class FlowMatrix {

  private final Map<SignerProfile, List<FlowResult>> results;
  private final Instant startedAt;
  private final long millis;
  private final String rpcUrl;

  private FlowMatrix(Map<SignerProfile, List<FlowResult>> results, Instant startedAt, long millis, String rpcUrl) {
    this.results = results;
    this.startedAt = startedAt;
    this.millis = millis;
    this.rpcUrl = rpcUrl;
  }

  /** Runs the suite for each profile, {@code parallelism} profiles at a time. */
  public static FlowMatrix run(String rpcUrl, List<SignerProfile> profiles, int parallelism, Consumer<String> log)
      throws Exception {
    Instant started = Instant.now();
    long t0 = System.currentTimeMillis();
    ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(parallelism, profiles.size())));
    Map<SignerProfile, Future<List<FlowResult>>> futures = new LinkedHashMap<>();
    for (SignerProfile p : profiles) {
      futures.put(p, pool.submit(() -> new FlowSuite(rpcUrl, p, log).run()));
    }
    Map<SignerProfile, List<FlowResult>> results = new LinkedHashMap<>();
    try {
      for (Map.Entry<SignerProfile, Future<List<FlowResult>>> e : futures.entrySet()) {
        results.put(e.getKey(), e.getValue().get());
      }
    } finally {
      // Stopped or failed: interrupt the other profiles' flows and wait for them, so nothing still runs
      // (and logs) once this returns.
      pool.shutdownNow();
      boolean interrupted = Thread.interrupted();
      try {
        pool.awaitTermination(60, TimeUnit.SECONDS);
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
    return new FlowMatrix(results, started, System.currentTimeMillis() - t0, rpcUrl);
  }

  public Map<SignerProfile, List<FlowResult>> results() {
    return results;
  }

  public boolean allPassed() {
    return results.values().stream().flatMap(List::stream).allMatch(r -> r.status() == Status.PASS);
  }

  // ------------------------------------------------------------------ text

  /** Console table: one row per flow, one column per profile, cells {@code PASS 412k} (gas used). */
  public String renderText() {
    List<String> header = new ArrayList<>(List.of("#", "Flow"));
    results.keySet().forEach(p -> header.add(p.label()));
    List<List<String>> rows = new ArrayList<>();
    for (int f = 0; f < FlowSuite.FLOW_NAMES.size(); f++) {
      List<String> row = new ArrayList<>(List.of(String.valueOf(f), FlowSuite.FLOW_NAMES.get(f)));
      for (List<FlowResult> rs : results.values()) {
        FlowResult r = rs.get(f);
        row.add(switch (r.status()) {
          case PASS -> "PASS " + kgas(r.gasUsed());
          case FAIL -> "FAIL";
          case SKIPPED -> "skip";
        });
      }
      rows.add(row);
    }
    List<String> totals = new ArrayList<>(List.of("", "Passed / total gas"));
    for (List<FlowResult> rs : results.values()) {
      long passed = rs.stream().filter(r -> r.status() == Status.PASS).count();
      totals.add(passed + "/" + rs.size() + " " + kgas(rs.stream().mapToLong(FlowResult::gasUsed).sum()));
    }
    rows.add(totals);

    StringBuilder out = new StringBuilder(table(header, rows));
    for (Map.Entry<SignerProfile, List<FlowResult>> e : results.entrySet()) {
      for (FlowResult r : e.getValue()) {
        if (r.status() == Status.FAIL) {
          out.append(String.format("FAIL %s flow %d (%s): %s%n", e.getKey().label(), r.flow(), r.name(), r.detail()));
        }
      }
    }
    return out.toString();
  }

  // ------------------------------------------------------------------ files

  /** Writes {@code flows.md} and {@code flows.csv} (one line per transaction) into {@code dir}. */
  public void write(Path dir) throws IOException {
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("flows.md"), renderMarkdown());
    StringBuilder csv = new StringBuilder("profile,flow,flow_name,status,flow_millis,tx_hash,account,gas_used,tx_bytes,signature_bytes,public_key_bytes,tx_success,detail\n");
    for (List<FlowResult> rs : results.values()) {
      for (FlowResult r : rs) {
        List<TxStat> txs = r.transactions().isEmpty() ? List.of(new TxStat("", "", 0, 0, 0, 0, false)) : r.transactions();
        for (TxStat t : txs) {
          csv.append(String.join(",", q(r.profile()), String.valueOf(r.flow()), q(r.name()), r.status().name(),
              String.valueOf(r.millis()), t.hash(), q(t.account()), String.valueOf(t.gasUsed()), String.valueOf(t.size()),
              String.valueOf(t.signatureLength()), String.valueOf(t.publicKeyLength()), String.valueOf(t.success()),
              q(r.detail() == null ? "" : r.detail()))).append('\n');
        }
      }
    }
    Files.writeString(dir.resolve("flows.csv"), csv.toString());
  }

  public String renderMarkdown() {
    StringBuilder md = new StringBuilder();
    md.append("# Drex flows per DSA\n\n");
    md.append("Run at ").append(startedAt).append(" against `").append(rpcUrl).append("`, ")
        .append(String.format(Locale.ROOT, "%.1f", millis / 1000.0)).append(" s. Flows are those of ")
        .append("[docs/use-case-flows.md](../../docs/use-case-flows.md); every actor of a column signs with that DSA.\n\n");

    md.append("## Summary\n\n| Signer | Flows passed | Transactions | Gas used | Bytes on the wire | Avg. tx size |\n|---|---|---|---|---|---|\n");
    for (Map.Entry<SignerProfile, List<FlowResult>> e : results.entrySet()) {
      List<FlowResult> rs = e.getValue();
      long passed = rs.stream().filter(r -> r.status() == Status.PASS).count();
      long txs = rs.stream().mapToLong(FlowResult::txCount).sum();
      long gas = rs.stream().mapToLong(FlowResult::gasUsed).sum();
      long bytes = rs.stream().mapToLong(FlowResult::bytes).sum();
      md.append(String.format(Locale.ROOT, "| %s | %d/%d | %d | %,d | %,d | %,d B |%n", e.getKey().label(), passed, rs.size(),
          txs, gas, bytes, txs == 0 ? 0 : bytes / txs));
    }

    md.append("\n## Pass/fail and gas used per flow\n\n");
    md.append(markdownMatrix(r -> switch (r.status()) {
      case PASS -> String.format(Locale.ROOT, "✅ %,d", r.gasUsed());
      case FAIL -> "❌";
      case SKIPPED -> "skipped";
    }));

    SignerProfile baseline = results.keySet().stream().filter(SignerProfile::isBaseline).findFirst().orElse(null);
    if (baseline != null) {
      md.append("\n## Gas overhead relative to ").append(baseline.label()).append("\n\n");
      List<FlowResult> base = results.get(baseline);
      md.append(markdownMatrix(r -> {
        FlowResult b = base.get(r.flow());
        if (r.status() != Status.PASS || b.status() != Status.PASS || b.gasUsed() == 0) {
          return "–";
        }
        return String.format(Locale.ROOT, "%+.0f%%", 100.0 * (r.gasUsed() - b.gasUsed()) / b.gasUsed());
      }));
    }

    List<String> failures = new ArrayList<>();
    for (Map.Entry<SignerProfile, List<FlowResult>> e : results.entrySet()) {
      for (FlowResult r : e.getValue()) {
        if (r.status() == Status.FAIL) {
          failures.add("- **" + e.getKey().label() + "**, flow " + r.flow() + " (" + r.name() + "): " + r.detail());
        }
      }
    }
    md.append("\n## Failures\n\n").append(failures.isEmpty() ? "None.\n" : String.join("\n", failures) + "\n");
    return md.toString();
  }

  private String markdownMatrix(java.util.function.Function<FlowResult, String> cell) {
    StringBuilder md = new StringBuilder("| # | Flow |");
    results.keySet().forEach(p -> md.append(' ').append(p.label()).append(" |"));
    md.append("\n|---|---|").append("---|".repeat(results.size())).append('\n');
    for (int f = 0; f < FlowSuite.FLOW_NAMES.size(); f++) {
      md.append("| ").append(f).append(" | ").append(FlowSuite.FLOW_NAMES.get(f)).append(" |");
      for (List<FlowResult> rs : results.values()) {
        md.append(' ').append(cell.apply(rs.get(f))).append(" |");
      }
      md.append('\n');
    }
    return md.toString();
  }

  // ------------------------------------------------------------------ helpers

  private static String kgas(long gas) {
    return gas >= 10_000 ? (gas / 1000) + "k" : String.valueOf(gas);
  }

  private static String q(String s) {
    return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
  }

  private static String table(List<String> header, List<List<String>> rows) {
    int[] w = new int[header.size()];
    List<List<String>> all = new ArrayList<>();
    all.add(header);
    all.addAll(rows);
    for (List<String> r : all) {
      for (int i = 0; i < r.size(); i++) {
        w[i] = Math.max(w[i], r.get(i).length());
      }
    }
    StringBuilder out = new StringBuilder();
    for (int n = 0; n < all.size(); n++) {
      List<String> r = all.get(n);
      for (int i = 0; i < r.size(); i++) {
        out.append(i == 0 ? "" : " | ").append(String.format("%-" + w[i] + "s", r.get(i)));
      }
      out.append('\n');
      if (n == 0 || n == all.size() - 2) {
        for (int i = 0; i < w.length; i++) {
          out.append(i == 0 ? "" : "-+-").append("-".repeat(w[i]));
        }
        out.append('\n');
      }
    }
    return out.toString();
  }
}

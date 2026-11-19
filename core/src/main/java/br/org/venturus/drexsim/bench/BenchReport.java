package br.org.venturus.drexsim.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** CSV and Markdown reports of load-test levels, appended run after run into one directory. */
public final class BenchReport {

  private static final String HEADER = "profile,validators,block_gas_limit,target_tps,seconds,submitted,rejected,included,pending,"
      + "offered_tps,achieved_tps,latency_p50_ms,latency_p95_ms,latency_p99_ms,latency_max_ms,blocks,avg_tx_per_block,"
      + "max_tx_per_block,avg_block_fill_pct,avg_block_kb,avg_gas_per_tx,block_interval_s,gas_ceiling_tps,avg_tx_bytes,"
      + "avg_sign_ms,sampled_receipts,failed_sampled,avg_node_cpu_pct,max_node_mem_mb,sustained,top_rejection";

  private BenchReport() {}

  /** Appends the levels to {@code bench.csv} in {@code dir} and rewrites {@code bench.md} from the whole CSV. */
  public static void append(Path dir, List<LevelResult> levels) throws IOException {
    Files.createDirectories(dir);
    Path csv = dir.resolve("bench.csv");
    StringBuilder out = new StringBuilder(Files.exists(csv) ? "" : HEADER + "\n");
    for (LevelResult r : levels) {
      out.append(String.format(Locale.ROOT,
          "%s,%d,%d,%.1f,%d,%d,%d,%d,%d,%.2f,%.2f,%d,%d,%d,%d,%d,%.2f,%d,%.1f,%.1f,%.0f,%.2f,%.1f,%.0f,%.3f,%d,%d,%.1f,%.0f,%s,\"%s\"%n",
          r.profile(), r.validators(), r.blockGasLimit(), r.targetTps(), r.seconds(), r.submitted(), r.rejected(), r.included(), r.pending(),
          r.offeredTps(), r.achievedTps(), r.latencyP50Ms(), r.latencyP95Ms(), r.latencyP99Ms(), r.latencyMaxMs(),
          r.blocks(), r.avgTxPerBlock(), r.maxTxPerBlock(), r.avgBlockFillPct(), r.avgBlockKb(), r.avgGasPerTx(),
          r.blockIntervalS(), r.gasCeilingTps(), r.avgTxBytes(), r.avgSignMs(), r.sampledReceipts(), r.failedSampled(),
          r.avgNodeCpuPct(), r.maxNodeMemMb(), r.sustained(), r.topRejection().replace("\"", "'")));
    }
    Files.writeString(csv, out.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    Files.writeString(dir.resolve("bench.md"), markdown(Files.readAllLines(csv)));
  }

  static String markdown(List<String> csvLines) {
    String[] header = csvLines.get(0).split(",");
    Map<String, Integer> col = new LinkedHashMap<>();
    for (int i = 0; i < header.length; i++) {
      col.put(header[i], i);
    }
    List<Map<String, String>> rows = csvLines.stream().skip(1).filter(l -> !l.isBlank()).map(l -> {
      String[] f = l.split(",", header.length);
      Map<String, String> row = new LinkedHashMap<>();
      col.forEach((name, i) -> row.put(name, i < f.length ? f[i] : ""));
      return row;
    }).collect(Collectors.toList());

    StringBuilder md = new StringBuilder("# Load tests\n\nUpdated ").append(Instant.now()).append(". Workload: Drex clients ")
        .append("paying each other, same-bank Real Tokenizado transfers and inter-bank SwapOneStep swaps. Latency is from ")
        .append("submission to the block that includes the transaction. A level is *sustained* when the network ")
        .append("included at least 90% of the offered rate with little backlog. The gas-bound ceiling is the block gas ")
        .append("limit divided by the gas per transaction and the block interval.\n\n");

    md.append("## Highest sustained rate\n\n| Signer | Validators | Block gas limit | Highest sustained target (TPS) | Included TPS | p95 latency | Gas per tx | Gas-bound ceiling (TPS) | Avg tx size |\n|---|---|---|---|---|---|---|---|---|\n");
    Map<String, Map<String, String>> best = new LinkedHashMap<>();
    for (Map<String, String> r : rows) {
      String key = r.get("profile") + "|" + r.get("validators") + "|" + r.get("block_gas_limit");
      best.putIfAbsent(key, null);
      if ("true".equals(r.get("sustained")) && (best.get(key) == null
          || Double.parseDouble(r.get("target_tps")) > Double.parseDouble(best.get(key).get("target_tps")))) {
        best.put(key, r);
      }
    }
    best.forEach((key, r) -> {
      String[] k = key.split("\\|");
      md.append(r == null
          ? String.format("| %s | %s | %s | none of the levels run | | | | | |%n", k[0], k[1], millions(k[2]))
          : String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s ms | %,.0f | %s | %s B |%n", r.get("profile"),
              r.get("validators"), millions(r.get("block_gas_limit")), trim(r.get("target_tps")), r.get("achieved_tps"),
              r.get("latency_p95_ms"), Double.parseDouble(r.get("avg_gas_per_tx")), r.get("gas_ceiling_tps"),
              r.get("avg_tx_bytes")));
    });

    md.append("\n## All levels\n\n| Signer | Val. | Gas limit | Target | Offered | Included | p50 | p95 | p99 | Tx/block (max) | Block fill | Block size | Backlog | Rejected | Node CPU | Node mem | Sustained |\n")
        .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (Map<String, String> r : rows) {
      md.append(String.format("| %s | %s | %s | %s | %s | %s | %s ms | %s ms | %s ms | %s (%s) | %s%% | %s KB | %s | %s | %s | %s | %s |%n",
          r.get("profile"), r.get("validators"), millions(r.get("block_gas_limit")), trim(r.get("target_tps")),
          r.get("offered_tps"), r.get("achieved_tps"), r.get("latency_p50_ms"), r.get("latency_p95_ms"),
          r.get("latency_p99_ms"), r.get("avg_tx_per_block"), r.get("max_tx_per_block"), r.get("avg_block_fill_pct"),
          r.get("avg_block_kb"), r.get("pending"), r.get("rejected"),
          r.get("avg_node_cpu_pct").startsWith("-") ? "n/a" : r.get("avg_node_cpu_pct") + "%",
          r.get("max_node_mem_mb").startsWith("-") ? "n/a" : r.get("max_node_mem_mb") + " MB",
          "true".equals(r.get("sustained")) ? "yes" : "**no**"));
    }
    List<String> rejections = rows.stream().filter(r -> r.get("top_rejection").length() > 2)
        .map(r -> "- " + r.get("profile") + ", " + r.get("validators") + " validators, " + trim(r.get("target_tps")) + " TPS: "
            + r.get("top_rejection").replace("\"", ""))
        .collect(Collectors.toList());
    if (!rejections.isEmpty()) {
      md.append("\n## Most frequent rejection per level\n\n").append(String.join("\n", rejections)).append('\n');
    }
    md.append("\nNode CPU is the average over the validator containers (100% = one core); all nodes and the load ")
        .append("generator share one machine, so absolute numbers depend on it. Compare the signers with each other.\n");
    return md.toString();
  }

  private static String millions(String gas) {
    try {
      return String.format(Locale.ROOT, "%.0fM", Long.parseLong(gas) / 1e6);
    } catch (NumberFormatException e) {
      return gas;
    }
  }

  private static String trim(String number) {
    return number.endsWith(".0") ? number.substring(0, number.length() - 2) : number;
  }
}

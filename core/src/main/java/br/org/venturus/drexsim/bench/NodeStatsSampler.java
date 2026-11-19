package br.org.venturus.drexsim.bench;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Samples CPU and memory of the validator containers ({@code node-N}) with {@code docker stats}
 * while a level runs. Unavailable (no Docker, remote network) means no samples, reported as -1.
 */
final class NodeStatsSampler implements Runnable {

  private final List<double[]> samples = new ArrayList<>(); // {avg cpu % over nodes, max mem MB}
  private volatile boolean running = true;

  @Override
  public void run() {
    while (running) {
      try {
        Process p = new ProcessBuilder("docker", "stats", "--no-stream", "--format", "{{.Name}};{{.CPUPerc}};{{.MemUsage}}")
            .redirectErrorStream(true).start();
        double cpuSum = 0;
        double memMax = 0;
        int nodes = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
          String line;
          while ((line = r.readLine()) != null) {
            String[] f = line.split(";");
            if (f.length < 3 || !f[0].matches("node-\\d+")) {
              continue;
            }
            cpuSum += Double.parseDouble(f[1].replace("%", "").trim());
            memMax = Math.max(memMax, megabytes(f[2].split("/")[0].trim()));
            nodes++;
          }
        }
        p.waitFor(10, TimeUnit.SECONDS);
        if (nodes > 0) {
          synchronized (samples) {
            samples.add(new double[] {cpuSum / nodes, memMax});
          }
        }
      } catch (Exception e) {
        return; // no Docker here: stop sampling
      }
    }
  }

  private static double megabytes(String usage) {
    String u = usage.toLowerCase(Locale.ROOT);
    double value = Double.parseDouble(u.replaceAll("[^0-9.]", ""));
    if (u.endsWith("gib")) {
      return value * 1024;
    }
    if (u.endsWith("kib")) {
      return value / 1024;
    }
    return value; // MiB
  }

  void stop() {
    running = false;
  }

  double avgCpu() {
    synchronized (samples) {
      return samples.isEmpty() ? -1 : samples.stream().mapToDouble(s -> s[0]).average().orElse(-1);
    }
  }

  double maxMemMb() {
    synchronized (samples) {
      return samples.isEmpty() ? -1 : samples.stream().mapToDouble(s -> s[1]).max().orElse(-1);
    }
  }
}

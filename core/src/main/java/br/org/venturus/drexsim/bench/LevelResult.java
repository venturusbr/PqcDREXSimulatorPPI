package br.org.venturus.drexsim.bench;

/**
 * Result of one load level: a target submission rate held for a fixed time.
 *
 * @param targetTps submission rate asked for
 * @param offeredTps rate the generator actually submitted at
 * @param achievedTps transactions included per second: included transactions over the time from the first
 *     block that included one of them to the last, or over the sending time when that is longer
 * @param latencyP50Ms submission to inclusion (block observed), 50th percentile
 * @param pending submitted and accepted, but not included when the level ended (backlog)
 * @param failedSampled receipts with status 0 among {@code sampledReceipts}
 * @param avgBlockFillPct block gas used over block gas limit, average of the level's blocks
 * @param avgGasPerTx gas used per included transaction (operation mix)
 * @param gasCeilingTps most transactions per second the block gas limit allows for this mix:
 *     {@code blockGasLimit / avgGasPerTx / blockInterval}
 * @param avgNodeCpuPct average CPU of the validator containers (100 = one core), or -1 when unavailable
 * @param maxNodeMemMb highest memory of any validator container, or -1 when unavailable
 */
public record LevelResult(
    String profile,
    int validators,
    long blockGasLimit,
    double targetTps,
    int seconds,
    long submitted,
    long rejected,
    long included,
    long pending,
    double offeredTps,
    double achievedTps,
    long latencyP50Ms,
    long latencyP95Ms,
    long latencyP99Ms,
    long latencyMaxMs,
    int blocks,
    double avgTxPerBlock,
    int maxTxPerBlock,
    double avgBlockFillPct,
    double avgBlockKb,
    double avgGasPerTx,
    double blockIntervalS,
    double gasCeilingTps,
    double avgTxBytes,
    double avgSignMs,
    int sampledReceipts,
    int failedSampled,
    double avgNodeCpuPct,
    double maxNodeMemMb,
    String topRejection) {

  /**
   * The network kept up: something was sent, little was rejected or left as backlog, and the included rate
   * reached 90% of the offered one, allowing one block interval more than the level's duration. Inclusion
   * is counted in whole blocks, so a level that straddles one more block boundary, or meets one late block,
   * is not saturation; on short levels that interval is a large share, so they detect saturation less finely.
   */
  public boolean sustained() {
    long tolerance = Math.max(5, submitted / 20);
    double slack = seconds <= 0 ? 1 : seconds / (seconds + Math.max(0, blockIntervalS));
    return submitted > 0 && included > 0 && pending <= tolerance && rejected <= tolerance
        && achievedTps >= 0.9 * offeredTps * slack;
  }
}

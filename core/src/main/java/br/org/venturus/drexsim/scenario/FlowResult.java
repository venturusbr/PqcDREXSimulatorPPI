package br.org.venturus.drexsim.scenario;

import java.util.List;

/** Outcome of one use-case flow under one signer profile. */
public record FlowResult(
    String profile, int flow, String name, Status status, String detail, List<TxStat> transactions, long millis) {

  public enum Status {
    PASS,
    FAIL,
    /** Not run because a flow it depends on failed. */
    SKIPPED
  }

  /** One transaction the flow sent, with its receipt. */
  public record TxStat(String hash, String account, long gasUsed, int size, int signatureLength, int publicKeyLength,
      boolean success) {}

  public long gasUsed() {
    return transactions.stream().mapToLong(TxStat::gasUsed).sum();
  }

  public long bytes() {
    return transactions.stream().mapToLong(TxStat::size).sum();
  }

  public int txCount() {
    return transactions.size();
  }
}

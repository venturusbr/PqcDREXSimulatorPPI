package br.org.venturus.drexsim.tx;

/** How an account's transactions go on the wire. */
public enum TxFormat {
  /** besu-pqc-ppi flexible EIP-1559 ({@code 0x42}): any DSA, signature as one byte string. */
  FLEXIBLE,
  /** Standard EIP-1559 ({@code 0x02}) with {@code yParity, r, s}: ECDSA only. The classical baseline. */
  STANDARD
}

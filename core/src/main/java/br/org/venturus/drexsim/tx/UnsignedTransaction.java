package br.org.venturus.drexsim.tx;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Fields shared by flexible ({@code 0x42}) and standard ({@code 0x02}) EIP-1559 transactions.
 * The access list is always empty.
 *
 * @param to recipient, or {@code null} for a contract creation
 * @param data calldata (ABI-encoded call, or init code for a creation); never hex text
 */
public record UnsignedTransaction(
    long chainId,
    BigInteger nonce,
    BigInteger maxPriorityFeePerGas,
    BigInteger maxFeePerGas,
    BigInteger gasLimit,
    String to,
    BigInteger value,
    byte[] data) {

  public UnsignedTransaction {
    Objects.requireNonNull(nonce, "nonce");
    Objects.requireNonNull(maxPriorityFeePerGas, "maxPriorityFeePerGas");
    Objects.requireNonNull(maxFeePerGas, "maxFeePerGas");
    Objects.requireNonNull(gasLimit, "gasLimit");
    value = value == null ? BigInteger.ZERO : value;
    data = data == null ? new byte[0] : data;
    to = to == null || to.isEmpty() || to.equals("0x") ? null : to;
  }

  public boolean isContractCreation() {
    return to == null;
  }
}

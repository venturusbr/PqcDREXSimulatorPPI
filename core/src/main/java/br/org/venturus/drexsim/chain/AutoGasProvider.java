package br.org.venturus.drexsim.chain;

import java.math.BigInteger;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.tx.gas.ContractEIP1559GasProvider;

/**
 * Gas provider for the generated contract wrappers on the besu-qbft network, where gas is free
 * ({@code zeroBaseFee}, {@code min-gas-price=0}).
 *
 * <p>It returns a gas limit of 0, which tells {@link PqcTransactionManager} to estimate it: the
 * wrappers cannot know the extra intrinsic gas a post-quantum signature costs.
 */
public final class AutoGasProvider implements ContractEIP1559GasProvider {

  /** Gas limit value meaning "estimate it". */
  public static final BigInteger AUTO = BigInteger.ZERO;

  private final long chainId;
  private final BigInteger maxFeePerGas;
  private final BigInteger maxPriorityFeePerGas;

  public AutoGasProvider(long chainId) {
    this(chainId, BigInteger.ZERO, BigInteger.ZERO);
  }

  public AutoGasProvider(long chainId, BigInteger maxFeePerGas, BigInteger maxPriorityFeePerGas) {
    this.chainId = chainId;
    this.maxFeePerGas = maxFeePerGas;
    this.maxPriorityFeePerGas = maxPriorityFeePerGas;
  }

  @Override
  public long getChainId() {
    return chainId;
  }

  @Override
  public BigInteger getMaxFeePerGas() {
    return maxFeePerGas;
  }

  @Override
  public BigInteger getMaxPriorityFeePerGas() {
    return maxPriorityFeePerGas;
  }

  @Override
  public BigInteger getGasPrice() {
    return maxFeePerGas;
  }

  @Override
  public BigInteger getGasLimit(Transaction transaction) {
    return AUTO;
  }

  @Override
  public BigInteger getGasLimit() {
    return AUTO;
  }
}

package br.org.venturus.drexsim.chain;

import br.org.venturus.drexsim.keystore.Account;
import br.org.venturus.drexsim.tx.SignedTransaction;
import br.org.venturus.drexsim.tx.TransactionSigner;
import br.org.venturus.drexsim.tx.TxFormat;
import br.org.venturus.drexsim.tx.UnsignedTransaction;
import java.io.IOException;
import java.math.BigInteger;
import java.util.function.Consumer;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthGetCode;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.exceptions.ContractCallException;
import org.web3j.utils.Numeric;

/**
 * web3j {@link TransactionManager} that signs with an {@link Account}'s DSA, so the generated
 * contract wrappers send post-quantum (flexible, {@code 0x42}) or classical (standard, {@code
 * 0x02}) transactions without knowing the difference.
 *
 * <p>besu-pqc-ppi has no {@code eth_sendTransaction} for these accounts: every transaction is signed
 * here and sent with {@code eth_sendRawTransaction}. Gas limits of 0 ({@link AutoGasProvider}) are
 * estimated, adding the shortfall of {@code eth_estimateGas}, which simulates a standard
 * transaction and so misses the extra base cost of the DSA (docs/pqc/07-json-rpc-api.md).
 */
public final class PqcTransactionManager extends TransactionManager {

  /** Extra gas on top of the estimate, in percent. State can change between estimate and inclusion. */
  private static final int GAS_MARGIN_PERCENT = 20;

  private final Web3j web3j;
  private final Account account;
  private final long chainId;
  private final NonceManager nonces;
  private final Consumer<SentTransaction> listener;

  public PqcTransactionManager(
      Web3j web3j, Account account, long chainId, NonceManager nonces, Consumer<SentTransaction> listener) {
    // Poll for the receipt every 500 ms for up to 2 minutes; QBFT blocks come every 2 s.
    super(web3j, 240, 500, account.address());
    this.web3j = web3j;
    this.account = account;
    this.chainId = chainId;
    this.nonces = nonces;
    this.listener = listener == null ? tx -> {} : listener;
  }

  public Account account() {
    return account;
  }

  @Override
  public EthSendTransaction sendTransaction(
      BigInteger gasPrice, BigInteger gasLimit, String to, String data, BigInteger value, boolean constructor)
      throws IOException {
    return send(gasPrice, gasPrice, gasLimit, to, data, value);
  }

  @Override
  public EthSendTransaction sendEIP1559Transaction(
      long chainId,
      BigInteger maxPriorityFeePerGas,
      BigInteger maxFeePerGas,
      BigInteger gasLimit,
      String to,
      String data,
      BigInteger value,
      boolean constructor)
      throws IOException {
    return send(maxPriorityFeePerGas, maxFeePerGas, gasLimit, to, data, value);
  }

  private EthSendTransaction send(
      BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas, BigInteger gasLimit, String to, String data, BigInteger value)
      throws IOException {
    String recipient = to == null || to.isEmpty() ? null : to;
    BigInteger amount = value == null ? BigInteger.ZERO : value;
    if (gasLimit == null || gasLimit.signum() <= 0) {
      EthEstimateGas estimate =
          web3j.ethEstimateGas(new Transaction(account.address(), null, null, null, recipient, amount, data)).send();
      if (estimate.hasError()) {
        // Usually a revert: report it like a rejected submission so web3j raises it with the reason.
        return failed(estimate.getError(), "eth_estimateGas: ");
      }
      gasLimit = gasFor(estimate.getAmountUsed());
    }

    BigInteger nonce = nonces.next(account.address());
    UnsignedTransaction tx =
        new UnsignedTransaction(
            chainId,
            nonce,
            maxPriorityFeePerGas == null ? BigInteger.ZERO : maxPriorityFeePerGas,
            maxFeePerGas == null ? BigInteger.ZERO : maxFeePerGas,
            gasLimit,
            recipient,
            amount,
            Numeric.hexStringToByteArray(data == null ? "0x" : data));

    long t0 = System.nanoTime();
    SignedTransaction signed = TransactionSigner.sign(tx, account);
    long t1 = System.nanoTime();
    EthSendTransaction response = web3j.ethSendRawTransaction(signed.rawHex()).send();
    long t2 = System.nanoTime();

    String error = response.hasError() ? response.getError().getMessage() : null;
    if (error != null) {
      nonces.resync(account.address()); // the nonce was not consumed
    }
    listener.accept(
        new SentTransaction(
            account.name(),
            account.address(),
            recipient,
            account.dsa(),
            account.format(),
            nonce,
            gasLimit,
            signed.hash(),
            signed.size(),
            signed.signatureLength(),
            signed.publicKeyLength(),
            t1 - t0,
            t2 - t1,
            error));
    return response;
  }

  /** Gas limit for an {@code eth_estimateGas} result: shortfall of the DSA plus a safety margin. */
  BigInteger gasFor(BigInteger estimate) {
    long shortfall = account.format() == TxFormat.FLEXIBLE ? account.dsa().estimateGasShortfall() : 0;
    return estimate.add(BigInteger.valueOf(shortfall))
        .multiply(BigInteger.valueOf(100 + GAS_MARGIN_PERCENT))
        .divide(BigInteger.valueOf(100));
  }

  private static EthSendTransaction failed(Response.Error error, String prefix) {
    EthSendTransaction response = new EthSendTransaction();
    response.setError(new Response.Error(error.getCode(), prefix + error.getMessage()));
    return response;
  }

  @Override
  public String sendCall(String to, String data, DefaultBlockParameter defaultBlockParameter) throws IOException {
    EthCall call =
        web3j.ethCall(Transaction.createEthCallTransaction(account.address(), to, data), defaultBlockParameter).send();
    if (call.hasError()) {
      throw new ContractCallException(call.getError().getMessage());
    }
    if (call.isReverted()) {
      throw new ContractCallException(call.getRevertReason());
    }
    return call.getValue();
  }

  @Override
  public EthGetCode getCode(String contractAddress, DefaultBlockParameter defaultBlockParameter) throws IOException {
    return web3j.ethGetCode(contractAddress, defaultBlockParameter).send();
  }
}

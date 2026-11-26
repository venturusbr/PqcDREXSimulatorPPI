package br.org.venturus.drexsim.drex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.exceptions.TransactionException;

/**
 * Issue #13: a refused SwapOneStep payment sets the payer's allowance back, unless the swap may still be
 * included, which the allowance must then cover.
 */
class SwapAllowanceTest {

  @Test
  void aRefusedSwapIsOver() {
    // web3j turns a refusal by eth_estimateGas, or by the node, into a TransactionException with no hash.
    assertFalse(DrexClient.mayStillBeIncluded(new TransactionException(
        "JsonRpcError thrown with code 3. Message: eth_estimateGas: Execution reverted (Recipient is not enabled)")));
    TransactionReceipt reverted = new TransactionReceipt();
    reverted.setStatus("0x0");
    assertFalse(DrexClient.mayStillBeIncluded(new TransactionException("Transaction has failed", reverted)));
    assertFalse(DrexClient.mayStillBeIncluded(new IllegalStateException("RealDigital: insufficient balance")));
  }

  @Test
  void aSwapWithoutAnAnswerMayStillBeIncluded() {
    assertTrue(DrexClient.mayStillBeIncluded(new TransactionException(
        "Transaction receipt was not generated after 600 seconds", "0xabc")));
    assertTrue(DrexClient.mayStillBeIncluded(new SocketTimeoutException("Read timed out")));
    assertTrue(DrexClient.mayStillBeIncluded(new IOException("Connection reset")));
  }
}

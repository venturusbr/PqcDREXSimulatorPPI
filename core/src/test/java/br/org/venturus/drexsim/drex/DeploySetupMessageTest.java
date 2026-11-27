package br.org.venturus.drexsim.drex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.web3j.protocol.exceptions.TransactionException;

/**
 * A Real Tokenizado whose setup stopped halfway: the error keeps its address and how to finish, whatever
 * the cause, and holds no raw revert for the GUI to shorten.
 */
class DeploySetupMessageTest {

  private static final String ADDRESS = "0x078eb7bbbd0f506b1aa61cff68eebeac7fd51940";
  private static final String START = "Real Tokenizado BRLC was deployed at " + ADDRESS
      + ", but its setup did not finish, so run this again with the same values to finish it. Cause: ";

  @Test
  void aRefusedStepGivesTheContractsReason() {
    String message = DrexClient.setupDidNotFinish("BRLC", ADDRESS, new RuntimeException("wrapper",
        new TransactionException("JsonRpcError thrown with code 3. Message: eth_estimateGas: Execution reverted "
            + "(AccessControl: account is missing role)")));
    assertEquals(START + "the contract refused a step: AccessControl: account is missing role", message);
    assertFalse(message.toLowerCase().contains("reverted"), message);
  }

  @Test
  void anotherFailureKeepsItsMessage() {
    assertEquals(START + "Read timed out",
        DrexClient.setupDidNotFinish("BRLC", ADDRESS, new java.net.SocketTimeoutException("Read timed out")));
  }
}

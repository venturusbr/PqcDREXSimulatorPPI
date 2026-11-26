package br.org.venturus.drexsim.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.web3j.protocol.exceptions.TransactionException;

class RootMessageTest {

  @Test
  void aRevertIsReducedToItsReason() {
    assertEquals("Refused by the contract: Recipient is not enabled", SimulatorService.rootMessage(new TransactionException(
        "JsonRpcError thrown with code 3. Message: eth_estimateGas: Execution reverted (Recipient is not enabled)")));
  }

  @Test
  void aMessageOfTheSimulatorIsKeptWhole() {
    // DrexClient.deployRealTokenizado, when the setup of a deployed token stopped halfway.
    String halfway = "Real Tokenizado BRLC was deployed at 0x078eb7bbbd0f506b1aa61cff68eebeac7fd51940, but its setup "
        + "did not finish, so run this again with the same values to finish it. Cause: the contract refused a step: "
        + "AccessControl: account is missing role";
    assertEquals(halfway, SimulatorService.rootMessage(new IllegalStateException(halfway)));
  }
}

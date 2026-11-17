package br.org.venturus.drexsim.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OperationsTest {

  private static final Set<String> FIELD_TYPES = Set.of("account", "wallet", "token", "rt", "amount", "text", "number");

  @Test
  void everyOperationHasAUniqueIdAFlowAndKnownFieldTypes() {
    Set<String> ids = new HashSet<>();
    for (SimulatorService.Operation op : SimulatorService.OPERATIONS) {
      assertTrue(ids.add(op.id()), "duplicate id " + op.id());
      assertTrue(op.flow() >= 0 && op.flow() <= 18, op.id() + " flow " + op.flow());
      for (SimulatorService.Field f : op.fields()) {
        assertTrue(FIELD_TYPES.contains(f.type()), op.id() + "." + f.name() + " has type " + f.type());
      }
    }
  }

  @Test
  void coversTheClientFacingFlows() {
    List<Integer> flows = SimulatorService.OPERATIONS.stream().map(SimulatorService.Operation::flow).toList();
    for (int flow : List.of(4, 5, 6, 9, 10, 11, 12, 15, 16, 17)) {
      assertTrue(flows.contains(flow), "flow " + flow + " has an operation");
    }
  }

  @Test
  void reducesContractRevertsToTheirReason() {
    assertEquals("Refused by the contract: RealDigital: insufficient balance", SimulatorService.rootMessage(new RuntimeException(
        "JsonRpcError thrown with code 3. Message: eth_estimateGas: Execution reverted (RealDigital: insufficient balance)")));
    assertEquals("Wrong chainId", SimulatorService.rootMessage(new RuntimeException("JsonRpcError thrown with code -32000. Message: Wrong chainId")));
  }

  @Test
  void ships_the_page() {
    assertNotNull(getClass().getResource("/public/index.html"));
    assertNotNull(getClass().getResource("/public/app.js"));
    assertNotNull(getClass().getResource("/public/app.css"));
    assertNotNull(getClass().getResource("/public/drex-logo.svg"));
    assertNotNull(getClass().getResource("/public/favicon.ico"));
    assertEquals(16, SimulatorService.OPERATIONS.size());
  }
}

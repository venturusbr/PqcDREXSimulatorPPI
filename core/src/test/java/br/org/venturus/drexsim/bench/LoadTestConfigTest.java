package br.org.venturus.drexsim.bench;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.org.venturus.drexsim.bench.LoadTest.Config;
import br.org.venturus.drexsim.scenario.SignerProfile;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoadTestConfigTest {

  private static final SignerProfile PROFILE = SignerProfile.parse("SECP256K1-MLDSA44");
  private static final List<String> NODES = List.of("http://localhost:8545", "https://node-2.example:8546");

  private static Config config(List<String> nodes, int clients, List<Double> rates, int seconds) {
    return new Config(nodes, PROFILE, clients, rates, seconds, 0.3, 30, 16);
  }

  private static void assertRefused(String fragment, Runnable build) {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, build::run);
    assertTrue(e.getMessage().contains(fragment), e.getMessage());
  }

  @Test
  void acceptsTheDefaultsAndTheBounds() {
    assertDoesNotThrow(() -> config(NODES, 20, List.of(25.0, 50.0, 100.0), 20));
    assertDoesNotThrow(() -> config(NODES, Config.MIN_CLIENTS, List.of(0.2), Config.MIN_SECONDS)); // one transaction
    assertDoesNotThrow(() -> config(NODES, Config.MAX_CLIENTS, List.of(Config.MAX_RATE), Config.MAX_SECONDS));
  }

  @Test
  void refusesRatesThatSendNothingOrTooMuch() {
    assertRefused("No rate", () -> config(NODES, 20, List.of(), 20));
    assertRefused("above 0", () -> config(NODES, 20, List.of(25.0, 0.0), 20));
    assertRefused("above 0", () -> config(NODES, 20, List.of(-5.0), 20));
    assertRefused("above 0", () -> config(NODES, 20, List.of(Double.NaN), 20));
    assertRefused("above 0", () -> config(NODES, 20, Arrays.asList(25.0, null), 20));
    assertRefused("above 0", () -> config(NODES, 20, List.of(Config.MAX_RATE + 1), 20));
    assertRefused("send no transaction", () -> config(NODES, 20, List.of(0.05), 5));
  }

  @Test
  void refusesSecondsAndClientsOutOfRange() {
    assertRefused("Seconds per rate", () -> config(NODES, 20, List.of(25.0), 0));
    assertRefused("Seconds per rate", () -> config(NODES, 20, List.of(25.0), Config.MAX_SECONDS + 1));
    assertRefused("Clients per bank", () -> config(NODES, 1, List.of(25.0), 20));
    assertRefused("Clients per bank", () -> config(NODES, Config.MAX_CLIENTS + 1, List.of(25.0), 20));
  }

  @Test
  void refusesMalformedNodeUrls() {
    assertRefused("No node", () -> config(List.of(), 20, List.of(25.0), 20));
    assertRefused("abc", () -> config(List.of("abc"), 20, List.of(25.0), 20));
    assertRefused("ws://", () -> config(List.of("http://localhost:8545", "ws://localhost:8546"), 20, List.of(25.0), 20));
    assertRefused("http://", () -> config(List.of("http://"), 20, List.of(25.0), 20));
  }
}

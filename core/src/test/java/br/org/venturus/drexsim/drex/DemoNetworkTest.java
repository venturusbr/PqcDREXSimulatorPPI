package br.org.venturus.drexsim.drex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.org.venturus.drexsim.chain.Chain;
import br.org.venturus.drexsim.chain.SentTransaction;
import br.org.venturus.drexsim.drex.BalanceService.Snapshot;
import br.org.venturus.drexsim.drex.BalanceService.Wallet;
import br.org.venturus.drexsim.keystore.Account;
import br.org.venturus.drexsim.keystore.AccountStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * End-to-end run of {@link DemoSetup} on a live besu-pqc-ppi network, signing with every DSA.
 * Skipped unless {@code -Ddrexsim.rpc=http://localhost:8545} is given.
 */
class DemoNetworkTest {

  @Test
  void runsTheDemoAndReportsTheExpectedBalances() throws Exception {
    String rpc = System.getProperty("drexsim.rpc", "");
    Assumptions.assumeTrue(rpc.startsWith("http"), "set -Ddrexsim.rpc to run against a network");

    Path dataDir = Files.createTempDirectory("drexsim-it");
    try (Chain chain = new Chain(rpc)) {
      List<SentTransaction> sent = new ArrayList<>();
      chain.addListener(sent::add);
      AccountStore store = AccountStore.open(dataDir);
      DrexDeployment deployment = new DemoSetup(chain, store, dataDir, System.out::println).run();

      List<Wallet> wallets = new ArrayList<>();
      for (Account a : store.all()) {
        wallets.add(new Wallet(a.name(), a.address(), a.signingLabel()));
      }
      Snapshot s = new BalanceService(chain, deployment).snapshot(wallets);
      System.out.print(BalanceTable.render(s, false));

      assertEquals("998.865,44", rd(s, "reserve-a"));
      assertEquals("1.001.134,56", rd(s, "reserve-b"));
      assertEquals("50.000,00", rd(s, "bank-a"));
      assertEquals("9.165,44", rt(s, "ana", "BRLA"));
      assertEquals("2.200,00", rt(s, "bruno", "BRLA"));
      assertEquals("500,00", Amounts.format(row(s, "bruno").of("BRLA").frozen()));
      assertEquals("8.134,56", rt(s, "carla", "BRLB"));
      assertEquals("800,00", rt(s, "daniel", "BRLA"));
      assertEquals("1.000,00", rt(s, "daniel", "BRLB"));
      assertEquals("200,00", rt(s, "eva", "BRLA"));
      assertEquals("800,00", rt(s, "fabio", "BRLB"));

      // Which bank each client is at, as the contracts say (the GUI's Bank column).
      assertEquals(List.of("BRLA"), row(s, "ana").roles().clientOf());
      assertEquals(List.of("BRLA", "BRLB"), row(s, "daniel").roles().clientOf());
      assertEquals(List.of(), row(s, "eva").roles().clientOf());
      assertEquals(List.of(), row(s, "fabio").roles().clientOf());
      assertEquals(List.of("BRLB"), row(s, "reserve-b").roles().reserveOf());
      assertEquals(List.of("bank-a", "bank-b"),
          s.tokens().subList(1, 3).stream().map(BalanceService.Token::issuer).toList());

      // Every DSA of the demo really went on chain.
      Set<String> signers = sent.stream().filter(SentTransaction::accepted)
          .map(t -> t.dsa().wireName() + "/" + t.format()).collect(Collectors.toSet());
      assertEquals(
          DemoSetup.ACTORS.stream().map(a -> a.dsa().wireName() + "/" + a.format()).collect(Collectors.toSet()),
          signers);
    }
  }

  private static BalanceService.WalletBalances row(Snapshot s, String name) {
    return s.wallets().stream().filter(w -> w.wallet().label().equals(name)).findFirst().orElseThrow();
  }

  private static String rd(Snapshot s, String name) {
    return Amounts.format(row(s, name).of(BalanceService.RD).balance());
  }

  private static String rt(Snapshot s, String name, String symbol) {
    return Amounts.format(row(s, name).of(symbol).balance());
  }
}

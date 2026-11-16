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

      assertEquals("998.765,44", rd(s, "reserve-a"));
      assertEquals("1.001.234,56", rd(s, "reserve-b"));
      assertEquals("50.000,00", rd(s, "bank-a"));
      assertEquals("8.765,44", rt(s, "client-a1", "BRLA"));
      assertEquals("2.500,00", rt(s, "client-a2", "BRLA"));
      assertEquals("500,00", Amounts.format(row(s, "client-a2").of("BRLA").frozen()));
      assertEquals("8.234,56", rt(s, "client-b1", "BRLB"));

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

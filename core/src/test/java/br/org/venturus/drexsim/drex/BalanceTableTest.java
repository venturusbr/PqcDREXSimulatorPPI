package br.org.venturus.drexsim.drex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.org.venturus.drexsim.drex.BalanceService.Holding;
import br.org.venturus.drexsim.drex.BalanceService.Snapshot;
import br.org.venturus.drexsim.drex.BalanceService.Token;
import br.org.venturus.drexsim.drex.BalanceService.Wallet;
import br.org.venturus.drexsim.drex.BalanceService.WalletBalances;
import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BalanceTableTest {

  private static BigInteger c(long cents) {
    return BigInteger.valueOf(cents);
  }

  private static Snapshot snapshot() {
    List<Token> tokens = List.of(
        new Token("BRL", "Real Digital", "0x01"),
        new Token("BRLA", "Real Tokenizado Banco A", "0x02"));
    Map<String, Holding> reserve = new LinkedHashMap<>();
    reserve.put("BRL", new Holding(c(100_000_000), c(0)));
    reserve.put("BRLA", Holding.ZERO);
    Map<String, Holding> client = new LinkedHashMap<>();
    client.put("BRL", Holding.ZERO);
    client.put("BRLA", new Holding(c(250_000), c(50_000)));
    Map<String, Holding> empty = new LinkedHashMap<>();
    empty.put("BRL", Holding.ZERO);
    empty.put("BRLA", Holding.ZERO);
    return new Snapshot(
        42,
        Instant.parse("2026-11-16T12:00:00Z"),
        tokens,
        List.of(
            new WalletBalances(new Wallet("reserve-a", "0x1234567890abcdef1234567890abcdef12345678", "SECP256K1-FALCON512"), reserve),
            new WalletBalances(new Wallet("bruno", "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", "MLDSA87"), client),
            new WalletBalances(new Wallet("idle", "0x0000000000000000000000000000000000000001", "SECP256K1"), empty)),
        Map.of("BRL", c(200_000_000), "BRLA", c(250_000)));
  }

  @Test
  void showsBalancesFrozenPartsAndTotals() {
    String table = BalanceTable.render(snapshot(), false);
    assertTrue(table.startsWith("Balances at block 42"), table);
    assertTrue(table.contains("RD (BRL)") && table.contains("RT BRLA"), table);
    assertTrue(table.contains("1.000.000,00"), table);
    assertTrue(table.contains("2.500,00 (frozen 500,00)"), table);
    assertTrue(table.contains("0x123456..5678"), table);
    assertTrue(table.contains("Total supply on chain"), table);
    assertTrue(table.contains("2.000.000,00"), table); // supply differs from the wallets shown
    assertTrue(table.contains("idle"), table);
  }

  @Test
  void marksPausedTokensInTheHeader() {
    Snapshot s = snapshot();
    List<Token> tokens = List.of(s.tokens().get(0), new Token("BRLA", "Real Tokenizado Banco A", "0x02", "bank-a", true));
    String table = BalanceTable.render(new Snapshot(s.blockNumber(), s.takenAt(), tokens, s.wallets(), s.totalSupply()), false);
    assertTrue(table.contains("RT BRLA (paused)"), table);
    assertTrue(table.contains("RD (BRL) ") && !table.contains("RD (BRL) (paused)"), table);
  }

  @Test
  void hidesEmptyWalletsOnRequest() {
    assertFalse(BalanceTable.render(snapshot(), true).contains("idle"));
  }

  @Test
  void availableExcludesTheFrozenPart() {
    assertEquals(c(200_000), new Holding(c(250_000), c(50_000)).available());
    assertEquals(BigInteger.ZERO, new Holding(c(100), c(500)).available()); // freezing may exceed the balance
  }

  @Test
  void amountsUseTwoDecimalsInBrazilianFormat() {
    assertEquals("100,50", Amounts.format(c(10_050)));
    assertEquals("1.234.567,89", Amounts.format(c(123_456_789)));
    assertEquals("0,00", Amounts.format(BigInteger.ZERO));
    assertEquals(c(10_050), Amounts.parse("100,50"));
    assertEquals(c(123_456), Amounts.parse("1.234,56"));
    assertEquals(c(10_000), Amounts.parse("100"));
    assertEquals(c(10_050), Amounts.parse("R$ 100.50"));
  }
}

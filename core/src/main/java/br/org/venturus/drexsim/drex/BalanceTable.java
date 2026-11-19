package br.org.venturus.drexsim.drex;

import br.org.venturus.drexsim.drex.BalanceService.Holding;
import br.org.venturus.drexsim.drex.BalanceService.Snapshot;
import br.org.venturus.drexsim.drex.BalanceService.Token;
import br.org.venturus.drexsim.drex.BalanceService.WalletBalances;
import java.math.BigInteger;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a balance {@link Snapshot} as a text table, one row per wallet and one column per token
 * (Real Digital first, then each Real Tokenizado). A frozen part is shown next to the balance:
 * {@code 1.000,00 (frozen 200,00)}. The last rows are the sum of the wallets shown and the total
 * supply on chain, which differ when some holders are not listed.
 */
public final class BalanceTable {

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

  private BalanceTable() {}

  public static String render(Snapshot snapshot, boolean hideEmpty) {
    List<Token> tokens = snapshot.tokens();
    List<String> header = new ArrayList<>(List.of("Wallet", "Signs with", "Address"));
    for (Token t : tokens) {
      header.add(t.symbol().equals(BalanceService.RD) ? "RD (BRL)" : "RT " + t.symbol());
    }

    List<List<String>> rows = new ArrayList<>();
    BigInteger[] sums = new BigInteger[tokens.size()];
    java.util.Arrays.fill(sums, BigInteger.ZERO);
    for (WalletBalances w : snapshot.wallets()) {
      if (hideEmpty && w.isEmpty()) {
        continue;
      }
      List<String> row = new ArrayList<>(List.of(w.wallet().label(), w.wallet().signing(), shortAddress(w.wallet().address())));
      for (int i = 0; i < tokens.size(); i++) {
        Holding h = w.of(tokens.get(i).symbol());
        row.add(cell(h));
        sums[i] = sums[i].add(h.balance());
      }
      rows.add(row);
    }

    List<String> sumRow = new ArrayList<>(List.of("Sum of wallets shown", "", ""));
    List<String> supplyRow = new ArrayList<>(List.of("Total supply on chain", "", ""));
    for (int i = 0; i < tokens.size(); i++) {
      sumRow.add(Amounts.format(sums[i]));
      supplyRow.add(Amounts.format(snapshot.totalSupply().getOrDefault(tokens.get(i).symbol(), BigInteger.ZERO)));
    }

    int[] widths = new int[header.size()];
    for (List<String> r : concat(List.of(header), rows, List.of(sumRow, supplyRow))) {
      for (int i = 0; i < r.size(); i++) {
        widths[i] = Math.max(widths[i], r.get(i).length());
      }
    }

    StringBuilder out = new StringBuilder();
    out.append("Balances at block ").append(snapshot.blockNumber())
        .append(" (").append(TIME.format(snapshot.takenAt())).append("), amounts in R$\n");
    line(out, header, widths, false);
    rule(out, widths);
    if (rows.isEmpty()) {
      out.append("(no wallets").append(hideEmpty ? " with a balance" : "").append(")\n");
    }
    for (List<String> r : rows) {
      line(out, r, widths, true);
    }
    rule(out, widths);
    line(out, sumRow, widths, true);
    line(out, supplyRow, widths, true);
    return out.toString();
  }

  static String cell(Holding h) {
    String s = Amounts.format(h.balance());
    return h.frozen().signum() > 0 ? s + " (frozen " + Amounts.format(h.frozen()) + ")" : s;
  }

  static String shortAddress(String address) {
    return address.length() > 14 ? address.substring(0, 8) + ".." + address.substring(address.length() - 4) : address;
  }

  private static void line(StringBuilder out, List<String> cells, int[] widths, boolean rightAlignAmounts) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        out.append(" | ");
      }
      boolean right = rightAlignAmounts && i >= 3;
      String c = cells.get(i);
      String pad = " ".repeat(widths[i] - c.length());
      out.append(right ? pad + c : c + pad);
    }
    out.append('\n');
  }

  private static void rule(StringBuilder out, int[] widths) {
    for (int i = 0; i < widths.length; i++) {
      if (i > 0) {
        out.append("-+-");
      }
      out.append("-".repeat(widths[i]));
    }
    out.append('\n');
  }

  @SafeVarargs
  private static List<List<String>> concat(List<List<String>>... parts) {
    List<List<String>> all = new ArrayList<>();
    for (List<List<String>> p : parts) {
      all.addAll(p);
    }
    return all;
  }
}

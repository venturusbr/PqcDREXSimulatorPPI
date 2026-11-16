package br.org.venturus.drexsim.drex;

import br.org.venturus.drexsim.chain.Chain;
import br.org.venturus.drexsim.contracts.RealDigital;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.RemoteFunctionCall;

/**
 * Reads the Real Digital (RD) and Real Tokenizado (RT) balances of a set of wallets.
 *
 * <p>Every value of a snapshot is read at the same block, so the numbers are consistent with each
 * other (e.g. a swap never shows up as debited on one side but not yet credited on the other).
 * Calls are sent concurrently.
 */
public final class BalanceService {

  /** A wallet to show. {@code signing} describes how it signs (e.g. its DSA), or is empty. */
  public record Wallet(String label, String address, String signing) {}

  /** Balance of one token in one wallet. Only {@code balance - frozen} can be spent. */
  public record Holding(BigInteger balance, BigInteger frozen) {
    public static final Holding ZERO = new Holding(BigInteger.ZERO, BigInteger.ZERO);

    public BigInteger available() {
      return balance.subtract(frozen).max(BigInteger.ZERO);
    }

    public boolean isZero() {
      return balance.signum() == 0 && frozen.signum() == 0;
    }
  }

  /** A token column: Real Digital or one participant's Real Tokenizado. */
  public record Token(String symbol, String name, String address) {}

  public record WalletBalances(Wallet wallet, Map<String, Holding> holdings) {
    public Holding of(String symbol) {
      return holdings.getOrDefault(symbol, Holding.ZERO);
    }

    public boolean isEmpty() {
      return holdings.values().stream().allMatch(Holding::isZero);
    }
  }

  public record Snapshot(
      long blockNumber, Instant takenAt, List<Token> tokens, List<WalletBalances> wallets, Map<String, BigInteger> totalSupply) {}

  public static final String RD = "BRL";

  private final Chain chain;
  private final DrexDeployment deployment;

  public BalanceService(Chain chain, DrexDeployment deployment) {
    this.chain = chain;
    this.deployment = deployment;
  }

  public List<Token> tokens() {
    List<Token> tokens = new ArrayList<>();
    tokens.add(new Token(RD, "Real Digital", deployment.realDigital));
    for (DrexDeployment.Tokenized t : deployment.realTokenizados) {
      tokens.add(new Token(t.symbol(), "Real Tokenizado " + t.participant(), t.address()));
    }
    return tokens;
  }

  public Snapshot snapshot(List<Wallet> wallets) throws Exception {
    long block = chain.blockNumber();
    DefaultBlockParameter at = DefaultBlockParameter.valueOf(BigInteger.valueOf(block));
    List<Token> tokens = tokens();

    // RealTokenizado extends RealDigital, so the RealDigital wrapper reads both.
    Map<String, RealDigital> contracts = new LinkedHashMap<>();
    for (Token token : tokens) {
      RealDigital c = RealDigital.load(token.address(), chain.web3j(), chain.readOnly(), chain.gasProvider());
      c.setDefaultBlockParameter(at);
      contracts.put(token.symbol(), c);
    }

    Map<String, CompletableFuture<BigInteger>> supply = new LinkedHashMap<>();
    contracts.forEach((symbol, c) -> supply.put(symbol, async(c.totalSupply())));

    List<Map<String, CompletableFuture<Holding>>> pending = new ArrayList<>();
    for (Wallet wallet : wallets) {
      Map<String, CompletableFuture<Holding>> row = new LinkedHashMap<>();
      contracts.forEach(
          (symbol, c) ->
              row.put(
                  symbol,
                  async(c.balanceOf(wallet.address()))
                      .thenCombine(async(c.frozenBalanceOf(wallet.address())), Holding::new)));
      pending.add(row);
    }

    List<WalletBalances> rows = new ArrayList<>();
    for (int i = 0; i < wallets.size(); i++) {
      Map<String, Holding> holdings = new LinkedHashMap<>();
      pending.get(i).forEach((symbol, f) -> holdings.put(symbol, f.join()));
      rows.add(new WalletBalances(wallets.get(i), holdings));
    }
    Map<String, BigInteger> totals = new LinkedHashMap<>();
    supply.forEach((symbol, f) -> totals.put(symbol, f.join()));
    return new Snapshot(block, Instant.now(), tokens, rows, totals);
  }

  private static <T> CompletableFuture<T> async(RemoteFunctionCall<T> call) {
    return call.sendAsync();
  }
}

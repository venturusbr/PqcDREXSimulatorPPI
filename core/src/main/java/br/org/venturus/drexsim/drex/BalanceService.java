package br.org.venturus.drexsim.drex;

import br.org.venturus.drexsim.chain.Chain;
import br.org.venturus.drexsim.chain.RpcBatch;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;

/**
 * Reads the Real Digital (RD) and Real Tokenizado (RT) balances of a set of wallets.
 *
 * <p>Every value of a snapshot is read at the same block, so the numbers are consistent with each
 * other (e.g. a swap never shows up as debited on one side but not yet credited on the other).
 * The calls of a snapshot go to the node as one JSON-RPC batch.
 */
public final class BalanceService {

  /** A wallet to show. {@code signing} describes how it signs (e.g. its DSA), or is empty. */
  public record Wallet(String label, String address, String signing) {}

  /** Balance of one token in one wallet. Only {@code balance - frozen} can be spent. */
  public record Holding(BigInteger balance, BigInteger frozen) {
    public static final Holding ZERO = new Holding(BigInteger.ZERO, BigInteger.ZERO);

    @com.fasterxml.jackson.annotation.JsonProperty("available")
    public BigInteger available() {
      return balance.subtract(frozen).max(BigInteger.ZERO);
    }

    @JsonIgnore
    public boolean isZero() {
      return balance.signum() == 0 && frozen.signum() == 0;
    }
  }

  /** A token column: Real Digital or one participant's Real Tokenizado. */
  public record Token(String symbol, String name, String address) {}

  /**
   * What a wallet is on chain, from the contracts' own access control at the snapshot's block: {@code
   * bcb} holds DEFAULT_ADMIN_ROLE on RealDigital, {@code reserve} is the reserve wallet of a Real
   * Tokenizado, {@code participant} is enabled in RealDigital (a bank or other institution), and
   * {@code client} is enabled in the Real Tokenizado listed in {@code clientOf}. A wallet can have
   * several roles, or none if it was never enabled anywhere.
   */
  /**
   * What the contracts say a wallet is. {@code enabledIn} lists the tokens whose {@code verifyAccount} is true
   * for it: a balance in any other token got there without the wallet being enabled (see the README's known
   * contract flaws) or stayed after it was disabled.
   */
  public record Roles(List<String> roles, List<String> clientOf, List<String> enabledIn) {
    public static final Roles NONE = new Roles(List.of(), List.of(), List.of());
  }

  public record WalletBalances(Wallet wallet, Map<String, Holding> holdings, Roles roles) {
    public WalletBalances(Wallet wallet, Map<String, Holding> holdings) {
      this(wallet, holdings, Roles.NONE);
    }

    @JsonIgnore
    public Holding of(String symbol) {
      return holdings.getOrDefault(symbol, Holding.ZERO);
    }

    @JsonIgnore
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

  public Snapshot snapshot(List<Wallet> wallets) throws IOException {
    long block = chain.blockNumber();
    List<Token> tokens = tokens();
    String rd = tokens.get(0).address();
    CallBatch calls = new CallBatch(DefaultBlockParameter.valueOf(BigInteger.valueOf(block)));

    Map<String, Integer> supply = new LinkedHashMap<>();
    for (Token token : tokens) {
      supply.put(token.symbol(), calls.add(token.address(), fn("totalSupply", List.of(), UINT)));
    }
    // Reserve wallets, as each Real Tokenizado records them.
    List<Integer> reserves = new ArrayList<>();
    for (Token token : tokens.subList(1, tokens.size())) {
      reserves.add(calls.add(token.address(), fn("reserve", List.of(), ADDRESS)));
    }
    List<Map<String, int[]>> holdings = new ArrayList<>();
    List<Map<String, Integer>> enabled = new ArrayList<>();
    List<Integer> admins = new ArrayList<>();
    for (Wallet wallet : wallets) {
      Address who = new Address(wallet.address());
      Map<String, int[]> row = new LinkedHashMap<>();
      Map<String, Integer> access = new LinkedHashMap<>();
      for (Token token : tokens) {
        row.put(token.symbol(), new int[] {
            calls.add(token.address(), fn("balanceOf", List.of(who), UINT)),
            calls.add(token.address(), fn("frozenBalanceOf", List.of(who), UINT))});
        access.put(token.symbol(), calls.add(token.address(), fn("verifyAccount", List.of(who), BOOL)));
      }
      holdings.add(row);
      enabled.add(access);
      admins.add(calls.add(rd, fn("hasRole", List.of(new Bytes32(new byte[32]), who), BOOL)));
    }

    calls.send();

    List<String> reserveAddresses = reserves.stream().map(i -> calls.<String>get(i).toLowerCase()).toList();
    List<WalletBalances> rows = new ArrayList<>();
    for (int i = 0; i < wallets.size(); i++) {
      Map<String, Holding> row = new LinkedHashMap<>();
      holdings.get(i).forEach((symbol, at) -> row.put(symbol, new Holding(calls.get(at[0]), calls.get(at[1]))));
      Map<String, Boolean> access = new LinkedHashMap<>();
      enabled.get(i).forEach((symbol, at) -> access.put(symbol, calls.get(at)));
      rows.add(new WalletBalances(wallets.get(i), row, roles(wallets.get(i), calls.get(admins.get(i)), access, reserveAddresses)));
    }
    Map<String, BigInteger> totals = new LinkedHashMap<>();
    supply.forEach((symbol, at) -> totals.put(symbol, calls.get(at)));
    return new Snapshot(block, Instant.now(), tokens, rows, totals);
  }

  private static Roles roles(Wallet wallet, boolean admin, Map<String, Boolean> enabled, List<String> reserves) {
    List<String> roles = new ArrayList<>();
    List<String> clientOf = new ArrayList<>();
    List<String> enabledIn = new ArrayList<>();
    boolean reserve = reserves.contains(wallet.address().toLowerCase());
    if (admin) roles.add("bcb");
    if (reserve) roles.add("reserve");
    enabled.forEach((symbol, isEnabled) -> {
      if (!isEnabled) return;
      enabledIn.add(symbol);
      if (symbol.equals(RD)) {
        if (!reserve) roles.add("participant");
      } else {
        clientOf.add(symbol);
      }
    });
    if (!clientOf.isEmpty()) roles.add("client");
    return new Roles(List.copyOf(roles), List.copyOf(clientOf), List.copyOf(enabledIn));
  }

  // ------------------------------------------------------------------ batched calls

  private static final TypeReference<Uint256> UINT = new TypeReference<>() {};
  private static final TypeReference<Bool> BOOL = new TypeReference<>() {};
  private static final TypeReference<Address> ADDRESS = new TypeReference<>() {};
  private static final String NO_SENDER = "0x0000000000000000000000000000000000000000";

  @SuppressWarnings("rawtypes")
  private static Function fn(String name, List<? extends Type> inputs, TypeReference<?> output) {
    return new Function(name, List.<Type>copyOf(inputs), List.of(output));
  }

  /**
   * Read-only contract calls sent as JSON-RPC batches ({@link RpcBatch}), all at one block: a snapshot
   * costs one HTTP request instead of one request (and, with web3j's async calls, one connection) per call.
   */
  private final class CallBatch {
    private final DefaultBlockParameter at;
    private final List<Request<?, EthCall>> requests = new ArrayList<>();
    private final List<Function> functions = new ArrayList<>();
    private final List<Object> results = new ArrayList<>();

    CallBatch(DefaultBlockParameter at) {
      this.at = at;
    }

    /** Queues a call; returns its index for {@link #get}. */
    int add(String contract, Function function) {
      Transaction tx = Transaction.createEthCallTransaction(NO_SENDER, contract, FunctionEncoder.encode(function));
      requests.add(chain.web3j().ethCall(tx, at));
      functions.add(function);
      return requests.size() - 1;
    }

    void send() throws IOException {
      List<EthCall> responses = RpcBatch.send(chain.web3j(), requests);
      for (int i = 0; i < responses.size(); i++) {
        EthCall call = responses.get(i);
        Function function = functions.get(i);
        if (call.hasError()) {
          throw new IOException(function.getName() + ": " + call.getError().getMessage());
        }
        if (call.isReverted()) {
          throw new IOException(function.getName() + " reverted: " + call.getRevertReason());
        }
        List<Type> decoded = FunctionReturnDecoder.decode(call.getValue(), function.getOutputParameters());
        if (decoded.isEmpty()) {
          throw new IOException(function.getName() + " returned no data at " + function.getInputParameters());
        }
        results.add(decoded.get(0).getValue());
      }
    }

    @SuppressWarnings("unchecked")
    <T> T get(int index) {
      return (T) results.get(index);
    }
  }
}

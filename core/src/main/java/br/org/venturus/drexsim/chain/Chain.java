package br.org.venturus.drexsim.chain;

import br.org.venturus.drexsim.keystore.Account;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.ReadonlyTransactionManager;
import org.web3j.tx.TransactionManager;

/**
 * Connection to one besu-pqc-ppi JSON-RPC endpoint: web3j client, chain id, shared nonce manager,
 * and one {@link PqcTransactionManager} per account.
 */
public final class Chain implements AutoCloseable {

  /** {@code vnt_getAccountsSupportedDSAs} response. */
  public static final class SupportedDsas extends Response<List<String>> {}

  private final String rpcUrl;
  private final OkHttpClient client;
  private final HttpService http;
  private final Web3j web3j;
  private final long chainId;
  private final NonceManager nonces;
  private final AutoGasProvider gasProvider;
  private final Map<String, PqcTransactionManager> managers = new ConcurrentHashMap<>();
  private final List<Consumer<SentTransaction>> listeners = new CopyOnWriteArrayList<>();

  public Chain(String rpcUrl) throws IOException {
    this(rpcUrl, null);
  }

  /**
   * Connection that hands out nonces from {@code sharedNonces}, so the same accounts can send through
   * several nodes of one network without reusing a nonce. {@code null} gives this connection its own.
   */
  public Chain(String rpcUrl, NonceManager sharedNonces) throws IOException {
    this.rpcUrl = rpcUrl;
    // An own OkHttp client, so close() can release its keep-alive connections: web3j's HttpService
    // leaves them open for 5 minutes, and a node accepts only 80 (Besu's --rpc-http-max-active-connections).
    this.client = HttpService.getOkHttpClientBuilder().build();
    this.http = new HttpService(rpcUrl, client);
    this.web3j = Web3j.build(http);
    this.chainId = web3j.ethChainId().send().getChainId().longValueExact();
    this.nonces = sharedNonces != null ? sharedNonces : new NonceManager(web3j);
    this.gasProvider = new AutoGasProvider(chainId);
  }

  public String rpcUrl() {
    return rpcUrl;
  }

  public NonceManager nonces() {
    return nonces;
  }

  /** The HTTP service under {@link #web3j()}, for calls web3j has no method for. */
  public HttpService web3jService() {
    return http;
  }

  public Web3j web3j() {
    return web3j;
  }

  public long chainId() {
    return chainId;
  }

  public AutoGasProvider gasProvider() {
    return gasProvider;
  }

  /** Called for every transaction any account submits through this chain. */
  public void addListener(Consumer<SentTransaction> listener) {
    listeners.add(listener);
  }

  public PqcTransactionManager managerFor(Account account) {
    return managers.computeIfAbsent(
        account.address(),
        a -> new PqcTransactionManager(web3j, account, chainId, nonces, tx -> listeners.forEach(l -> l.accept(tx))));
  }

  /** For read-only contract calls. */
  public TransactionManager readOnly() {
    return new ReadonlyTransactionManager(web3j, "0x0000000000000000000000000000000000000000");
  }

  /** The account DSAs this node accepts ({@code VNT} RPC namespace). */
  public List<String> supportedDsas() throws IOException {
    SupportedDsas response =
        new Request<>("vnt_getAccountsSupportedDSAs", List.of(), http, SupportedDsas.class).send();
    if (response.hasError()) {
      throw new IOException("vnt_getAccountsSupportedDSAs: " + response.getError().getMessage()
          + " (is the VNT API enabled in rpc-http-api?)");
    }
    return response.getResult();
  }

  public long blockNumber() throws IOException {
    return web3j.ethBlockNumber().send().getBlockNumber().longValueExact();
  }

  @Override
  public void close() {
    web3j.shutdown();
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
  }
}

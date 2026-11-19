package br.org.venturus.drexsim.chain;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.BatchRequest;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;

/**
 * Sends many JSON-RPC requests as batches: one HTTP request per {@value #SIZE} calls instead of one
 * per call. Many calls sent one by one from concurrent threads each take a connection of their own,
 * and at a few hundred per second they exhaust the client's local ports.
 */
public final class RpcBatch {

  /** Calls per batch; Besu accepts 1024 by default ({@code --rpc-http-max-batch-size}). */
  public static final int SIZE = 500;

  private RpcBatch() {}

  /**
   * Sends {@code requests} and returns their responses in the same order. A response may carry an
   * error ({@link Response#hasError()}); checking it is the caller's job.
   */
  @SuppressWarnings("unchecked")
  public static <R extends Response<?>> List<R> send(Web3j web3j, List<? extends Request<?, R>> requests)
      throws IOException {
    List<R> responses = new ArrayList<>(requests.size());
    for (int from = 0; from < requests.size(); from += SIZE) {
      List<? extends Request<?, R>> chunk = requests.subList(from, Math.min(from + SIZE, requests.size()));
      BatchRequest batch = web3j.newBatch();
      Map<Long, Integer> indexById = new HashMap<>();
      for (int i = 0; i < chunk.size(); i++) {
        batch.add(chunk.get(i));
        if (indexById.put(chunk.get(i).getId(), i) != null) {
          throw new IllegalStateException("Two requests of one batch share the id " + chunk.get(i).getId());
        }
      }
      Object[] ordered = new Object[chunk.size()];
      for (Response<?> response : batch.send().getResponses()) {
        Integer index = indexById.get(response.getId());
        if (index == null) {
          throw new IOException("Unexpected response id " + response.getId() + " in a JSON-RPC batch");
        }
        ordered[index] = response;
      }
      for (int i = 0; i < ordered.length; i++) {
        if (ordered[i] == null) {
          throw new IOException("No response to " + chunk.get(i).getMethod() + " in a JSON-RPC batch");
        }
        responses.add((R) ordered[i]);
      }
    }
    return responses;
  }
}

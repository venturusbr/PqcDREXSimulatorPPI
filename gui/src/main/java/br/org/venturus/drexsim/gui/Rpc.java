package br.org.venturus.drexsim.gui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Raw JSON-RPC calls that keep the node's reply as JSON. web3j's typed responses drop the fields
 * besu-pqc-ppi adds to flexible transactions ({@code dsaType}, {@code publicKey}, {@code signature}).
 */
final class Rpc {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  private Rpc() {}

  /** The {@code result} of a call; {@code null} results come back as JSON null. */
  static JsonNode call(String url, String method, Object... params) throws IOException {
    String body = JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", List.of(params)));
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    HttpResponse<String> response;
    try {
      response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted calling " + method, e);
    }
    JsonNode reply = JSON.readTree(response.body());
    if (reply.hasNonNull("error")) {
      throw new IOException(method + ": " + reply.get("error").path("message").asText("error"));
    }
    return reply.path("result");
  }

  static long quantity(JsonNode hex) {
    return Long.decode(hex.asText());
  }
}

package br.org.venturus.drexsim.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.sse.SseClient;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pushes live updates to the browser over Server-Sent Events: new blocks, transactions and their
 * receipts, job progress. Each event carries a JSON payload.
 */
final class EventHub {

  private final Set<SseClient> clients = ConcurrentHashMap.newKeySet();
  private final ObjectMapper json;

  EventHub(ObjectMapper json) {
    this.json = json;
  }

  void add(SseClient client) {
    clients.add(client);
    client.onClose(() -> clients.remove(client));
    client.keepAlive();
  }

  void send(String event, Object payload) {
    String data;
    try {
      data = json.writeValueAsString(payload);
    } catch (Exception e) {
      return;
    }
    for (SseClient c : clients) {
      try {
        c.sendEvent(event, data);
      } catch (Exception e) {
        clients.remove(c);
      }
    }
  }
}

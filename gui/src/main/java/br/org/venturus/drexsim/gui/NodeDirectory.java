package br.org.venturus.drexsim.gui;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.web3j.crypto.Hash;

/**
 * The RPC endpoints of the network's nodes. Given explicitly ({@code --nodes}), or found by probing
 * the ports after a local {@code --rpc} port: besu-qbft maps node N to 8545 + N - 1. A probed port
 * counts only if it serves the same chain (same chain id and genesis block).
 */
final class NodeDirectory {

  /** A node: a short name and its JSON-RPC URL. */
  record Node(String name, String url, boolean primary) {}

  /** What a node reports right now. */
  record NodeStatus(String name, String url, boolean primary, boolean reachable, Long block, Integer peers,
      String p2p, Integer nodeKeyBytes, String validator, String error) {}

  /** How many ports after the primary one to probe. */
  static final int PROBE_PORTS = 15;

  private static final Pattern ENODE = Pattern.compile("^enode://([0-9a-fA-F]+)@([^?]+)");

  private final String primaryUrl;
  private final List<String> explicit;
  private volatile List<Node> nodes = List.of();

  NodeDirectory(String primaryUrl, List<String> explicit) {
    this.primaryUrl = primaryUrl;
    this.explicit = explicit == null ? List.of() : explicit.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  List<Node> nodes() {
    return nodes;
  }

  Node primary() {
    return nodes.stream().filter(Node::primary).findFirst().orElse(new Node("node-1", primaryUrl, true));
  }

  /** The node with this name or URL; blank means the primary. Only known nodes are accepted. */
  Node resolve(String nameOrUrl) {
    if (nameOrUrl == null || nameOrUrl.isBlank()) {
      return primary();
    }
    return nodes.stream().filter(n -> n.name().equals(nameOrUrl) || n.url().equals(nameOrUrl)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown node " + nameOrUrl));
  }

  /** Finds the nodes again. The primary is always first. */
  synchronized List<Node> discover() {
    Set<String> urls = new LinkedHashSet<>();
    urls.add(primaryUrl);
    if (!explicit.isEmpty()) {
      urls.addAll(explicit);
    } else {
      urls.addAll(probeLocalPorts());
    }
    List<String> ordered = new ArrayList<>(urls);
    ordered.sort(Comparator.comparingInt(NodeDirectory::port));
    List<Node> found = new ArrayList<>();
    for (int i = 0; i < ordered.size(); i++) {
      String url = ordered.get(i);
      found.add(new Node("node-" + (i + 1), url, url.equals(primaryUrl)));
    }
    nodes = List.copyOf(found);
    return nodes;
  }

  private List<String> probeLocalPorts() {
    URI uri = URI.create(primaryUrl);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (!(host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"))) {
      return List.of();
    }
    String chainId;
    String genesis;
    try {
      chainId = Rpc.call(primaryUrl, "eth_chainId").asText();
      genesis = Rpc.call(primaryUrl, "eth_getBlockByNumber", "0x0", false).path("hash").asText();
    } catch (Exception e) {
      return List.of();
    }
    int base = port(primaryUrl);
    List<CompletableFuture<String>> probes = new ArrayList<>();
    for (int p = base + 1; p <= base + PROBE_PORTS; p++) {
      String url = uri.getScheme() + "://" + uri.getHost() + ":" + p + (uri.getPath() == null ? "" : uri.getPath());
      probes.add(CompletableFuture.supplyAsync(() -> {
        try {
          boolean same = chainId.equals(Rpc.call(url, "eth_chainId").asText())
              && genesis.equals(Rpc.call(url, "eth_getBlockByNumber", "0x0", false).path("hash").asText());
          return same ? url : null;
        } catch (Exception e) {
          return null;
        }
      }));
    }
    return probes.stream().map(CompletableFuture::join).filter(u -> u != null).toList();
  }

  /** Status of every node, asked in parallel. */
  List<NodeStatus> status(List<String> validators) {
    Set<String> validatorSet = new LinkedHashSet<>();
    validators.forEach(v -> validatorSet.add(v.toLowerCase(Locale.ROOT)));
    return nodes.stream().map(n -> CompletableFuture.supplyAsync(() -> status(n, validatorSet)))
        .toList().stream().map(CompletableFuture::join).toList();
  }

  private static NodeStatus status(Node n, Set<String> validators) {
    try {
      long block = Rpc.quantity(Rpc.call(n.url(), "eth_blockNumber"));
      Integer peers = null;
      String p2p = null;
      Integer keyBytes = null;
      String validator = null;
      try {
        peers = (int) Rpc.quantity(Rpc.call(n.url(), "net_peerCount"));
      } catch (Exception ignored) {
        // NET API not enabled
      }
      try {
        Matcher m = ENODE.matcher(Rpc.call(n.url(), "net_enode").asText());
        if (m.find()) {
          byte[] key = HexFormat.of().parseHex(m.group(1));
          keyBytes = key.length;
          p2p = m.group(2);
          // A node's address is the last 20 bytes of keccak(public key), as for accounts.
          String address = "0x" + HexFormat.of().formatHex(Hash.sha3(key)).substring(24);
          if (validators.contains(address)) {
            validator = address;
          }
        }
      } catch (Exception ignored) {
        // no enode (NET API not enabled)
      }
      return new NodeStatus(n.name(), n.url(), n.primary(), true, block, peers, p2p, keyBytes, validator, null);
    } catch (Exception e) {
      return new NodeStatus(n.name(), n.url(), n.primary(), false, null, null, null, null, null, e.getMessage());
    }
  }

  static int port(String url) {
    URI uri = URI.create(url);
    if (uri.getPort() > 0) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  /** Helper for callers holding a JSON list of validator addresses. */
  static List<String> addresses(JsonNode list) {
    List<String> out = new ArrayList<>();
    list.forEach(v -> out.add(v.asText()));
    return out;
  }
}

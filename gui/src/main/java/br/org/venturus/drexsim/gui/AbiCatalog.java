package br.org.venturus.drexsim.gui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.web3j.crypto.Hash;

/**
 * Function selectors, event topics and role ids of the Drex contracts, read from the ABIs bundled
 * under {@code /abi} (copied from contracts/build). The page uses them to decode transaction input
 * and logs.
 */
final class AbiCatalog {

  static final List<String> CONTRACTS = List.of("RealDigital", "RealTokenizado", "STR", "SwapOneStep", "SwapTwoSteps",
      "RealDigitalEnableAccount", "RealDigitalDefaultAccount", "KeyDictionary", "AddressDiscovery");

  record Param(String name, String type, boolean indexed) {}

  record Entry(String name, String signature, List<String> contracts, List<Param> inputs) {}

  /** selector (0x + 8 hex) → function; topic0 → event; role id → role name. */
  record Catalog(Map<String, Entry> functions, Map<String, Entry> events, Map<String, String> roles) {}

  private AbiCatalog() {}

  static Catalog load() throws IOException {
    ObjectMapper json = new ObjectMapper();
    Map<String, Entry> functions = new LinkedHashMap<>();
    Map<String, Entry> events = new LinkedHashMap<>();
    Map<String, String> roles = new LinkedHashMap<>();
    roles.put("0x" + "0".repeat(64), "DEFAULT_ADMIN_ROLE");
    for (String contract : CONTRACTS) {
      try (InputStream in = AbiCatalog.class.getResourceAsStream("/abi/" + contract + ".abi")) {
        if (in == null) {
          continue;
        }
        for (JsonNode item : json.readTree(in)) {
          String kind = item.path("type").asText();
          if (!kind.equals("function") && !kind.equals("event")) {
            continue;
          }
          String name = item.path("name").asText();
          List<Param> inputs = new ArrayList<>();
          for (JsonNode p : item.path("inputs")) {
            inputs.add(new Param(p.path("name").asText(), canonicalType(p), p.path("indexed").asBoolean(false)));
          }
          String signature = name + "(" + inputs.stream().map(Param::type).collect(Collectors.joining(",")) + ")";
          String hash = Hash.sha3String(signature);
          Map<String, Entry> target = kind.equals("function") ? functions : events;
          String key = kind.equals("function") ? hash.substring(0, 10) : hash;
          Entry existing = target.get(key);
          if (existing == null) {
            target.put(key, new Entry(name, signature, new ArrayList<>(List.of(contract)), inputs));
          } else if (!existing.contracts().contains(contract)) {
            existing.contracts().add(contract);
          }
          if (kind.equals("function") && name.endsWith("_ROLE") && inputs.isEmpty() && !name.equals("DEFAULT_ADMIN_ROLE")) {
            roles.put(Hash.sha3String(name), name);
          }
        }
      }
    }
    return new Catalog(functions, events, roles);
  }

  /** The type as it appears in a signature: tuples spelled out as (t1,t2,...). */
  private static String canonicalType(JsonNode p) {
    String type = p.path("type").asText();
    if (!type.startsWith("tuple")) {
      return type;
    }
    List<String> parts = new ArrayList<>();
    p.path("components").forEach(c -> parts.add(canonicalType(c)));
    return "(" + String.join(",", parts) + ")" + type.substring("tuple".length());
  }
}

package br.org.venturus.drexsim.drex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Addresses of a deployed Drex network, kept in {@code deployment.json} in the data directory.
 * The same addresses are registered on chain in AddressDiscovery.
 */
public final class DrexDeployment {

  /** One participant's Real Tokenizado. */
  public record Tokenized(
      String participant, String symbol, BigInteger cnpj8, String address, String authority, String reserve) {}

  private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private static final String FILE = "deployment.json";

  public long chainId;
  public String realDigital;
  public String str;
  public String realDigitalEnableAccount;
  public String realDigitalDefaultAccount;
  public String keyDictionary;
  public String swapOneStep;
  public String swapTwoSteps;
  public String addressDiscovery;
  public List<Tokenized> realTokenizados = new ArrayList<>();

  public Optional<Tokenized> tokenized(String symbol) {
    return realTokenizados.stream().filter(t -> t.symbol().equalsIgnoreCase(symbol)).findFirst();
  }

  public static Optional<DrexDeployment> load(Path dataDir) throws IOException {
    Path file = dataDir.resolve(FILE);
    return Files.exists(file) ? Optional.of(JSON.readValue(file.toFile(), DrexDeployment.class)) : Optional.empty();
  }

  public void save(Path dataDir) throws IOException {
    Files.createDirectories(dataDir);
    JSON.writeValue(dataDir.resolve(FILE).toFile(), this);
  }
}

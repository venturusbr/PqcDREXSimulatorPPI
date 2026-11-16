package br.org.venturus.drexsim.keystore;

import br.org.venturus.drexsim.crypto.Dsa;
import br.org.venturus.drexsim.tx.TxFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.web3j.utils.Numeric;

/**
 * Keys of the simulated actors, kept in {@code accounts.json} in the simulator data directory.
 *
 * <p>The keys are <b>test keys in plain text</b>: they exist only to drive the simulation on a
 * local network and must never hold anything of value.
 */
public final class AccountStore {

  /** JSON form of an account. */
  public record Entry(String name, String dsa, String format, String address, String privateKey) {}

  private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private final Path file;
  private final Map<String, Account> accounts = new LinkedHashMap<>();

  private AccountStore(Path file) {
    this.file = file;
  }

  /** Opens the store in {@code dataDir}, creating an empty one when the file does not exist. */
  public static AccountStore open(Path dataDir) throws IOException {
    AccountStore store = new AccountStore(dataDir.resolve("accounts.json"));
    if (Files.exists(store.file)) {
      for (Entry e : JSON.readValue(store.file.toFile(), Entry[].class)) {
        Account account =
            Account.fromPrivateKey(
                e.name(), Dsa.fromName(e.dsa()), TxFormat.valueOf(e.format()), Numeric.hexStringToByteArray(e.privateKey()));
        store.accounts.put(e.name(), account);
      }
    }
    return store;
  }

  public Optional<Account> find(String name) {
    return Optional.ofNullable(accounts.get(name));
  }

  public Account get(String name) {
    return find(name).orElseThrow(() -> new IllegalArgumentException("No account named " + name));
  }

  /** Finds an account by address, ignoring case. */
  public Optional<Account> byAddress(String address) {
    return accounts.values().stream().filter(a -> a.address().equalsIgnoreCase(address)).findFirst();
  }

  public Collection<Account> all() {
    return List.copyOf(accounts.values());
  }

  /** Returns the named account, generating and saving it first if it does not exist. */
  public synchronized Account getOrCreate(String name, Dsa dsa, TxFormat format) throws IOException {
    Account existing = accounts.get(name);
    if (existing != null) {
      return existing;
    }
    Account created = Account.generate(name, dsa, format);
    accounts.put(name, created);
    save();
    return created;
  }

  /** Generates a new account; fails if the name is taken. */
  public synchronized Account create(String name, Dsa dsa, TxFormat format) throws IOException {
    if (accounts.containsKey(name)) {
      throw new IllegalArgumentException("Account " + name + " already exists");
    }
    return getOrCreate(name, dsa, format);
  }

  private void save() throws IOException {
    Files.createDirectories(file.getParent());
    List<Entry> entries = new ArrayList<>();
    for (Account a : accounts.values()) {
      entries.add(
          new Entry(a.name(), a.dsa().wireName(), a.format().name(), a.address(), Numeric.toHexString(a.privateKey())));
    }
    JSON.writeValue(file.toFile(), entries);
  }
}

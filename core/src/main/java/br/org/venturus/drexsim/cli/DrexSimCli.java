package br.org.venturus.drexsim.cli;

import br.org.venturus.drexsim.chain.Chain;
import br.org.venturus.drexsim.chain.SentTransaction;
import br.org.venturus.drexsim.crypto.Dsa;
import br.org.venturus.drexsim.drex.BalanceService;
import br.org.venturus.drexsim.drex.BalanceTable;
import br.org.venturus.drexsim.drex.DemoSetup;
import br.org.venturus.drexsim.drex.DrexClient;
import br.org.venturus.drexsim.drex.DrexDeployment;
import br.org.venturus.drexsim.keystore.Account;
import br.org.venturus.drexsim.keystore.AccountStore;
import br.org.venturus.drexsim.tx.TxFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** Command-line entry point of the PQC Drex simulator. */
@Command(
    name = "drexsim",
    mixinStandardHelpOptions = true,
    version = "drexsim 0.1.0",
    description = "Simulates Real Digital (Drex) operations on the besu-pqc-ppi post-quantum network.",
    subcommands = {
      DrexSimCli.NetworkCommand.class,
      DrexSimCli.AccountsCommand.class,
      DrexSimCli.DeployCommand.class,
      DrexSimCli.SetupDemoCommand.class,
      DrexSimCli.BalancesCommand.class
    })
public final class DrexSimCli {

  @Option(names = "--rpc", defaultValue = "${env:DREXSIM_RPC:-http://localhost:8545}",
      description = "JSON-RPC endpoint of a besu-pqc-ppi node (default: ${DEFAULT-VALUE}).")
  String rpc;

  @Option(names = "--data-dir", defaultValue = "${env:DREXSIM_DATA:-simulator-data}",
      description = "Directory with accounts.json and deployment.json (default: ${DEFAULT-VALUE}).")
  Path dataDir;

  @Option(names = {"-q", "--quiet"}, description = "Do not print each transaction sent.")
  boolean quiet;

  public static void main(String[] args) {
    System.exit(new CommandLine(new DrexSimCli()).execute(args));
  }

  Chain connect() throws IOException {
    Chain chain = new Chain(rpc);
    if (!quiet) {
      chain.addListener(DrexSimCli::printSent);
    }
    return chain;
  }

  AccountStore accounts() throws IOException {
    return AccountStore.open(dataDir);
  }

  DrexDeployment deployment() throws IOException {
    return DrexDeployment.load(dataDir)
        .orElseThrow(() -> new IllegalStateException("No deployment in " + dataDir + "; run `drexsim deploy` or `drexsim setup-demo` first"));
  }

  static void printSent(SentTransaction tx) {
    if (tx.accepted()) {
      System.out.printf("  -> %-10s %-22s nonce %-3s %6.1f kB  sig %4d B  key %4d B  gas %8s  sign %5.1f ms  %s%n",
          tx.account(), tx.format() == TxFormat.STANDARD ? tx.dsa() + " (0x02)" : tx.dsa() + " (0x42)",
          tx.nonce(), tx.size() / 1024.0, tx.signatureLength(), tx.publicKeyLength(), tx.gasLimit(),
          tx.signNanos() / 1e6, tx.hash());
    } else {
      System.out.printf("  -> %-10s %-22s REJECTED: %s%n", tx.account(), tx.dsa(), tx.error());
    }
  }

  // ------------------------------------------------------------------ network

  @Command(mixinStandardHelpOptions = true, name = "network", description = "Shows the chain id, the latest block and the account DSAs the node accepts.")
  static final class NetworkCommand implements Callable<Integer> {
    @ParentCommand DrexSimCli root;

    @Override
    public Integer call() throws Exception {
      try (Chain chain = root.connect()) {
        System.out.println("RPC:            " + chain.rpcUrl());
        System.out.println("Chain id:       " + chain.chainId());
        System.out.println("Block:          " + chain.blockNumber());
        System.out.println("Account DSAs:   " + String.join(", ", chain.supportedDsas()));
      }
      return 0;
    }
  }

  // ------------------------------------------------------------------ accounts

  @Command(mixinStandardHelpOptions = true, name = "accounts", description = "Lists or creates simulated accounts (test keys, stored in plain text).",
      subcommands = {AccountsCommand.ListCommand.class, AccountsCommand.NewCommand.class})
  static final class AccountsCommand {
    @ParentCommand DrexSimCli root;

    @Command(mixinStandardHelpOptions = true, name = "list", description = "Lists the accounts.")
    static final class ListCommand implements Callable<Integer> {
      @ParentCommand AccountsCommand parent;

      @Override
      public Integer call() throws Exception {
        for (Account a : parent.root.accounts().all()) {
          System.out.printf("%-12s %s  %s%n", a.name(), a.address(), a.signingLabel());
        }
        return 0;
      }
    }

    @Command(mixinStandardHelpOptions = true, name = "new", description = "Generates a new account.")
    static final class NewCommand implements Callable<Integer> {
      @ParentCommand AccountsCommand parent;

      @Parameters(index = "0", description = "Account name.")
      String name;

      @Option(names = "--dsa", defaultValue = "SECP256K1-MLDSA44",
          description = "One of: SECP256K1, SECP256R1, MLDSA44, MLDSA65, MLDSA87, FALCON512, SECP256K1-MLDSA44, SECP256K1-FALCON512 (default: ${DEFAULT-VALUE}).")
      String dsa;

      @Option(names = "--standard", description = "Sign standard EIP-1559 (0x02) transactions; ECDSA DSAs only. The classical baseline.")
      boolean standard;

      @Override
      public Integer call() throws Exception {
        Account a = parent.root.accounts().create(name, Dsa.fromName(dsa), standard ? TxFormat.STANDARD : TxFormat.FLEXIBLE);
        System.out.println(a);
        return 0;
      }
    }
  }

  // ------------------------------------------------------------------ deploy

  @Command(mixinStandardHelpOptions = true, name = "deploy", description = "Flow 0: deploys the Drex contracts and grants the service roles.")
  static final class DeployCommand implements Callable<Integer> {
    @ParentCommand DrexSimCli root;

    @Option(names = "--bcb", defaultValue = "bcb", description = "Account that deploys and administers (created if missing).")
    String bcb;

    @Option(names = "--bcb-dsa", defaultValue = "SECP256K1-MLDSA44", description = "DSA of the BCB account when it is created.")
    String bcbDsa;

    @Override
    public Integer call() throws Exception {
      try (Chain chain = root.connect()) {
        Account account = root.accounts().getOrCreate(bcb, Dsa.fromName(bcbDsa), TxFormat.FLEXIBLE);
        DrexDeployment d = DrexClient.deployNetwork(chain, account, System.out::println);
        d.save(root.dataDir);
        System.out.println("RealDigital at " + d.realDigital + "; addresses saved in " + root.dataDir.resolve("deployment.json"));
      }
      return 0;
    }
  }

  // ------------------------------------------------------------------ setup-demo

  @Command(mixinStandardHelpOptions = true, name = "setup-demo",
      description = "Creates a demo network: BCB, two banks with reserves and Real Tokenizado, three clients, "
          + "each signing with a different DSA; mints, deposits and one inter-bank payment.")
  static final class SetupDemoCommand implements Callable<Integer> {
    @ParentCommand DrexSimCli root;

    @Override
    public Integer call() throws Exception {
      try (Chain chain = root.connect()) {
        AccountStore store = root.accounts();
        DrexDeployment d = new DemoSetup(chain, store, root.dataDir, System.out::println).run();
        System.out.println();
        BalanceService service = new BalanceService(chain, d);
        System.out.print(BalanceTable.render(service.snapshot(BalancesCommand.walletsOf(store.all())), false));
      }
      return 0;
    }
  }

  // ------------------------------------------------------------------ balances

  @Command(mixinStandardHelpOptions = true, name = "balances",
      description = "Shows the Real Digital (RD) and Real Tokenizado (RT) balances of the wallets, frozen parts included.")
  static final class BalancesCommand implements Callable<Integer> {
    @ParentCommand DrexSimCli root;

    @Option(names = {"-a", "--account"}, description = "Only these accounts (by name). Default: every account in accounts.json.")
    List<String> accountNames = new ArrayList<>();

    @Option(names = "--address", description = "Also show these addresses (e.g. wallets created outside the simulator).")
    List<String> addresses = new ArrayList<>();

    @Option(names = "--hide-empty", description = "Hide wallets with no balance at all.")
    boolean hideEmpty;

    @Option(names = {"-w", "--watch"}, paramLabel = "SECONDS", description = "Refresh every SECONDS seconds until interrupted.")
    Integer watch;

    @Option(names = "--json", description = "Print the snapshot as JSON (amounts in cents).")
    boolean json;

    static List<BalanceService.Wallet> walletsOf(Iterable<Account> accounts) {
      List<BalanceService.Wallet> wallets = new ArrayList<>();
      for (Account a : accounts) {
        wallets.add(new BalanceService.Wallet(a.name(), a.address(), a.signingLabel()));
      }
      return wallets;
    }

    @Override
    public Integer call() throws Exception {
      AccountStore store = root.accounts();
      List<BalanceService.Wallet> wallets = new ArrayList<>();
      if (accountNames.isEmpty()) {
        wallets.addAll(walletsOf(store.all()));
      } else {
        for (String name : accountNames) {
          wallets.addAll(walletsOf(List.of(store.get(name))));
        }
      }
      for (String address : addresses) {
        wallets.add(new BalanceService.Wallet(
            store.byAddress(address).map(Account::name).orElse("(external)"), address.toLowerCase(), ""));
      }

      ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).enable(SerializationFeature.INDENT_OUTPUT);
      try (Chain chain = new Chain(root.rpc)) {
        BalanceService service = new BalanceService(chain, root.deployment());
        while (true) {
          BalanceService.Snapshot snapshot = service.snapshot(wallets);
          if (watch != null && !json) {
            System.out.print("\033[H\033[2J"); // clear the terminal between refreshes
          }
          System.out.print(json ? mapper.writeValueAsString(snapshot) + "\n" : BalanceTable.render(snapshot, hideEmpty));
          System.out.flush();
          if (watch == null) {
            return 0;
          }
          Thread.sleep(watch * 1000L);
        }
      }
    }
  }

}

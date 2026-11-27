package br.org.venturus.drexsim.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Web interface of the simulator. Serves the page in {@code /public} and a JSON API over the core:
 *
 * <pre>
 * GET  /api/network                 chain id, block, account DSAs, validators
 * GET  /api/accounts                simulated accounts        POST /api/accounts {name, dsa, standard}
 * GET  /api/deployment              contract addresses
 * GET  /api/balances                RD and RT balances of every account, at one block
 * GET  /api/operations              operations offered, with their form fields
 * POST /api/operations/{id}         runs one, signed by the account its flow names, sent to params.node
 * GET  /api/proposals               pending two-step payments (flow 13) and open key requests (flow 14)
 * GET  /api/transactions            transactions sent from the GUI, with receipts
 * GET  /api/transactions/{hash}     eth_getTransactionByHash and eth_getTransactionReceipt (?node=node-2)
 * GET  /api/nodes                   every node and its status   POST /api/nodes/rescan
 * GET  /api/abi                     selectors, event topics and role ids of the Drex contracts
 * GET  /api/languages               the interface's languages: the files in /public/i18n
 * POST /api/jobs/{demo|deploy|flows|security|bench}   starts a background job
 * GET  /api/jobs, /api/jobs/{id}    job status, log and report
 * GET  /api/events                  Server-Sent Events: block, tx, job, job-log, accounts, balances-changed
 * </pre>
 *
 * <p>The server signs with the simulator's keys, so it listens on 127.0.0.1 unless told otherwise.
 */
@Command(name = "drexsim-gui", mixinStandardHelpOptions = true, version = "drexsim-gui 0.1.0",
    description = "Web interface of the PQC Drex simulator.")
public final class GuiServer implements Callable<Integer> {

  @Option(names = "--rpc", defaultValue = "${env:DREXSIM_RPC:-http://localhost:8545}",
      description = "JSON-RPC endpoint of a besu-pqc-ppi node (default: ${DEFAULT-VALUE}).")
  String rpc;

  @Option(names = "--nodes", split = ",",
      description = "RPC endpoints of all the network's nodes, comma-separated, for choosing where operations go. "
          + "Default: --rpc plus the next " + NodeDirectory.PROBE_PORTS + " local ports that serve the same chain.")
  List<String> nodes;

  @Option(names = "--data-dir", defaultValue = "${env:DREXSIM_DATA:-simulator-data}",
      description = "Directory with accounts.json and deployment.json, shared with the CLI (default: ${DEFAULT-VALUE}).")
  Path dataDir;

  @Option(names = "--reports-dir", defaultValue = "reports", description = "Where evaluation reports go (default: ${DEFAULT-VALUE}).")
  Path reportsDir;

  @Option(names = "--port", defaultValue = "8080", description = "HTTP port (default: ${DEFAULT-VALUE}).")
  int port;

  @Option(names = "--host", defaultValue = "127.0.0.1",
      description = "Address to listen on (default: ${DEFAULT-VALUE}). The GUI can sign with every simulated account: "
          + "do not expose it beyond your machine.")
  String host;

  public static void main(String[] args) {
    int code = new CommandLine(new GuiServer()).execute(args);
    if (code != 0) {
      System.exit(code);
    }
  }

  /** Request bodies. */
  record NewAccount(String name, String dsa, boolean standard) {}

  record DeployRequest(String bcb, String bcbDsa) {}

  record FlowsRequest(List<String> dsas) {}

  record BenchRequest(List<String> dsas, List<Double> rates, Integer seconds, Integer clients, List<String> nodes) {}

  @Override
  public Integer call() throws Exception {
    ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    EventHub events = new EventHub(json);
    SimulatorService service = new SimulatorService(rpc, nodes, dataDir, reportsDir, events);
    Javalin app = create(service, events, json);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      app.stop();
      service.close();
    }));
    app.start(host, port);
    System.out.println("PQC Drex simulator GUI on http://" + (host.equals("0.0.0.0") ? "localhost" : host) + ":" + port
        + " (node " + rpc + ", " + service.nodeList().size() + " node(s), data " + dataDir.toAbsolutePath() + ")");
    Thread.currentThread().join();
    return 0;
  }

  static Javalin create(SimulatorService service, EventHub events, ObjectMapper json) {
    Javalin app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new JavalinJackson(json, false));
      config.staticFiles.add(files -> {
        files.hostedPath = "/";
        files.directory = "/public";
        files.location = Location.CLASSPATH;
      });
    });

    app.get("/api/network", ctx -> ctx.json(service.network()));
    app.get("/api/nodes", ctx -> ctx.json(service.nodeStatus()));
    app.post("/api/nodes/rescan", ctx -> ctx.json(service.rescanNodes()));
    app.get("/api/abi", ctx -> ctx.json(service.abi()));
    List<Languages.Language> languages = Languages.list();
    app.get("/api/languages", ctx -> ctx.json(languages));
    app.get("/api/accounts", ctx -> ctx.json(service.accounts()));
    app.post("/api/accounts", ctx -> {
      NewAccount body = ctx.bodyAsClass(NewAccount.class);
      ctx.status(HttpStatus.CREATED).json(service.createAccount(body.name(), body.dsa(), body.standard()));
    });
    app.get("/api/deployment", ctx -> service.deployment().ifPresentOrElse(ctx::json,
        () -> ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "No Drex network deployed yet"))));
    app.get("/api/balances", ctx -> service.balances().ifPresentOrElse(ctx::json,
        () -> ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "No Drex network deployed yet"))));
    app.get("/api/operations", ctx -> ctx.json(SimulatorService.OPERATIONS));
    app.get("/api/proposals", ctx -> ctx.json(service.proposals()));
    app.post("/api/operations/{id}", ctx -> {
      @SuppressWarnings("unchecked")
      Map<String, String> params = ctx.body().isBlank() ? Map.of() : json.readValue(ctx.body(), Map.class);
      SimulatorService.OpResult r = service.run(ctx.pathParam("id"), params);
      ctx.status(r.ok() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_CONTENT).json(r);
    });
    app.get("/api/transactions/{hash}", ctx -> ctx.json(service.transactionDetails(ctx.pathParam("hash"), ctx.queryParam("node"))));
    app.get("/api/transactions", ctx -> ctx.json(service.transactions(ctx.queryParamAsClass("limit", Integer.class).getOrDefault(200))));

    app.post("/api/jobs/demo", ctx -> ctx.status(HttpStatus.ACCEPTED).json(JobRunner.Summary.of(service.startDemo())));
    app.post("/api/jobs/deploy", ctx -> {
      DeployRequest body = ctx.body().isBlank() ? new DeployRequest(null, null) : ctx.bodyAsClass(DeployRequest.class);
      ctx.status(HttpStatus.ACCEPTED).json(JobRunner.Summary.of(service.startDeploy(
          body.bcb() == null || body.bcb().isBlank() ? "bcb" : body.bcb(),
          body.bcbDsa() == null || body.bcbDsa().isBlank() ? "SECP256K1-MLDSA44" : body.bcbDsa())));
    });
    app.post("/api/jobs/flows", ctx -> {
      FlowsRequest body = ctx.body().isBlank() ? new FlowsRequest(null) : ctx.bodyAsClass(FlowsRequest.class);
      ctx.status(HttpStatus.ACCEPTED).json(JobRunner.Summary.of(service.startFlows(body.dsas())));
    });
    app.post("/api/jobs/security", ctx -> ctx.status(HttpStatus.ACCEPTED).json(JobRunner.Summary.of(service.startSecurity())));
    app.post("/api/jobs/bench", ctx -> {
      BenchRequest b = ctx.body().isBlank() ? new BenchRequest(null, null, null, null, null) : ctx.bodyAsClass(BenchRequest.class);
      ctx.status(HttpStatus.ACCEPTED).json(JobRunner.Summary.of(service.startBench(b.dsas(),
          b.rates() == null || b.rates().isEmpty() ? List.of(25.0, 50.0, 100.0) : b.rates(),
          b.seconds() == null ? 20 : b.seconds(), b.clients() == null ? 20 : b.clients(), b.nodes())));
    });
    app.get("/api/jobs", ctx -> ctx.json(service.jobs().list()));
    app.get("/api/jobs/{id}", ctx -> {
      JobRunner.Job job = service.jobs().get(ctx.pathParamAsClass("id", Long.class).get());
      if (job == null) {
        ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "No such job"));
      } else {
        ctx.json(job);
      }
    });

    app.sse("/api/events", events::add);

    app.exception(IllegalArgumentException.class, (e, ctx) -> ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage())));
    app.exception(java.util.NoSuchElementException.class,
        (e, ctx) -> ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", e.getMessage())));
    app.exception(java.io.IOException.class, (e, ctx) -> ctx.status(HttpStatus.BAD_GATEWAY).json(Map.of("error", e.getMessage())));
    app.exception(IllegalStateException.class, (e, ctx) -> ctx.status(HttpStatus.CONFLICT).json(Map.of("error", e.getMessage())));
    return app;
  }
}

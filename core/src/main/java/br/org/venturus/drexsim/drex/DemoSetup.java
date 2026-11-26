package br.org.venturus.drexsim.drex;

import br.org.venturus.drexsim.chain.Chain;
import br.org.venturus.drexsim.crypto.Dsa;
import br.org.venturus.drexsim.keystore.Account;
import br.org.venturus.drexsim.keystore.AccountStore;
import br.org.venturus.drexsim.tx.TxFormat;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Builds a small Drex network for demonstrations and for the balance viewer: BCB, two banks, their
 * reserves and Real Tokenizados, and clients: ana and bruno at Banco A, carla at Banco B, daniel at both,
 * fabio disabled by Banco B after a deposit, and eva, never onboarded, who still receives Real Tokenizado
 * (transfer() checks only the sender). The names don't say which bank a client is at: the chain does, and
 * the balance viewer reads it from there.
 * The actors sign with different DSAs, including a classical bank (standard ECDSA transactions) for
 * comparison, so one run exercises every kind of signature the fork accepts.
 *
 * <p>Each run deploys a fresh set of contracts and replaces {@code deployment.json}; accounts are
 * reused from {@code accounts.json}.
 */
public final class DemoSetup {

  /** A demo actor and how it signs. */
  public record Actor(String name, Dsa dsa, TxFormat format) {}

  public static final List<Actor> ACTORS =
      List.of(
          new Actor("bcb", Dsa.SECP256K1_MLDSA44, TxFormat.FLEXIBLE),
          new Actor("bank-a", Dsa.MLDSA44, TxFormat.FLEXIBLE),
          new Actor("reserve-a", Dsa.SECP256K1_FALCON512, TxFormat.FLEXIBLE),
          new Actor("bank-b", Dsa.SECP256K1, TxFormat.STANDARD),
          new Actor("reserve-b", Dsa.SECP256K1, TxFormat.STANDARD),
          new Actor("ana", Dsa.SECP256K1_MLDSA44, TxFormat.FLEXIBLE),
          new Actor("bruno", Dsa.MLDSA87, TxFormat.FLEXIBLE),
          new Actor("carla", Dsa.FALCON512, TxFormat.FLEXIBLE),
          new Actor("daniel", Dsa.MLDSA65, TxFormat.FLEXIBLE),
          new Actor("eva", Dsa.SECP256K1, TxFormat.STANDARD),
          new Actor("fabio", Dsa.FALCON512, TxFormat.FLEXIBLE));

  public static final BigInteger CNPJ_A = BigInteger.valueOf(11111111);
  public static final BigInteger CNPJ_B = BigInteger.valueOf(22222222);

  private final Chain chain;
  private final AccountStore store;
  private final Path dataDir;
  private final Consumer<String> log;

  public DemoSetup(Chain chain, AccountStore store, Path dataDir, Consumer<String> log) {
    this.chain = chain;
    this.store = store;
    this.dataDir = dataDir;
    this.log = log == null ? s -> {} : log;
  }

  public DrexDeployment run() throws Exception {
    checkSupportedDsas();
    for (Actor actor : ACTORS) {
      store.getOrCreate(actor.name(), actor.dsa(), actor.format());
    }
    Account bcb = store.get("bcb");
    Account bankA = store.get("bank-a");
    Account reserveA = store.get("reserve-a");
    Account bankB = store.get("bank-b");
    Account reserveB = store.get("reserve-b");
    Account ana = store.get("ana");
    Account bruno = store.get("bruno");
    Account carla = store.get("carla");
    Account daniel = store.get("daniel");
    Account eva = store.get("eva");
    Account fabio = store.get("fabio");

    log.accept("== Flow 0: network setup");
    DrexDeployment deployment = DrexClient.deployNetwork(chain, bcb, log);
    deployment.save(dataDir);
    DrexClient drex = new DrexClient(chain, deployment, log);

    log.accept("== Flows 1 and 7: participants and their Real Tokenizado");
    drex.onboardParticipant(bcb, bankA, reserveA, CNPJ_A);
    drex.onboardParticipant(bcb, bankB, reserveB, CNPJ_B);
    drex.deployRealTokenizado(bcb, bankA, reserveA, "Banco A", "BRLA", CNPJ_A);
    drex.deployRealTokenizado(bcb, bankB, reserveB, "Banco B", "BRLB", CNPJ_B);
    deployment.save(dataDir);

    log.accept("== Flow 8: clients");
    drex.onboardClient(bankA, "BRLA", ana, new BigInteger("12345678901"), BigInteger.ONE, BigInteger.valueOf(1001), BigInteger.ONE);
    drex.onboardClient(bankA, "BRLA", bruno, new BigInteger("23456789012"), BigInteger.ONE, BigInteger.valueOf(1002), BigInteger.ONE);
    drex.onboardClient(bankB, "BRLB", carla, new BigInteger("34567890123"), BigInteger.TWO, BigInteger.valueOf(2001), BigInteger.ONE);

    log.accept("== Flow 4: Real Digital issued through STR");
    drex.strMint(reserveA, Amounts.parse("1.000.000,00"));
    drex.strMint(reserveB, Amounts.parse("1.000.000,00"));
    drex.strMint(bankA, Amounts.parse("50.000,00"));

    log.accept("== Flow 9: client deposits");
    drex.deposit(bankA, "BRLA", ana, Amounts.parse("10.000,00"));
    drex.deposit(bankA, "BRLA", bruno, Amounts.parse("2.500,00"));
    drex.deposit(bankB, "BRLB", carla, Amounts.parse("7.000,00"));

    log.accept("== Flow 15: judicial block of part of bruno's balance");
    drex.freeze(bankA, "BRLA", bruno, Amounts.parse("500,00"));

    log.accept("== Flow 12: ana (Banco A) pays carla (Banco B)");
    drex.payOneStep(ana, "BRLA", "BRLB", carla.address(), Amounts.parse("1.234,56"));

    log.accept("== Flow 11: bruno pays ana, both at Banco A (only the unfrozen part can move)");
    drex.transfer(bruno, "BRLA", ana.address(), Amounts.parse("300,00"));

    log.accept("== Flow 12: carla (Banco B) pays ana (Banco A)");
    drex.payOneStep(carla, "BRLB", "BRLA", ana.address(), Amounts.parse("100,00"));

    log.accept("== Flow 8: daniel, a client of both banks (his key stays registered at Banco A)");
    drex.onboardClient(bankA, "BRLA", daniel, new BigInteger("45678901234"), BigInteger.ONE, BigInteger.valueOf(1003), BigInteger.ONE);
    drex.enableClient(bankB, "BRLB", daniel);
    drex.deposit(bankA, "BRLA", daniel, Amounts.parse("1.000,00"));
    drex.deposit(bankB, "BRLB", daniel, Amounts.parse("1.000,00"));

    log.accept("== Flow 11: daniel pays eva, who was never onboarded (transfer() checks only the sender)");
    drex.transfer(daniel, "BRLA", eva.address(), Amounts.parse("200,00"));

    log.accept("== Flow 8: fabio, a client Banco B disables after a deposit (the balance stays)");
    drex.onboardClient(bankB, "BRLB", fabio, new BigInteger("56789012345"), BigInteger.TWO, BigInteger.valueOf(2002), BigInteger.ONE);
    drex.deposit(bankB, "BRLB", fabio, Amounts.parse("800,00"));
    drex.disableClient(bankB, "BRLB", fabio);

    deployment.save(dataDir);
    return deployment;
  }

  private void checkSupportedDsas() throws Exception {
    Set<String> supported = new LinkedHashSet<>(chain.supportedDsas());
    Set<String> missing = new LinkedHashSet<>();
    for (Actor actor : ACTORS) {
      if (!supported.contains(actor.dsa().wireName())) {
        missing.add(actor.dsa().wireName());
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "The node does not accept account DSAs " + missing + " (it accepts " + supported + "). "
              + "Start the network with infra/start-network.sh, which enables every account DSA, "
              + "or add them to accounts-supported-dsas on every node.");
    }
  }
}

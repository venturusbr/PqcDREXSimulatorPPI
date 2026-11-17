package br.org.venturus.drexsim.security;

import br.org.venturus.drexsim.chain.Chain;
import br.org.venturus.drexsim.contracts.RealDigital;
import br.org.venturus.drexsim.contracts.RealDigitalEnableAccount;
import br.org.venturus.drexsim.contracts.RealTokenizado;
import br.org.venturus.drexsim.crypto.Dsa;
import br.org.venturus.drexsim.crypto.EcdsaScheme;
import br.org.venturus.drexsim.crypto.HybridScheme;
import br.org.venturus.drexsim.crypto.RawKeyPair;
import br.org.venturus.drexsim.drex.Amounts;
import br.org.venturus.drexsim.drex.DrexClient;
import br.org.venturus.drexsim.drex.DrexDeployment;
import br.org.venturus.drexsim.keystore.Account;
import br.org.venturus.drexsim.scenario.SignerProfile;
import br.org.venturus.drexsim.security.SecurityResult.Outcome;
import br.org.venturus.drexsim.tx.FlexibleTransactionVerifier;
import br.org.venturus.drexsim.tx.SignedTransaction;
import br.org.venturus.drexsim.tx.TransactionSigner;
import br.org.venturus.drexsim.tx.TxFormat;
import br.org.venturus.drexsim.tx.UnsignedTransaction;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

/**
 * Attacks the live network and checks that it resists them (docs/pqc/08-security.md of
 * besu-pqc-ppi gives the threat model):
 *
 * <ol>
 *   <li><b>protocol</b>: forged, tampered, relabelled or replayed transactions, sent raw with
 *       {@code eth_sendRawTransaction}. Each must be rejected by the node or, for recoverable ECDSA,
 *       be attributed to some other sender, never to the victim;
 *   <li><b>quantum</b>: a simulated quantum adversary that knows every ECDSA private key (as Shor's
 *       algorithm would give it). Against a classical deployment it drains a reserve, mints as BCB
 *       and moves a client's funds; against a hybrid deployment every strategy must fail, which is
 *       checked on the balances and roles themselves;
 *   <li><b>contract</b>: flaws of the pilot contracts, which no signature scheme fixes.
 * </ol>
 */
public final class SecuritySuite {

  private static final BigInteger CNPJ_A = BigInteger.valueOf(11111111);
  private static final BigInteger CNPJ_B = BigInteger.valueOf(22222222);
  private static final BigInteger CPF_X = new BigInteger("12345678901");
  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * Gas limit of the protocol-test transactions: above the base cost of every DSA, so a relabelled
   * transaction reaches the signature check instead of failing on intrinsic gas.
   */
  private static final BigInteger PROTOCOL_GAS = BigInteger.valueOf(300_000);

  /** Gas limit of the adversary's calls: fixed, so they are mined (and revert) instead of failing estimation. */
  private static final BigInteger ATTACK_GAS = BigInteger.valueOf(600_000);

  /** DSAs whose transactions are attacked at protocol level. */
  public static final List<SignerProfile> PROTOCOL_PROFILES = SignerProfile.all();

  /** Hybrid profile defended against the quantum adversary. */
  public static final SignerProfile DEFENDED = new SignerProfile(Dsa.SECP256K1_MLDSA44, TxFormat.FLEXIBLE);

  private final String rpcUrl;
  private final Consumer<String> log;
  private final List<SecurityResult> results = new ArrayList<>();
  private Chain chain;

  public SecuritySuite(String rpcUrl, Consumer<String> log) {
    this.rpcUrl = rpcUrl;
    this.log = log == null ? s -> {} : log;
  }

  public List<SecurityResult> run() throws Exception {
    try (Chain c = new Chain(rpcUrl)) {
      chain = c;
      log.accept("== Protocol attacks");
      for (SignerProfile p : PROTOCOL_PROFILES) {
        protocolAttacks(p);
      }
      log.accept("== Quantum adversary against a classical deployment");
      quantumAdversary(SignerProfile.BASELINE);
      log.accept("== Quantum adversary against a hybrid deployment");
      Deployment hybrid = quantumAdversary(DEFENDED);
      log.accept("== Contract findings");
      contractFindings(hybrid);
    }
    return results;
  }

  // ================================================================== protocol

  private void protocolAttacks(SignerProfile p) throws Exception {
    Account victim = Account.generate("victim", p.dsa(), p.format());
    String target = p.label();
    String other = Account.generate("other", Dsa.SECP256K1, TxFormat.FLEXIBLE).address();

    // A valid transaction, mined, then replayed.
    SignedTransaction valid = TransactionSigner.sign(transfer(victim, other, nonce(victim)), victim);
    Sent first = send(valid.raw());
    if (!first.accepted()) {
      add("protocol", "Valid transaction (control)", target, Outcome.SUCCEEDED, Outcome.BLOCKED, first.error());
      return;
    }
    waitForReceipt(first.hash());
    judge("protocol", "Replay of a mined transaction", target, victim, send(valid.raw()));

    // Wrong chain id.
    UnsignedTransaction tx = transfer(victim, other, nonce(victim));
    UnsignedTransaction otherChain = new UnsignedTransaction(tx.chainId() + 1, tx.nonce(), tx.maxPriorityFeePerGas(),
        tx.maxFeePerGas(), tx.gasLimit(), tx.to(), tx.value(), tx.data());
    judge("protocol", "Signed for another chain id", target, victim, send(TransactionSigner.sign(otherChain, victim).raw()));

    if (p.format() == TxFormat.STANDARD) {
      byte[] raw = TransactionSigner.sign(tx, victim).raw().clone();
      raw[raw.length - 1] ^= 0x01; // last byte of s
      judge("protocol", "Tampered signature", target, victim, send(raw));
      return;
    }

    FlexibleTransactionVerifier.Decoded d = FlexibleTransactionVerifier.decode(TransactionSigner.sign(tx, victim).raw());
    Dsa dsa = d.dsa();

    byte[] sig = d.signature().clone();
    sig[sig.length / 2] ^= 0x01;
    judge("protocol", "Tampered signature", target, victim, send(TransactionSigner.encodeFlexible(d.fields(), dsa, d.carriedPublicKey(), sig)));

    UnsignedTransaction redirected = new UnsignedTransaction(tx.chainId(), tx.nonce(), tx.maxPriorityFeePerGas(),
        tx.maxFeePerGas(), tx.gasLimit(), Account.generate("thief", Dsa.SECP256K1, TxFormat.FLEXIBLE).address(),
        tx.value(), tx.data());
    judge("protocol", "Recipient changed after signing", target, victim,
        send(TransactionSigner.encodeFlexible(redirected, dsa, d.carriedPublicKey(), d.signature())));

    Dsa relabel = relabelTarget(dsa);
    byte[] relabelledKey = relabel.carried() == Dsa.Carried.NONE ? null
        : d.carriedPublicKey() != null ? d.carriedPublicKey() : new byte[relabel.carriedPublicKeyLength()];
    judge("protocol", "dsaType relabelled to " + relabel.wireName(), target, victim,
        send(TransactionSigner.encodeFlexible(d.fields(), relabel, relabelledKey, d.signature())));

    if (d.carriedPublicKey() == null) {
      return; // the remaining attacks target the carried post-quantum key
    }

    byte[] attackerKey = dsa.carriedPublicKey(dsa.scheme().generateKeyPair().publicKey());
    judge("protocol", "Carried public key replaced", target, victim,
        send(TransactionSigner.encodeFlexible(d.fields(), dsa, attackerKey, d.signature())));

    byte[] shortKey = Arrays.copyOf(d.carriedPublicKey(), d.carriedPublicKey().length - 1);
    judge("protocol", "Carried public key of the wrong length", target, victim,
        send(TransactionSigner.encodeFlexible(d.fields(), dsa, shortKey, d.signature())));

    if (dsa == Dsa.FALCON512 || dsa == Dsa.SECP256K1_FALCON512) {
      byte[] noise = new byte[200];
      RANDOM.nextBytes(noise);
      byte[] padded = concat(d.signature(), noise);
      judge("protocol", "Falcon signature beyond 752 bytes", target, victim,
          send(TransactionSigner.encodeFlexible(d.fields(), dsa, d.carriedPublicKey(), padded)));
    }

    if (dsa.isHybrid()) {
      byte[] forged = Arrays.copyOf(d.signature(), d.signature().length);
      byte[] noise = new byte[forged.length - EcdsaScheme.SIGNATURE_LENGTH];
      RANDOM.nextBytes(noise);
      System.arraycopy(noise, 0, forged, EcdsaScheme.SIGNATURE_LENGTH, noise.length);
      judge("protocol", "Hybrid: valid ECDSA half, random PQC half", target, victim,
          send(TransactionSigner.encodeFlexible(d.fields(), dsa, d.carriedPublicKey(), forged)));
    }
  }

  /** A same-kind DSA to relabel a transaction with (algorithm substitution). */
  private static Dsa relabelTarget(Dsa dsa) {
    return switch (dsa) {
      case SECP256K1 -> Dsa.SECP256R1;
      case SECP256R1 -> Dsa.SECP256K1;
      case MLDSA44 -> Dsa.MLDSA65;
      case MLDSA65 -> Dsa.MLDSA87;
      case MLDSA87 -> Dsa.MLDSA44;
      case FALCON512 -> Dsa.MLDSA44;
      case SECP256K1_MLDSA44 -> Dsa.MLDSA44; // same 1312-byte carried key, signature without the ECDSA half
      case SECP256K1_FALCON512 -> Dsa.FALCON512;
    };
  }

  /**
   * A protocol attack is blocked when the node refuses the transaction, or accepts it as a
   * transaction of some other sender (only possible with recoverable ECDSA).
   */
  private void judge(String category, String attack, String target, Account victim, Sent sent) throws Exception {
    if (!sent.accepted()) {
      add(category, attack, target, Outcome.BLOCKED, Outcome.BLOCKED, "rejected: " + sent.error());
      return;
    }
    // The node's view of the sender; a transaction with a future nonce for its (other) sender may
    // not be returned, in which case the sender is derived offline the same way the node does.
    Optional<Transaction> tx = chain.web3j().ethGetTransactionByHash(sent.hash()).send().getTransaction();
    String from = tx.map(Transaction::getFrom).orElseGet(() -> offlineSender(sent.raw()));
    boolean asVictim = from.equalsIgnoreCase(victim.address());
    add(category, attack, target, Outcome.BLOCKED, asVictim ? Outcome.SUCCEEDED : Outcome.BLOCKED,
        asVictim ? "accepted as the victim " + from : "accepted, but as another sender " + from + " (not the victim)");
  }

  // ================================================================== quantum adversary

  /** A small Drex network: BCB, two banks, a funded reserve and a funded client. */
  private record Deployment(SignerProfile profile, DrexDeployment d, DrexClient drex,
      Account bcb, Account bankA, Account reserveA, Account bankB, Account clientX) {}

  private Deployment deploy(SignerProfile p) throws Exception {
    Account bcb = Account.generate("bcb", p.dsa(), p.format());
    Account bankA = Account.generate("bank-a", p.dsa(), p.format());
    Account reserveA = Account.generate("reserve-a", p.dsa(), p.format());
    Account bankB = Account.generate("bank-b", p.dsa(), p.format());
    Account reserveB = Account.generate("reserve-b", p.dsa(), p.format());
    Account clientX = Account.generate("client-x", p.dsa(), p.format());
    DrexDeployment d = DrexClient.deployNetwork(chain, bcb, null);
    DrexClient drex = new DrexClient(chain, d, null);
    drex.onboardParticipant(bcb, bankA, reserveA, CNPJ_A);
    drex.onboardParticipant(bcb, bankB, reserveB, CNPJ_B);
    drex.deployRealTokenizado(bcb, bankA, reserveA, "Banco A", "BRLA", CNPJ_A);
    drex.onboardClient(bankA, "BRLA", clientX, CPF_X, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE);
    drex.strMint(reserveA, Amounts.parse("1.000.000,00"));
    drex.deposit(bankA, "BRLA", clientX, Amounts.parse("10.000,00"));
    return new Deployment(p, d, drex, bcb, bankA, reserveA, bankB, clientX);
  }

  /** What the adversary tries with a victim's stolen key: one goal, three victims. */
  private enum Goal {
    DRAIN_RESERVE("Drain bank A's reserve (Real Digital)"),
    MINT_AS_BCB("Mint Real Digital as BCB"),
    MOVE_CLIENT_FUNDS("Move a client's Real Tokenizado");

    final String text;

    Goal(String text) {
      this.text = text;
    }
  }

  private Deployment quantumAdversary(SignerProfile p) throws Exception {
    Deployment net = deploy(p);
    // The adversary's own wallet: classical, not enabled anywhere.
    Account attacker = Account.generate("attacker", Dsa.SECP256K1, TxFormat.STANDARD);

    for (Goal goal : Goal.values()) {
      Account victim = switch (goal) {
        case DRAIN_RESERVE -> net.reserveA();
        case MINT_AS_BCB -> net.bcb();
        case MOVE_CLIENT_FUNDS -> net.clientX();
      };
      byte[] ecdsaKey = Arrays.copyOf(victim.privateKey(), EcdsaScheme.PRIVATE_KEY_LENGTH); // what Shor gives

      if (!p.dsa().isQuantumResistant()) {
        Account stolen = Account.fromPrivateKey("stolen", victim.dsa(), victim.format(), ecdsaKey);
        attempt(net, goal, attacker, "Stolen ECDSA key, signing as the victim", stolen, Outcome.SUCCEEDED);
        continue;
      }
      // Hybrid victim: the ECDSA half is known, the ML-DSA half is not.
      attempt(net, goal, attacker, "Downgrade to a flexible ECDSA-only transaction",
          Account.fromPrivateKey("downgrade-flexible", Dsa.SECP256K1, TxFormat.FLEXIBLE, ecdsaKey), Outcome.BLOCKED);
      attempt(net, goal, attacker, "Downgrade to a standard EIP-1559 transaction",
          Account.fromPrivateKey("downgrade-standard", Dsa.SECP256K1, TxFormat.STANDARD, ecdsaKey), Outcome.BLOCKED);
      RawKeyPair ownPq = ((HybridScheme) p.dsa().scheme()).postQuantum().generateKeyPair();
      attempt(net, goal, attacker, "Hybrid with the victim's ECDSA key and the attacker's own PQC key",
          Account.fromPrivateKey("own-pq", p.dsa(), p.format(), concat(ecdsaKey, ownPq.privateKey())), Outcome.BLOCKED);
      forgedPostQuantumHalf(net, goal, attacker, victim, ecdsaKey, ownPq);
    }
    return net;
  }

  /** Runs the goal's calls signed by {@code signer} and judges by the resulting state. */
  private void attempt(Deployment net, Goal goal, Account attacker, String strategy, Account signer, Outcome expected)
      throws Exception {
    State before = state(net, attacker);
    String error = null;
    try {
      // Each call is mined even when it reverts, so the result is on chain, not only in an estimate.
      switch (goal) {
        case DRAIN_RESERVE -> error = mined(() -> rdFixed(net, signer).transfer(attacker.address(), before.reserveA).send());
        case MINT_AS_BCB -> {
          String enable = mined(() -> rdFixed(net, signer).enableAccount(attacker.address()).send());
          String mint = mined(() -> rdFixed(net, signer).mint(attacker.address(), Amounts.parse("1.000.000.000,00")).send());
          error = enable == null && mint == null ? null : "enableAccount: " + (enable == null ? "ok" : enable)
              + "; mint: " + (mint == null ? "ok" : mint);
        }
        case MOVE_CLIENT_FUNDS -> error = mined(() -> rtFixed(net, signer).transfer(attacker.address(), before.clientX).send());
      }
    } catch (Exception e) {
      error = shortMessage(e);
    }
    State after = state(net, attacker);
    boolean gained = after.gainedSince(before);
    String evidence = String.format("signer %s (victim %s); attacker RD %s, RT %s, enabled %s%s",
        abbreviate(signer.address()), abbreviate(victimOf(net, goal).address()), Amounts.format(after.attackerRd),
        Amounts.format(after.attackerRt), after.attackerEnabled, error == null ? "" : "; " + error);
    add("quantum", goal.text + ": " + strategy, net.profile().label(), expected,
        gained ? Outcome.SUCCEEDED : Outcome.BLOCKED, evidence);
    if (gained && expected == Outcome.SUCCEEDED) {
      log.accept("   (as expected for a classical victim: " + evidence + ")");
    }
  }

  /**
   * The strongest attempt on a hybrid account: carry the victim's own ML-DSA public key, so the
   * sender would be the victim, with a genuine ECDSA half and an ML-DSA half the attacker cannot
   * produce (signed with its own key).
   */
  private void forgedPostQuantumHalf(Deployment net, Goal goal, Account attacker, Account victim, byte[] ecdsaKey,
      RawKeyPair ownPq) throws Exception {
    Dsa dsa = victim.dsa();
    HybridScheme hybrid = (HybridScheme) dsa.scheme();
    String to;
    String data;
    switch (goal) {
      case DRAIN_RESERVE -> {
        to = net.d().realDigital;
        data = call("transfer", attacker.address(), Amounts.parse("1.000.000,00"));
      }
      case MINT_AS_BCB -> {
        to = net.d().realDigital;
        data = call("mint", attacker.address(), Amounts.parse("1.000.000.000,00"));
      }
      default -> {
        to = net.d().tokenized("BRLA").orElseThrow().address();
        data = call("transfer", attacker.address(), Amounts.parse("10.000,00"));
      }
    }
    UnsignedTransaction tx = new UnsignedTransaction(chain.chainId(), nonce(victim), BigInteger.ZERO, BigInteger.ZERO,
        ATTACK_GAS, to, BigInteger.ZERO, Numeric.hexStringToByteArray(data));
    byte[] carried = dsa.carriedPublicKey(victim.publicKey());
    byte[] hash = TransactionSigner.flexibleSigningHash(tx, dsa, carried);
    byte[] ecdsaSig = hybrid.classical().sign(hash, ecdsaKey);
    byte[] pqSig = hybrid.postQuantum().sign(concat(hash, ecdsaSig), ownPq.privateKey());
    State before = state(net, attacker);
    Sent sent = send(TransactionSigner.encodeFlexible(tx, dsa, carried, concat(ecdsaSig, pqSig)));
    if (sent.accepted()) {
      waitForReceipt(sent.hash());
    }
    State after = state(net, attacker);
    boolean gained = after.gainedSince(before);
    add("quantum", goal.text + ": victim's PQC key carried, PQC half forged", net.profile().label(), Outcome.BLOCKED,
        gained ? Outcome.SUCCEEDED : Outcome.BLOCKED,
        sent.accepted() ? "accepted by the node (" + sent.hash() + ")" : "rejected: " + sent.error());
  }

  private record State(BigInteger reserveA, BigInteger clientX, BigInteger attackerRd, BigInteger attackerRt,
      boolean attackerEnabled) {
    /** Whether the attacker got funds or access it did not have in {@code before}. */
    boolean gainedSince(State before) {
      return attackerRd.compareTo(before.attackerRd) > 0 || attackerRt.compareTo(before.attackerRt) > 0
          || (attackerEnabled && !before.attackerEnabled);
    }
  }

  private State state(Deployment net, Account attacker) throws Exception {
    RealDigital rd = RealDigital.load(net.d().realDigital, chain.web3j(), chain.readOnly(), chain.gasProvider());
    RealTokenizado rt = RealTokenizado.load(net.d().tokenized("BRLA").orElseThrow().address(), chain.web3j(),
        chain.readOnly(), chain.gasProvider());
    return new State(rd.balanceOf(net.reserveA().address()).send(), rt.balanceOf(net.clientX().address()).send(),
        rd.balanceOf(attacker.address()).send(), rt.balanceOf(attacker.address()).send(),
        rd.verifyAccount(attacker.address()).send());
  }

  private static Account victimOf(Deployment net, Goal goal) {
    return switch (goal) {
      case DRAIN_RESERVE -> net.reserveA();
      case MINT_AS_BCB -> net.bcb();
      case MOVE_CLIENT_FUNDS -> net.clientX();
    };
  }

  // ================================================================== contract findings

  private void contractFindings(Deployment net) throws Exception {
    String target = net.profile().label();
    Account outsider = Account.generate("outsider", Dsa.SECP256K1, TxFormat.STANDARD);
    byte[] key = DrexClient.clientKey(CPF_X);

    // KeyDictionary.addAccount does not check that the key exists: bank B takes bank A's client key.
    String detail;
    try {
      net.drex().keyDictionary(net.bankB()).addAccount(key, CPF_X, BigInteger.TWO, BigInteger.ONE, BigInteger.ONE,
          outsider.address()).send();
      detail = "getWallet(key) = " + net.drex().keyDictionary(net.bcb()).getWallet(key).send();
    } catch (Exception e) {
      detail = shortMessage(e);
    }
    String wallet = net.drex().keyDictionary(net.bcb()).getWallet(key).send();
    add("contract", "Another participant overwrites a client's key (KeyDictionary.addAccount)", target,
        Outcome.SUCCEEDED, wallet.equalsIgnoreCase(outsider.address()) ? Outcome.SUCCEEDED : Outcome.BLOCKED, detail);

    // transfer() does not check the recipient: tokens can be sent to a wallet that cannot move them.
    BigInteger amount = Amounts.parse("1,00");
    String stuck;
    try {
      rt(net, net.clientX()).transfer(outsider.address(), amount).send();
      boolean canMove = true;
      try {
        rt(net, outsider).transfer(net.clientX().address(), amount).send();
      } catch (Exception e) {
        canMove = false;
      }
      stuck = "recipient balance " + Amounts.format(rt(net, net.bcb()).balanceOf(outsider.address()).send())
          + ", can move it back: " + canMove;
    } catch (Exception e) {
      stuck = shortMessage(e);
    }
    BigInteger received = rt(net, net.bcb()).balanceOf(outsider.address()).send();
    add("contract", "Transfer to a wallet that is not enabled (funds get stuck)", target, Outcome.SUCCEEDED,
        received.signum() > 0 ? Outcome.SUCCEEDED : Outcome.BLOCKED, stuck);

    // RealDigitalEnableAccount lets any participant enable any address in RealDigital.
    try {
      RealDigitalEnableAccount.load(net.d().realDigitalEnableAccount, chain.web3j(), chain.managerFor(net.bankA()),
          chain.gasProvider()).enableAccount(outsider.address()).send();
    } catch (Exception ignored) {
      // judged on the state below
    }
    boolean enabled = rd(net, net.bcb()).verifyAccount(outsider.address()).send();
    add("contract", "A participant enables an arbitrary wallet in RealDigital", target, Outcome.SUCCEEDED,
        enabled ? Outcome.SUCCEEDED : Outcome.BLOCKED, "verifyAccount(outsider) = " + enabled);
  }

  // ================================================================== helpers

  private static String offlineSender(byte[] raw) {
    try {
      if (raw[0] == TransactionSigner.FLEXIBLE_EIP1559_TYPE) {
        String sender = FlexibleTransactionVerifier.verify(raw).sender();
        return sender == null ? "(unresolvable)" : sender;
      }
      return ((org.web3j.crypto.SignedRawTransaction) org.web3j.crypto.TransactionDecoder.decode(Numeric.toHexString(raw)))
          .getFrom();
    } catch (Exception e) {
      return "(unresolvable)";
    }
  }

  private record Sent(byte[] raw, String hash, String error) {
    boolean accepted() {
      return error == null;
    }
  }

  private Sent send(byte[] raw) throws Exception {
    EthSendTransaction r = chain.web3j().ethSendRawTransaction(Numeric.toHexString(raw)).send();
    return r.hasError() ? new Sent(raw, null, r.getError().getMessage()) : new Sent(raw, r.getTransactionHash(), null);
  }

  private void waitForReceipt(String hash) throws Exception {
    for (int i = 0; i < 120; i++) {
      Optional<TransactionReceipt> r = chain.web3j().ethGetTransactionReceipt(hash).send().getTransactionReceipt();
      if (r.isPresent()) {
        return;
      }
      Thread.sleep(500);
    }
    throw new IllegalStateException("No receipt for " + hash);
  }

  private BigInteger nonce(Account a) throws Exception {
    return chain.web3j().ethGetTransactionCount(a.address(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
  }

  private UnsignedTransaction transfer(Account from, String to, BigInteger nonce) {
    return new UnsignedTransaction(chain.chainId(), nonce, BigInteger.ZERO, BigInteger.ZERO,
        PROTOCOL_GAS, to, BigInteger.ZERO, new byte[0]);
  }

  private static String call(String function, String address, BigInteger amount) {
    return FunctionEncoder.encode(new Function(function, List.of(new Address(address), new Uint256(amount)), List.of()));
  }

  @FunctionalInterface
  private interface Call {
    TransactionReceipt run() throws Exception;
  }

  /** Runs a call sent with a fixed gas limit; returns null when it succeeded, else why it failed on chain. */
  private static String mined(Call call) {
    try {
      call.run();
      return null;
    } catch (org.web3j.protocol.exceptions.TransactionException e) {
      return "mined in block " + e.getTransactionReceipt().map(r -> r.getBlockNumber().toString()).orElse("?")
          + " with status " + e.getTransactionReceipt().map(TransactionReceipt::getStatus).orElse("?") + " (reverted)";
    } catch (Exception e) {
      return shortMessage(e);
    }
  }

  private org.web3j.tx.gas.ContractGasProvider fixedGas() {
    return new org.web3j.tx.gas.StaticEIP1559GasProvider(chain.chainId(), BigInteger.ZERO, BigInteger.ZERO, ATTACK_GAS);
  }

  private RealDigital rdFixed(Deployment net, Account as) {
    return RealDigital.load(net.d().realDigital, chain.web3j(), chain.managerFor(as), fixedGas());
  }

  private RealTokenizado rtFixed(Deployment net, Account as) {
    return RealTokenizado.load(net.d().tokenized("BRLA").orElseThrow().address(), chain.web3j(), chain.managerFor(as),
        fixedGas());
  }

  private RealDigital rd(Deployment net, Account as) {
    return RealDigital.load(net.d().realDigital, chain.web3j(), chain.managerFor(as), chain.gasProvider());
  }

  private RealTokenizado rt(Deployment net, Account as) {
    return RealTokenizado.load(net.d().tokenized("BRLA").orElseThrow().address(), chain.web3j(), chain.managerFor(as),
        chain.gasProvider());
  }

  private void add(String category, String attack, String target, Outcome expected, Outcome observed, String evidence) {
    SecurityResult r = new SecurityResult(category, attack, target, expected, observed, evidence);
    results.add(r);
    log.accept(String.format("%-4s %-20s %-60s %s", r.asExpected() ? "ok" : "FAIL", target, attack, observed));
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  private static String abbreviate(String address) {
    return address.substring(0, 8) + ".." + address.substring(address.length() - 4);
  }

  private static String shortMessage(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    m = m.replaceAll("\\s+", " ");
    return m.length() > 160 ? m.substring(0, 160) + "..." : m;
  }
}

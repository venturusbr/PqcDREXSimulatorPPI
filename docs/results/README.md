# Proof of concept results

Results of running the Real Digital (Drex) pilot contracts on besu-pqc-ppi, a Besu fork that accepts post-quantum (PQC) and hybrid signatures. They cover the four goals of the proof of concept: install and configure the platform, simulate Drex operations on it, test its security against quantum-era attacks, and measure its performance and scalability.

All numbers come from the runs whose reports are in this directory, made with the simulator's `flows`, `security` and `bench` commands (see the [README](../../README.md#evaluating-the-platform)).

| Report | Contents |
|---|---|
| [flows.md](flows.md) / [flows.csv](flows.csv) | The 19 use-case flows under every signature algorithm: pass/fail, gas, bytes, one line per transaction |
| [security.md](security.md) / [security.csv](security.csv) | Protocol attacks, simulated quantum adversary, contract findings |
| [bench-4-validators/](bench-4-validators/bench.md) | Load tests, 6 signature algorithms × 5 rates, 4 validators |
| [scalability-validators/](scalability-validators/bench.md) | Load tests with 4 and 7 validators |
| [scalability-gas-limit/](scalability-gas-limit/bench.md) | Load tests with a 200M block gas limit |

## Test environment

| Item | Value |
|---|---|
| Client | besu-pqc-ppi, image `hyperledger/besu:26.1.0-pqc-dfa0d1d`, built from commit `dfa0d1d` |
| Network | besu-qbft, QBFT, 4 validators (7 in one test), validator keys SECP256K1-MLDSA44, 2 s block period, 50M block gas limit (200M in one test), Prague from genesis, zero gas price |
| Account DSAs enabled | SECP256K1, SECP256R1, MLDSA44, MLDSA65, MLDSA87, FALCON512, SECP256K1-MLDSA44, SECP256K1-FALCON512 |
| Contracts | wireshape/real-digital-smart-contracts at `93b3192`, Solidity 0.8.24, OpenZeppelin 4.9.6 |
| Host | One Windows 11 machine, 12 cores, 16 GB for Docker. Every node and the load generator ran on it |

**Baseline.** "SECP256K1 (std)" is standard Ethereum: EIP-1559 transactions (type `0x02`) signed with ECDSA. Every other column uses besu-pqc-ppi's flexible transactions (type `0x42`). Flexible SECP256K1 is included to separate the cost of the format from the cost of the algorithm.

## 1. Installation and compatibility

- `infra/start-network.sh` starts the network from besu-qbft with every account DSA enabled. Validators sign consensus messages with SECP256K1-MLDSA44, so the chain's blocks are themselves post-quantum signed.
- The pilot contracts run **unchanged**: the fork changes how transactions are signed, not the EVM. The same bytecode passes the same tests on the standard EVM (Hardhat) and on besu-pqc-ppi.
- The simulator's signer reproduces byte for byte the transactions of besu-flex-wallet-ppi, whose transactions the fork accepts. On the network, the node decodes them with the expected `dsaType`, signature and key sizes, and derives the same sender address.
- Compatibility gaps an integrator must handle, all documented by the fork and all handled by the simulator:
  - There is no `eth_sendTransaction` for these accounts: transactions are signed client-side.
  - `eth_estimateGas` simulates a standard transaction, so it under-estimates by the extra base cost of the DSA.
  - The default besu-qbft configuration enables only SECP256K1, SECP256R1 and the two hybrids for accounts.

## 2. Drex operations under every algorithm

**All 19 flows pass under all 9 signature configurations: 171 of 171.** Each run deploys its own contracts and checks the state each flow must produce, including 12 calls that must be refused (e.g. a swap without allowance, spending a frozen balance, a payer executing its own two-step proposal). The flows cover network setup, onboarding, STR issuance and redemption, inter-bank Real Digital transfers, Real Tokenizado issuance, deposits, withdrawals and transfers, one- and two-step inter-bank swaps, key portability, freezing, forced transfer and redemption, emergency pause and address discovery.

Cost of the whole suite (74 transactions), relative to the classical baseline:

| Signer | Gas used | vs. baseline | Avg. transaction size |
|---|---|---|---|
| SECP256K1 (std) | 14.0 M | – | 751 B |
| SECP256K1 (flexible) | 14.0 M | +0% | 754 B |
| SECP256R1 | 14.8 M | +6% | 755 B |
| FALCON512 | 15.9 M | +13% | 2,246 B |
| SECP256K1-FALCON512 | 16.2 M | +15% | 2,311 B |
| MLDSA44 | 18.5 M | +32% | 4,427 B |
| SECP256K1-MLDSA44 | 18.8 M | +34% | 4,492 B |
| MLDSA65 | 20.6 M | +47% | 5,956 B |
| MLDSA87 | 23.3 M | +67% | 7,914 B |

The flexible format itself costs nothing: flexible SECP256K1 uses the same gas as the standard transaction. The overhead comes from the per-DSA base cost the fork charges for verifying and carrying larger signatures and keys. It is fixed per transaction, so it weighs most on the simplest operations: an inter-bank Real Digital transfer costs +105% with MLDSA44 and +214% with MLDSA87, while a contract deployment costs only +14% and +28%.

## 3. Security

**77 of 77 checks behaved as expected.** Details in [security.md](security.md).

**Protocol attacks (59 checks, all blocked).** These transactions were sent raw to the node for every DSA: replays, a signature for another chain id, tampered signatures, a recipient changed after signing, a relabelled `dsaType`, a carried public key replaced or truncated, an oversized Falcon signature, and a hybrid with a valid ECDSA half and a random PQC half. Every post-quantum and hybrid forgery was rejected by the node (`Invalid signature`, `Invalid params`, `Wrong chainId`, `Nonce too low`). For recoverable ECDSA, a tampered transaction is accepted as coming from some *other* address, never from the victim, which is the expected behaviour of public-key recovery.

**Simulated quantum adversary (15 checks).** The adversary knows every ECDSA private key, as Shor's algorithm would give it.

- **Classical deployment:** all three attacks succeed. The adversary drains a bank's reserve (R$ 1,000,000.00), signs as BCB to enable itself and mint R$ 1,000,000,000.00, and moves a client's Real Tokenizado. This is the risk the platform addresses.
- **Hybrid deployment (SECP256K1-MLDSA44):** all twelve attempts fail, and balances and roles are unchanged.
  - Three strategies have the attacker sign with the ECDSA key it knows: a downgrade to an ECDSA-only flexible transaction, a downgrade to a standard transaction, and a hybrid built from the stolen ECDSA key plus the attacker's own ML-DSA key. These transactions were **mined**, but as different accounts: a hybrid address commits to both public keys. They reverted on chain.
  - Carrying the victim's ML-DSA key with a forged ML-DSA half was rejected by the node as `Invalid signature`.

**Contract findings (3).** Flaws of the pilot contracts that no signature scheme fixes, reproduced on the PQC network:

1. Any participant can overwrite another bank's client key in KeyDictionary.
2. `transfer` does not check the recipient, so funds can be sent to a wallet that is not enabled, where they get stuck.
3. Any participant can enable any address in RealDigital through RealDigitalEnableAccount.

Two more were found while building the tests and are documented in [contracts-reference.md](../contracts-reference.md):

- `moveAndBurn` reverts unless the token contract itself is enabled.
- SwapTwoSteps proposal ids start at 1.

**Not covered.** Forged consensus messages need a malicious validator on the p2p layer. besu-pqc-ppi covers them in its own consensus tests under every node DSA. Confidentiality is out of scope: PQC signatures protect the authenticity and integrity of transactions, they do not encrypt them.

## 4. Performance

The workload: clients of two banks paying each other, 70% same-bank Real Tokenizado transfers and 30% inter-bank swaps (which burn, move Real Digital between reserves and mint). Each level lasts 30 s at a fixed rate, with 100 clients spread over the 4 nodes. Details are in [bench-4-validators/bench.md](bench-4-validators/bench.md).

| Signer | Highest sustained rate | p95 latency there | Gas per tx | Gas-bound ceiling | Avg tx size |
|---|---|---|---|---|---|
| SECP256K1 (std) | 300 TPS (273 included) | 6.7 s | 65,884 | 302 TPS | 194 B |
| FALCON512 | 200 TPS | 3.1 s | 91,570 | 276 TPS | 1,688 B |
| SECP256K1-FALCON512 | 200 TPS | 4.1 s | 95,428 | 259 TPS | 1,753 B |
| MLDSA44 | 100 TPS | 2.8 s | 126,684 | 199 TPS | 3,868 B |
| SECP256K1-MLDSA44 | 100 TPS | 3.5 s | 130,233 | 191 TPS | 3,933 B |
| MLDSA87 | 100 TPS | 5.1 s | 191,177 | 121 TPS | 7,355 B |

Up to 100 TPS every algorithm keeps up, with a median latency of 1.4–3.2 s (one or two 2 s blocks). Above that, **the block gas limit is the bottleneck**: each algorithm saturates close to its gas-bound ceiling (block gas limit ÷ gas per transaction ÷ block interval), with blocks 92–100% full. CPU was not the limit: the validators averaged 1–2.4 cores each out of 12 on the host. Memory grows with signature size, peaking at 1.2 GB per node with ECDSA and 2.2 GB with ML-DSA-87.

Two consequences:

- **Which algorithm runs the accounts sets the capacity.** On the same network, Falcon-512's gas-bound ceiling is about 1.4× that of ML-DSA-44 and 2.3× that of ML-DSA-87. Falcon is the cheapest post-quantum option, but its signatures are variable-length and Falcon *node* keys are still experimental in the fork; that limitation doesn't apply to Falcon *account* keys.
- **Hybrids cost little more than their PQC half alone:** about 3–4% of the gas, and 65 more bytes per transaction.

## 5. Scalability

**Block gas limit.** Raising the limit 4× (to 200M) did **not** raise throughput. Blocks grew to 2–6 MB and the block interval stretched from 2 s to 6–12 s: QBFT logged round changes, because those blocks weren't agreed on within the round timeout. ML-DSA-87 went from about 105 to about 130 TPS included, and SECP256K1-MLDSA44 got worse. The limit moves from gas to producing and propagating large blocks. Scaling further needs tuning together:

- the block gas limit and block period;
- the QBFT `requesttimeoutseconds`;
- the network between validators.

**Validator count.** With 7 validators on the same host, every rate degraded: blocks every 3–16 s instead of 2 s, no round changes, and a similar share of empty blocks as with 4 validators. The 4-validator runs reproduced the earlier measurements within a few percent, so the test is repeatable. The 7-validator slowdown most likely reflects 7 Java nodes contending for one machine, not QBFT itself. **The effect of the validator count on throughput is inconclusive on this test bed**: measuring it needs validators on separate machines. `infra/scalability.sh` runs the same procedure on any image and validator set.

## Conclusions

1. Drex operations run on the post-quantum platform without changing the contracts, under every algorithm the fork offers, including hybrids that keep ECDSA for compatibility.
2. Against an adversary that breaks ECDSA, hybrid and pure post-quantum accounts protect funds and privileged roles: every forgery and downgrade attempt failed, while the same attacks emptied a classical deployment. Admin and authority wallets (BCB, participants' reserves) are the first that should move to them.
3. The cost is mostly gas and bytes. The verification CPU per transaction is not the limit here: gas per transaction grows 13–67% over a whole flow and up to 3× on simple transfers, and transactions grow from about 0.2 KB to 1.7–7.4 KB. With the default 50M block gas limit, sustained throughput falls from about 300 TPS (ECDSA) to 200 TPS (Falcon-512) and 100 TPS (ML-DSA).
4. Raising capacity means tuning the block gas limit, block period and consensus timeouts together, on a test bed with validators on separate machines. That is the next step for the scalability evaluation.

## Limitations

- All nodes and the load generator shared one machine, so absolute throughput and latency depend on it. Comparisons between algorithms under the same conditions are the meaningful part.
- Each load level lasted 30 s. Longer runs would capture state growth and memory behaviour over time.
- The pilot contracts are Wire Labs' reconstruction from the ABIs BCB published, not the official implementation.

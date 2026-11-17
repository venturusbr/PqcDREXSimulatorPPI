# PqcDREXSimulatorPPI

A front-end and smart contracts that reproduce Brazil's Real Digital (Drex) pilot operations, running on a Besu PQC network modified to accept post-quantum (PQC) and hybrid signatures.

## Repository layout

| Path | What it is |
|---|---|
| [contracts/](contracts) | Hardhat project: the Drex contracts (pinned copy, OpenZeppelin 4.9.6), functional tests on the standard EVM, and the ABI/bytecode exported to `contracts/build/` for the Java core |
| [core/](core) | Java 21 library and `drexsim` CLI: signs transactions with every DSA of besu-pqc-ppi, deploys and drives the Drex contracts, shows RD/RT balances |
| [infra/](infra) | Starts a besu-pqc-ppi QBFT network through besu-qbft, with every account DSA enabled, and runs load tests across validator counts |
| [docs/](docs) | Contracts reference and use-case flows |

## Requirements

- Java 21 and Maven 3.9
- Node.js 18+ (only to change or test the contracts; the Java build uses the committed `contracts/build/`)
- Docker, a besu-pqc-ppi image and a [besu-qbft](https://github.com/venturusbr/besu-qbft) checkout next to this repository, to run a network

## Quick start

Build and run the unit tests:

```sh
mvn clean package
```

Start a four-validator network whose validators sign with SECP256K1-MLDSA44:

```sh
infra/start-network.sh hyperledger/besu:26.1.0-pqc --node-dsa=SECP256K1-MLDSA44 --nodes=4
```

In another terminal, check the node, build a demo Drex network and look at the balances:

```sh
java -jar core/target/drexsim.jar network
java -jar core/target/drexsim.jar setup-demo
java -jar core/target/drexsim.jar balances --watch 2
```

`setup-demo` creates BCB, two banks with their reserves and Real Tokenizado (BRLA, BRLB) and three clients, each actor signing with a different DSA (`MLDSA44`, `MLDSA87`, `FALCON512`, both hybrids, and standard ECDSA for one bank as the classical baseline). It then mints Real Digital through STR, deposits client funds, freezes part of a balance, and runs payments in which every client signs: two inter-bank swaps and one transfer inside Banco A. Every transaction is printed with its type, signature and key sizes and gas limit.

Other commands: `accounts list`, `accounts new <name> --dsa <DSA> [--standard]`, `deploy`, and the evaluation commands `flows`, `security` and `bench` (below). Run `java -jar core/target/drexsim.jar <command> --help` for details. Accounts and contract addresses are kept in `simulator-data/` (`--data-dir`); the keys there are **test keys in plain text**.

### Balance viewer

`drexsim balances` shows, for every wallet, its Real Digital (RD) and Real Tokenizado (RT) balance, with any frozen part, read at a single block so the figures are consistent. The last two rows compare the sum of the wallets shown with the total supply on chain.

```text
Balances at block 192 (2026-11-16 16:19:52), amounts in R$
Wallet                | Signs with          | Address        | RD (BRL)     | RT BRLA                  | RT BRLB
----------------------+---------------------+----------------+--------------+--------------------------+---------
bcb                   | SECP256K1-MLDSA44   | 0x5ef8e8..b9fa |         0,00 |                     0,00 |     0,00
bank-a                | MLDSA44             | 0x8c86a7..048f |    50.000,00 |                     0,00 |     0,00
reserve-a             | SECP256K1-FALCON512 | 0xc256cb..4b2b |   998.865,44 |                     0,00 |     0,00
bank-b                | SECP256K1 (std)     | 0xc5f1c4..9321 |         0,00 |                     0,00 |     0,00
reserve-b             | SECP256K1 (std)     | 0x86f3b8..e825 | 1.001.134,56 |                     0,00 |     0,00
client-a1             | SECP256K1-MLDSA44   | 0x0719c2..36fa |         0,00 |                 9.165,44 |     0,00
client-a2             | MLDSA87             | 0xd9da58..7f5a |         0,00 | 2.200,00 (frozen 500,00) |     0,00
client-b1             | FALCON512           | 0xaa9d5e..de88 |         0,00 |                     0,00 | 8.134,56
----------------------+---------------------+----------------+--------------+--------------------------+---------
Sum of wallets shown  |                     |                | 2.050.000,00 |                11.365,44 | 8.134,56
Total supply on chain |                     |                | 2.050.000,00 |                11.365,44 | 8.134,56
```

Options: `--account <name>` (repeatable), `--address <0x..>` for wallets created elsewhere, `--hide-empty`, `--watch <seconds>`, and `--json` for the raw snapshot (amounts in cents, with `balance`, `frozen` and `available`). The same data comes from `BalanceService` for other front-ends.

## Evaluating the platform

Three commands run the evaluation of the proof of concept against a live network. Each writes a Markdown report and a CSV under `reports/`.

| Command | What it does |
|---|---|
| `drexsim flows` | Runs the 19 Drex use-case flows (docs/use-case-flows.md) once per signer DSA: the classical baseline (standard ECDSA transactions) and every DSA of the fork in flexible transactions. Each run uses fresh accounts and contracts, checks the on-chain state of every flow, including the calls that must be refused, and reports a flow × DSA matrix of pass/fail, gas and bytes. |
| `drexsim security` | Attacks the network. **Protocol**: tampered, relabelled, replayed or forged transactions for every DSA. **Quantum adversary**: knowing every ECDSA private key (as Shor's algorithm would), it drains a reserve, mints as BCB and moves client funds on a classical deployment, and must fail with every strategy on a hybrid one (ECDSA-only and standard-transaction downgrades, its own PQC key, a forged PQC half). **Contract**: reproduces flaws of the pilot contracts. |
| `drexsim bench` | Load test: clients of two banks paying each other (same-bank transfers and inter-bank swaps) at increasing rates. Reports included TPS, latency percentiles, transactions per block, block fill, the throughput ceiling set by the block gas limit, and node CPU and memory. |

```sh
java -jar core/target/drexsim.jar flows
java -jar core/target/drexsim.jar security
java -jar core/target/drexsim.jar bench --dsa SECP256K1:std,SECP256K1-MLDSA44 --rates 50,100,200 --nodes http://localhost:8545,http://localhost:8546,http://localhost:8547,http://localhost:8548
```

To compare networks of different sizes, `infra/scalability.sh` restarts the network with each validator count and runs `bench` on it:

```sh
infra/scalability.sh hyperledger/besu:26.1.0-pqc --validators=4,7,10 --dsa=SECP256K1:std,SECP256K1-MLDSA44
```

Results of a run of all three are in [docs/results/](docs/results).

### Tests

```sh
mvn test                                         # unit tests, no network needed
mvn test -Ddrexsim.rpc=http://localhost:8545     # also runs the demo, flows and security suites on a live network
cd contracts && npm install && npm test          # contract tests on the Hardhat network
```

The signer is checked byte for byte against transactions produced by [besu-flex-wallet-ppi](https://github.com/venturusbr/besu-flex-wallet-ppi), which besu-pqc-ppi accepts.

## How transactions are signed

besu-pqc-ppi has no `eth_sendTransaction` for post-quantum accounts, so the core signs every transaction itself and sends it with `eth_sendRawTransaction`. The generated web3j contract wrappers go through `PqcTransactionManager`, which:

- encodes a flexible EIP-1559 transaction (`0x42`) for any DSA, or a standard one (`0x02`) for classical accounts;
- keeps a nonce counter per account;
- sets the gas limit from `eth_estimateGas` plus the extra base cost of the DSA, which the estimate leaves out (65 102 gas for SECP256K1-MLDSA44, for instance), plus a 20% margin.

## Smart contracts

The contracts come from [wireshape/real-digital-smart-contracts](https://github.com/wireshape/real-digital-smart-contracts), a Wire Labs reconstruction of the Real Digital pilot contracts based on the ABIs released by Banco Central do Brasil. It is not the official BCB implementation.

| Contract | Purpose |
|---|---|
| [CBDCAccessControl.sol](https://github.com/wireshape/real-digital-smart-contracts/blob/main/CBDCAccessControl.sol) | Roles and wallet allow-list shared by the tokens |
| [RealDigital.sol](https://github.com/wireshape/real-digital-smart-contracts/blob/main/RealDigital.sol) | Wholesale CBDC, held by BCB and participants |
| [RealTokenizado.sol](https://github.com/wireshape/real-digital-smart-contracts/blob/main/RealTokenizado.sol) | Tokenized deposit of one participant, held by its clients |
| [STR.sol](https://github.com/wireshape/real-digital-smart-contracts/blob/main/STR.sol) | Simulated STR: participants mint and burn Real Digital |
| [SwapOneStep.sol](https://github.com/wireshape/real-digital-smart-contracts/blob/main/SwapOneStep.sol) | Inter-bank client transfer in one transaction |
| [SwapTwoSteps.sol](https://github.com/wireshape/real-digital-smart-contracts/blob/main/SwapTwoSteps.sol) | Inter-bank client transfer with propose and accept |
| [RealDigitalEnableAccount.sol](https://github.com/wireshape/real-digital-smart-contracts/blob/main/RealDigitalEnableAccount.sol) | Participants enable their own wallets |
| [RealDigitalDefaultAccount.sol](https://github.com/wireshape/real-digital-smart-contracts/blob/main/RealDigitalDefaultAccount.sol) | Default wallet of each participant, by CNPJ8 |
| [KeyDictionary.sol](https://github.com/wireshape/real-digital-smart-contracts/blob/main/KeyDictionary.sol) | Directory from client key to bank data and wallet |
| [AddressDiscovery.sol](https://github.com/wireshape/real-digital-smart-contracts/blob/main/AddressDiscovery.sol) | Directory of deployed contract addresses |

## Documentation

- [Contracts reference](docs/contracts-reference.md): what each contract does, its functions, roles, required setup and known caveats.
- [Use case flows](docs/use-case-flows.md): which contracts and functions to call, in which order and by which wallet, for each use case (network setup, onboarding, minting, transfers, swaps, freezing, pausing), with argument types.
- [Proof of concept results](docs/results/README.md): what running the flows, the security tests and the load tests on besu-pqc-ppi showed, with the reports of each run.

## License

Licensed under the [Apache License 2.0](LICENSE). The Drex contracts in [contracts/contracts/drex/](contracts/contracts/drex) keep their original [MIT License](contracts/contracts/drex/LICENSE) (Copyright (c) 2023 Wireshape). See [NOTICE](NOTICE) for attributions.

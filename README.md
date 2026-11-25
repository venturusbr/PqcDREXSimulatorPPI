# PqcDREXSimulatorPPI

A front-end and smart contracts that reproduce Brazil's Real Digital (Drex) pilot operations, running on a Besu PQC network modified to accept post-quantum (PQC) and hybrid signatures.

## Repository layout

| Path | What it is |
|---|---|
| [contracts/](contracts) | Hardhat project: the Drex contracts (pinned copy, OpenZeppelin 4.9.6), functional tests on the standard EVM, and the ABI/bytecode exported to `contracts/build/` for the Java core |
| [core/](core) | Java 21 library and `drexsim` CLI: signs transactions with every DSA of besu-pqc-ppi, deploys and drives the Drex contracts, shows RD/RT balances |
| [gui/](gui) | Web interface (`drexsim-gui`): live balances, Drex operations, transaction log with signature sizes and gas, and the evaluation runs with their reports |
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

`setup-demo` creates BCB, two banks with their reserves and Real Tokenizado (BRLA, BRLB) and six clients, the actors signing with different DSAs (`MLDSA44`, `MLDSA65`, `MLDSA87`, `FALCON512`, both hybrids, and standard ECDSA for one bank as the classical baseline). It then mints Real Digital through STR, deposits client funds, freezes part of a balance, and runs payments: two inter-bank swaps and two transfers inside Banco A. Three clients show edge cases of the balance viewer: daniel is a client of both banks, Banco B disables fabio after a deposit (his balance stays), and eva is never onboarded but receives Real Tokenizado, since `transfer()` checks only the sender. Every transaction is printed with its type, signature and key sizes and gas limit.

Other commands: `accounts list`, `accounts new <name> --dsa <DSA> [--standard]`, `deploy`, and the evaluation commands `flows`, `security` and `bench` (below). Run `java -jar core/target/drexsim.jar <command> --help` for details. Accounts and contract addresses are kept in `simulator-data/` (`--data-dir`); the keys there are **test keys in plain text**.

### Web interface

```sh
java -jar gui/target/drexsim-gui.jar
```

Then open http://localhost:8080. The page shows:

- **Overview**: live balances of every account, refreshed at every block and with frozen parts shown, in two tables: participants (BCB, banks and reserves) with their Real Digital, and clients with their Real Tokenizado in one column where each amount is tagged with its token (BRLA, BRLB, ...), so the table keeps its width however many banks issue tokens. An account that is both, such as a bank with an account at another bank, is in both tables, and a wallet holding a token outside its role is listed where that token is, so each table's sum matches the supply. A switch above the tables changes to a unified view, one table of every wallet with both columns, and the browser remembers the choice. An icon beside each name shows what the wallet is on chain, read from the contracts' access control: BCB (admin of Real Digital), participant (enabled in Real Digital), reserve (reserve wallet of a Real Tokenizado), client (enabled in a Real Tokenizado), or none of these. An amount held in a token the wallet is not enabled in is marked *not enabled*: it arrived by a plain transfer, which the contracts only check on the sender side, or stayed after the wallet was disabled, and the wallet cannot send it. A form for each Drex operation (STR issuance and redemption, transfers, deposits, withdrawals, inter-bank payments, freezing, forced transfers, pause, onboarding), each signed by the account its flow names and sent to the node you choose, and the transactions sent, with their operation (number and name; only the number, with the name on hover, when the table would not fit), the contract called, algorithm, type (`0x42` or `0x02`), size, signature and key bytes, gas and receipt status. Clicking a transaction opens what `eth_getTransactionByHash` and `eth_getTransactionReceipt` return, organized: overview with account and contract names, the decoded call, signature algorithm and sizes, gas, the decoded events, and the raw JSON. On a chain without a Drex network it offers to set up the demo.
- **Accounts**: the simulated accounts and a form to create one with any algorithm.
- **Evaluation**: runs the flows, security and load tests, with their log live and their report when done.
- **Network**: every node with its status, block, peers, validator address and P2P address; the chain's validators, accepted algorithms and contract addresses.

The interface is in Brazilian Portuguese by default; the globe menu in the header switches it to English, and the browser remembers the choice. Logs, reports and error messages from the simulator stay in English.

Each language is a JSON file in [gui/src/main/resources/public/i18n/](gui/src/main/resources/public/i18n), named by its locale (`en-US.json`, `pt-BR.json`), mapping keys such as `balances.title` to text and giving its own name in `"_language"`. To add a language, copy `en-US.json` to a file named after the new locale, translate the values and rebuild: the server lists the files at `/api/languages` and the menu offers every one. `{name}` placeholders must be kept; a key with `.one` / `.other` forms is a plural. A key missing from a language falls back to English, and `LanguagesTest` fails when a file lacks a key or a placeholder of `en-US.json`.

It uses the same `--rpc` and `--data-dir` options as the CLI, so accounts and deployments are shared. The other nodes are found by probing the 15 local ports after the `--rpc` port (besu-qbft maps node N to 8545 + N − 1), keeping those on the same chain; `--nodes http://host:8545,http://host:8546,...` lists them instead. All nodes share one nonce counter per account, so an account can switch nodes between operations. The GUI signs with every simulated account, so it listens on 127.0.0.1 only; `--host` changes that, which is not advisable.

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
ana                   | SECP256K1-MLDSA44   | 0x0719c2..36fa |         0,00 |                 9.165,44 |     0,00
bruno                 | MLDSA87             | 0xd9da58..7f5a |         0,00 | 2.200,00 (frozen 500,00) |     0,00
carla                 | FALCON512           | 0xaa9d5e..de88 |         0,00 |                     0,00 | 8.134,56
daniel                | MLDSA65             | 0xf7886e..0677 |         0,00 |                   800,00 | 1.000,00
eva                   | SECP256K1 (std)     | 0xd64e4c..2334 |         0,00 |                   200,00 |     0,00
fabio                 | FALCON512           | 0xd54884..5acb |         0,00 |                     0,00 |   800,00
----------------------+---------------------+----------------+--------------+--------------------------+---------
Sum of wallets shown  |                     |                | 2.050.000,00 |                12.365,44 | 9.934,56
Total supply on chain |                     |                | 2.050.000,00 |                12.365,44 | 9.934,56
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

GitHub Actions runs two workflows: [Java](.github/workflows/java.yml) builds the modules and runs the unit tests (the network suites are skipped there), and [Contracts](.github/workflows/contracts.yml) runs the contract tests and checks the compiled artifacts.

After changing a contract, run `npm run export` in `contracts/` and commit `contracts/build`. The Java build uses those files so that it needs no Node.js, and the Contracts workflow fails when they no longer match a fresh export of the sources. Solidity hashes each source's exact text into the bytecode, so `.gitattributes` keeps the `.sol` files LF on every platform; a CRLF copy compiles to different files.

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

### Known contract flaws

These are flaws in the contracts themselves, so no signature scheme fixes them. The contracts are kept as published, and the simulator works around the flaws where a flow needs it. `drexsim security` reproduces the first three on a live network ([results](docs/results/README.md)):

1. **`transfer` does not check the recipient.** `checkAccess` checks only the sender of a transfer and checks the recipient only when tokens are minted. Funds can be sent to a wallet that is not enabled in the token, and that wallet cannot move them afterwards. The Overview of the web interface shows such a wallet with the "not enabled" icon. `transferFrom` checks both sides. See [checkAccess](docs/contracts-reference.md#modifier-checkaccessfrom-to).
2. **Any participant can enable any address in RealDigital.** RealDigitalEnableAccount only requires the caller to be enabled, so a bank can bring any wallet into the wholesale token. See [RealDigitalEnableAccount](docs/contracts-reference.md#realdigitalenableaccount).
3. **Any participant can overwrite another bank's client key.** `KeyDictionary.addAccount` does not check whether the key exists, so a participant can redirect another bank's client to a wallet of its choice. See [KeyDictionary](docs/contracts-reference.md#keydictionary).

The [contracts reference](docs/contracts-reference.md) describes the other caveats found while building the tests:
- `moveAndBurn` reverts until the token contract's own address is enabled.
- A frozen amount is not capped by the balance.
- `burnFrom` relies on the allowance that `mint` grants.
- SwapTwoSteps does not freeze funds when a swap is proposed, and its proposal ids start at 1.
- KeyDictionary's "already registered" check never blocks.

## Documentation

- [Contracts reference](docs/contracts-reference.md): what each contract does, its functions, roles, required setup and known caveats.
- [Use case flows](docs/use-case-flows.md): which contracts and functions to call, in which order and by which wallet, for each use case (network setup, onboarding, minting, transfers, swaps, freezing, pausing), with argument types.
- [Proof of concept results](docs/results/README.md): what running the flows, the security tests and the load tests on besu-pqc-ppi showed, with the reports of each run.

## License

Licensed under the [Apache License 2.0](LICENSE). The Drex contracts in [contracts/contracts/drex/](contracts/contracts/drex) keep their original [MIT License](contracts/contracts/drex/LICENSE) (Copyright (c) 2023 Wireshape). See [NOTICE](NOTICE) for attributions.

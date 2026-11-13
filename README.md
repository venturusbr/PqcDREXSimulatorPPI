# PqcDREXSimulatorPPI

A front-end and smart contracts that reproduce Brazil's Real Digital (Drex) pilot operations, running on a Besu PQC network modified to accept post-quantum (PQC) and hybrid signatures.

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

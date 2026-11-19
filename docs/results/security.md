# Security tests

Run at 2026-11-16T20:48:14.159606100Z against `http://localhost:8545`. 77 of 77 checks behaved as expected.

## Protocol attacks

Forged or altered transactions sent raw with `eth_sendRawTransaction`. Expected: **BLOCKED**, meaning the node rejects the transaction or, for recoverable ECDSA, accepts it only as a transaction of some other sender.

| Attack | SECP256K1 (std) | SECP256K1 | SECP256R1 | MLDSA44 | MLDSA65 | MLDSA87 | FALCON512 | SECP256K1-MLDSA44 | SECP256K1-FALCON512 |
|---|---|---|---|---|---|---|---|---|---|
| Replay of a mined transaction | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED |
| Signed for another chain id | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED |
| Tampered signature | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED |
| Recipient changed after signing | n/a | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED |
| dsaType relabelled | n/a | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED |
| Carried public key replaced | n/a | n/a | n/a | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED |
| Carried public key of the wrong length | n/a | n/a | n/a | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED | ✅ BLOCKED |
| Falcon signature beyond 752 bytes | n/a | n/a | n/a | n/a | n/a | n/a | ✅ BLOCKED | n/a | ✅ BLOCKED |
| Hybrid: valid ECDSA half, random PQC half | n/a | n/a | n/a | n/a | n/a | n/a | n/a | ✅ BLOCKED | ✅ BLOCKED |

<details><summary>Node responses</summary>

| Signer | Attack | Evidence |
|---|---|---|
| SECP256K1 (std) | Replay of a mined transaction | rejected: Nonce too low |
| SECP256K1 (std) | Signed for another chain id | rejected: Wrong chainId |
| SECP256K1 (std) | Tampered signature | accepted, but as another sender 0x533bc6e614dcbabe9ae01d00ad5b07d88f916f16 (not the victim) |
| SECP256K1 | Replay of a mined transaction | rejected: Nonce too low |
| SECP256K1 | Signed for another chain id | rejected: Wrong chainId |
| SECP256K1 | Tampered signature | accepted, but as another sender 0x20bc09577cb6f89f34a65c233deaf7bd063fb033 (not the victim) |
| SECP256K1 | Recipient changed after signing | accepted, but as another sender 0xe0f81cf6788d0f4a39d6d318dca5b0dc2e0f8e84 (not the victim) |
| SECP256K1 | dsaType relabelled to SECP256R1 | rejected: Invalid signature |
| SECP256R1 | Replay of a mined transaction | rejected: Nonce too low |
| SECP256R1 | Signed for another chain id | rejected: Wrong chainId |
| SECP256R1 | Tampered signature | accepted, but as another sender 0x3cda049873b08f6041f7fa60178c30d7c1c5ad37 (not the victim) |
| SECP256R1 | Recipient changed after signing | accepted, but as another sender 0x87259ca3471afcdea6bc5f03e51dbeaead26a0bb (not the victim) |
| SECP256R1 | dsaType relabelled to SECP256K1 | accepted, but as another sender 0xa87e8e49823d7adedaa81109c2f980de0207eb63 (not the victim) |
| MLDSA44 | Replay of a mined transaction | rejected: Nonce too low |
| MLDSA44 | Signed for another chain id | rejected: Wrong chainId |
| MLDSA44 | Tampered signature | rejected: Invalid signature |
| MLDSA44 | Recipient changed after signing | rejected: Invalid signature |
| MLDSA44 | dsaType relabelled to MLDSA65 | rejected: Invalid params |
| MLDSA44 | Carried public key replaced | rejected: Invalid signature |
| MLDSA44 | Carried public key of the wrong length | rejected: Invalid params |
| MLDSA65 | Replay of a mined transaction | rejected: Nonce too low |
| MLDSA65 | Signed for another chain id | rejected: Wrong chainId |
| MLDSA65 | Tampered signature | rejected: Invalid signature |
| MLDSA65 | Recipient changed after signing | rejected: Invalid signature |
| MLDSA65 | dsaType relabelled to MLDSA87 | rejected: Invalid params |
| MLDSA65 | Carried public key replaced | rejected: Invalid signature |
| MLDSA65 | Carried public key of the wrong length | rejected: Invalid params |
| MLDSA87 | Replay of a mined transaction | rejected: Nonce too low |
| MLDSA87 | Signed for another chain id | rejected: Wrong chainId |
| MLDSA87 | Tampered signature | rejected: Invalid signature |
| MLDSA87 | Recipient changed after signing | rejected: Invalid signature |
| MLDSA87 | dsaType relabelled to MLDSA44 | rejected: Invalid params |
| MLDSA87 | Carried public key replaced | rejected: Invalid signature |
| MLDSA87 | Carried public key of the wrong length | rejected: Invalid params |
| FALCON512 | Replay of a mined transaction | rejected: Nonce too low |
| FALCON512 | Signed for another chain id | rejected: Wrong chainId |
| FALCON512 | Tampered signature | rejected: Invalid signature |
| FALCON512 | Recipient changed after signing | rejected: Invalid signature |
| FALCON512 | dsaType relabelled to MLDSA44 | rejected: Invalid params |
| FALCON512 | Carried public key replaced | rejected: Invalid signature |
| FALCON512 | Carried public key of the wrong length | rejected: Invalid params |
| FALCON512 | Falcon signature beyond 752 bytes | rejected: Invalid signature |
| SECP256K1-MLDSA44 | Replay of a mined transaction | rejected: Nonce too low |
| SECP256K1-MLDSA44 | Signed for another chain id | rejected: Wrong chainId |
| SECP256K1-MLDSA44 | Tampered signature | rejected: Invalid signature |
| SECP256K1-MLDSA44 | Recipient changed after signing | rejected: Invalid signature |
| SECP256K1-MLDSA44 | dsaType relabelled to MLDSA44 | rejected: Invalid signature |
| SECP256K1-MLDSA44 | Carried public key replaced | rejected: Invalid signature |
| SECP256K1-MLDSA44 | Carried public key of the wrong length | rejected: Invalid params |
| SECP256K1-MLDSA44 | Hybrid: valid ECDSA half, random PQC half | rejected: Invalid signature |
| SECP256K1-FALCON512 | Replay of a mined transaction | rejected: Nonce too low |
| SECP256K1-FALCON512 | Signed for another chain id | rejected: Wrong chainId |
| SECP256K1-FALCON512 | Tampered signature | rejected: Invalid signature |
| SECP256K1-FALCON512 | Recipient changed after signing | rejected: Invalid signature |
| SECP256K1-FALCON512 | dsaType relabelled to FALCON512 | rejected: Invalid signature |
| SECP256K1-FALCON512 | Carried public key replaced | rejected: Invalid signature |
| SECP256K1-FALCON512 | Carried public key of the wrong length | rejected: Invalid params |
| SECP256K1-FALCON512 | Falcon signature beyond 752 bytes | rejected: Invalid params |
| SECP256K1-FALCON512 | Hybrid: valid ECDSA half, random PQC half | rejected: Invalid signature |

</details>

## Simulated quantum adversary

The adversary knows every ECDSA private key, as Shor's algorithm would give it, plus its own keys. Against the classical deployment its attacks are expected to **succeed**: that is the risk being addressed. Against the hybrid deployment every strategy must be **BLOCKED**, judged on balances and roles, not on error messages.

| Victim signs with | Attack | Expected | Observed | Evidence |
|---|---|---|---|---|
| SECP256K1 (std) | Drain bank A's reserve (Real Digital): Stolen ECDSA key, signing as the victim | SUCCEEDED | ✅ SUCCEEDED | signer 0xa709ce..2c85 (victim 0xa709ce..2c85); attacker RD 1.000.000,00, RT 0,00, enabled false |
| SECP256K1 (std) | Mint Real Digital as BCB: Stolen ECDSA key, signing as the victim | SUCCEEDED | ✅ SUCCEEDED | signer 0x95de56..0c2a (victim 0x95de56..0c2a); attacker RD 1.001.000.000,00, RT 0,00, enabled true |
| SECP256K1 (std) | Move a client's Real Tokenizado: Stolen ECDSA key, signing as the victim | SUCCEEDED | ✅ SUCCEEDED | signer 0x9249da..95ae (victim 0x9249da..95ae); attacker RD 1.001.000.000,00, RT 10.000,00, enabled true |
| SECP256K1-MLDSA44 | Drain bank A's reserve (Real Digital): Downgrade to a flexible ECDSA-only transaction | BLOCKED | ✅ BLOCKED | signer 0x913958..04dd (victim 0xbf1c08..c669); attacker RD 0,00, RT 0,00, enabled false; mined in block 2431 with status 0x0 (reverted) |
| SECP256K1-MLDSA44 | Drain bank A's reserve (Real Digital): Downgrade to a standard EIP-1559 transaction | BLOCKED | ✅ BLOCKED | signer 0x913958..04dd (victim 0xbf1c08..c669); attacker RD 0,00, RT 0,00, enabled false; mined in block 2432 with status 0x0 (reverted) |
| SECP256K1-MLDSA44 | Drain bank A's reserve (Real Digital): Hybrid with the victim's ECDSA key and the attacker's own PQC key | BLOCKED | ✅ BLOCKED | signer 0x064b2b..8bfa (victim 0xbf1c08..c669); attacker RD 0,00, RT 0,00, enabled false; mined in block 2433 with status 0x0 (reverted) |
| SECP256K1-MLDSA44 | Drain bank A's reserve (Real Digital): victim's PQC key carried, PQC half forged | BLOCKED | ✅ BLOCKED | rejected: Invalid signature |
| SECP256K1-MLDSA44 | Mint Real Digital as BCB: Downgrade to a flexible ECDSA-only transaction | BLOCKED | ✅ BLOCKED | signer 0x2d436c..e84a (victim 0x63d8b6..4153); attacker RD 0,00, RT 0,00, enabled false; enableAccount: mined in block 2434 with status 0x0 (reverted); mint: mined in block 2435 with status 0x0 (reverted) |
| SECP256K1-MLDSA44 | Mint Real Digital as BCB: Downgrade to a standard EIP-1559 transaction | BLOCKED | ✅ BLOCKED | signer 0x2d436c..e84a (victim 0x63d8b6..4153); attacker RD 0,00, RT 0,00, enabled false; enableAccount: mined in block 2436 with status 0x0 (reverted); mint: mined in block 2437 with status 0x0 (reverted) |
| SECP256K1-MLDSA44 | Mint Real Digital as BCB: Hybrid with the victim's ECDSA key and the attacker's own PQC key | BLOCKED | ✅ BLOCKED | signer 0xf15a34..04a5 (victim 0x63d8b6..4153); attacker RD 0,00, RT 0,00, enabled false; enableAccount: mined in block 2438 with status 0x0 (reverted); mint: mined in block 2439 with status 0x0 (reverted) |
| SECP256K1-MLDSA44 | Mint Real Digital as BCB: victim's PQC key carried, PQC half forged | BLOCKED | ✅ BLOCKED | rejected: Invalid signature |
| SECP256K1-MLDSA44 | Move a client's Real Tokenizado: Downgrade to a flexible ECDSA-only transaction | BLOCKED | ✅ BLOCKED | signer 0x8ff2e4..3914 (victim 0x77268b..148e); attacker RD 0,00, RT 0,00, enabled false; mined in block 2440 with status 0x0 (reverted) |
| SECP256K1-MLDSA44 | Move a client's Real Tokenizado: Downgrade to a standard EIP-1559 transaction | BLOCKED | ✅ BLOCKED | signer 0x8ff2e4..3914 (victim 0x77268b..148e); attacker RD 0,00, RT 0,00, enabled false; mined in block 2441 with status 0x0 (reverted) |
| SECP256K1-MLDSA44 | Move a client's Real Tokenizado: Hybrid with the victim's ECDSA key and the attacker's own PQC key | BLOCKED | ✅ BLOCKED | signer 0x8a8243..557f (victim 0x77268b..148e); attacker RD 0,00, RT 0,00, enabled false; mined in block 2442 with status 0x0 (reverted) |
| SECP256K1-MLDSA44 | Move a client's Real Tokenizado: victim's PQC key carried, PQC half forged | BLOCKED | ✅ BLOCKED | rejected: Invalid signature |

## Contract findings

Flaws of the pilot contracts (docs/contracts-reference.md) that no signature scheme fixes. Expected: the flaw is reproduced (**SUCCEEDED**), documenting it for the contract owners.

| Victim signs with | Attack | Expected | Observed | Evidence |
|---|---|---|---|---|
| SECP256K1-MLDSA44 | Another participant overwrites a client's key (KeyDictionary.addAccount) | SUCCEEDED | ✅ SUCCEEDED | getWallet(key) = 0x4866ace5f813d0c89e3d3111b1023681ab68336a |
| SECP256K1-MLDSA44 | Transfer to a wallet that is not enabled (funds get stuck) | SUCCEEDED | ✅ SUCCEEDED | recipient balance 1,00, can move it back: false |
| SECP256K1-MLDSA44 | A participant enables an arbitrary wallet in RealDigital | SUCCEEDED | ✅ SUCCEEDED | verifyAccount(outsider) = true |

## Not covered here

- Forged QBFT consensus messages and block seals: they need a malicious validator on the p2p layer; besu-pqc-ppi covers them in its consensus integration tests under every node DSA.
- A node that does not enable a DSA: the network enables every account DSA, as the simulator needs.

# Load tests

Updated 2026-11-16T22:58:49.048651300Z. Workload: Drex clients paying each other, same-bank Real Tokenizado transfers and inter-bank SwapOneStep swaps. Latency is from submission to the block that includes the transaction. A level is *sustained* when the network included at least 90% of the offered rate with little backlog. The gas-bound ceiling is the block gas limit divided by the gas per transaction and the block interval.

## Highest sustained rate

| Signer | Validators | Block gas limit | Highest sustained target (TPS) | Included TPS | p95 latency | Gas per tx | Gas-bound ceiling (TPS) | Avg tx size |
|---|---|---|---|---|---|---|---|---|
| SECP256K1-MLDSA44 | 4 | 200M | none of the levels run | | | | | |
| MLDSA87 | 4 | 200M | 100 | 91.29 | 6837 ms | 191,174 | 382.0 | 7355 B |

## All levels

| Signer | Val. | Gas limit | Target | Offered | Included | p50 | p95 | p99 | Tx/block (max) | Block fill | Block size | Backlog | Rejected | Node CPU | Node mem | Sustained |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SECP256K1-MLDSA44 | 4 | 200M | 100 | 99.19 | 62.88 | 22529 ms | 29259 ms | 30394 ms | 600.00 (1535) | 39.1% | 2318.4 KB | 0 | 0 | 273.4% | 865 MB | **no** |
| SECP256K1-MLDSA44 | 4 | 200M | 200 | 168.82 | 107.87 | 20318 ms | 32987 ms | 34226 ms | 1000.00 (1533) | 65.6% | 3856.7 KB | 0 | 0 | 244.8% | 1068 MB | **no** |
| SECP256K1-MLDSA44 | 4 | 200M | 300 | 189.60 | 112.56 | 32224 ms | 51676 ms | 55815 ms | 1125.00 (1536) | 73.7% | 4337.8 KB | 0 | 0 | 240.7% | 1710 MB | **no** |
| SECP256K1-MLDSA44 | 4 | 200M | 400 | 232.46 | 34.85 | 44047 ms | 217885 ms | 278176 ms | 1355.71 (1538) | 88.7% | 5225.7 KB | 65 | 0 | 234.9% | 1988 MB | **no** |
| MLDSA87 | 4 | 200M | 100 | 99.71 | 91.29 | 4562 ms | 6837 ms | 7424 ms | 250.00 (407) | 23.9% | 1808.4 KB | 0 | 0 | 241.9% | 1311 MB | yes |
| MLDSA87 | 4 | 200M | 200 | 199.10 | 127.91 | 15248 ms | 21424 ms | 23260 ms | 750.00 (1053) | 72.0% | 5401.9 KB | 0 | 0 | 233.3% | 1661 MB | **no** |
| MLDSA87 | 4 | 200M | 300 | 210.12 | 133.40 | 23861 ms | 34993 ms | 38986 ms | 900.00 (1053) | 86.4% | 6480.2 KB | 0 | 0 | 231.3% | 1650 MB | **no** |
| MLDSA87 | 4 | 200M | 400 | 200.21 | 36.34 | 59372 ms | 234699 ms | 247357 ms | 919.75 (1049) | 88.3% | 6622.6 KB | 482 | 0 | 241.7% | 1844 MB | **no** |

Node CPU is the average over the validator containers (100% = one core); all nodes and the load generator share one machine, so absolute numbers depend on it. Compare the signers with each other.

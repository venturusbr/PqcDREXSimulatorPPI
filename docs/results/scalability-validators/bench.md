# Load tests

Updated 2026-11-16T22:35:18.757658700Z. Workload: Drex clients paying each other, same-bank Real Tokenizado transfers and inter-bank SwapOneStep swaps. Latency is from submission to the block that includes the transaction. A level is *sustained* when the network included at least 90% of the offered rate with little backlog. The gas-bound ceiling is the block gas limit divided by the gas per transaction and the block interval.

## Highest sustained rate

| Signer | Validators | Block gas limit | Highest sustained target (TPS) | Included TPS | p95 latency | Gas per tx | Gas-bound ceiling (TPS) | Avg tx size |
|---|---|---|---|---|---|---|---|---|
| SECP256K1 (std) | 4 | 50M | 200 | 186.07 | 5283 ms | 66,063 | 305.1 | 193 B |
| SECP256K1-MLDSA44 | 4 | 50M | 100 | 93.61 | 2789 ms | 130,233 | 191.7 | 3933 B |
| SECP256K1 (std) | 7 | 50M | none of the levels run | | | | | |
| SECP256K1-MLDSA44 | 7 | 50M | none of the levels run | | | | | |

## All levels

| Signer | Val. | Gas limit | Target | Offered | Included | p50 | p95 | p99 | Tx/block (max) | Block fill | Block size | Backlog | Rejected | Node CPU | Node mem | Sustained |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SECP256K1 (std) | 4 | 50M | 50 | 49.97 | 48.30 | 2784 ms | 6419 ms | 7169 ms | 107.14 (218) | 14.0% | 32.3 KB | 0 | 0 | 237.2% | 979 MB | yes |
| SECP256K1 (std) | 4 | 50M | 100 | 100.01 | 95.19 | 1751 ms | 2695 ms | 2966 ms | 187.50 (203) | 24.4% | 47.5 KB | 0 | 0 | 135.1% | 961 MB | yes |
| SECP256K1 (std) | 4 | 50M | 200 | 199.83 | 186.07 | 3527 ms | 5283 ms | 5789 ms | 461.54 (633) | 61.0% | 99.9 KB | 0 | 0 | 222.1% | 1029 MB | yes |
| SECP256K1-MLDSA44 | 4 | 50M | 50 | 49.94 | 46.87 | 1459 ms | 2362 ms | 2481 ms | 93.75 (102) | 24.5% | 372.3 KB | 0 | 0 | 53.0% | 1040 MB | yes |
| SECP256K1-MLDSA44 | 4 | 50M | 100 | 99.88 | 93.61 | 1820 ms | 2789 ms | 3072 ms | 187.50 (204) | 48.8% | 732.7 KB | 0 | 0 | 114.8% | 1106 MB | yes |
| SECP256K1-MLDSA44 | 4 | 50M | 200 | 196.41 | 132.24 | 12222 ms | 17636 ms | 18768 ms | 352.82 (390) | 92.6% | 1368.4 KB | 1 | 1 | 160.3% | 1144 MB | **no** |
| SECP256K1 (std) | 7 | 50M | 50 | 49.92 | 23.54 | 31031 ms | 39118 ms | 39965 ms | 374.75 (774) | 49.1% | 90.7 KB | 0 | 1 | 172.7% | 1013 MB | **no** |
| SECP256K1 (std) | 7 | 50M | 100 | 99.22 | 82.31 | 11154 ms | 16668 ms | 17346 ms | 500.00 (784) | 65.1% | 114.4 KB | 0 | 0 | 151.1% | 1267 MB | **no** |
| SECP256K1 (std) | 7 | 50M | 200 | 162.15 | 110.00 | 25691 ms | 34454 ms | 35293 ms | 666.67 (769) | 88.1% | 146.6 KB | 0 | 0 | 147.8% | 1884 MB | **no** |
| SECP256K1-MLDSA44 | 7 | 50M | 50 | 49.53 | 43.92 | 5070 ms | 7634 ms | 8337 ms | 136.36 (241) | 35.6% | 543.6 KB | 0 | 0 | 154.0% | 1654 MB | **no** |
| SECP256K1-MLDSA44 | 7 | 50M | 100 | 99.37 | 57.45 | 20385 ms | 28107 ms | 29341 ms | 300.00 (392) | 78.1% | 1172.6 KB | 0 | 0 | 155.0% | 1909 MB | **no** |
| SECP256K1-MLDSA44 | 7 | 50M | 200 | 113.63 | 54.27 | 43372 ms | 60674 ms | 61213 ms | 366.15 (390) | 95.9% | 1427.1 KB | 142 | 1 | 155.9% | 2818 MB | **no** |

## Most frequent rejection per level

- SECP256K1-MLDSA44, 4 validators, 200 TPS: Initial sync is still in progress (1)
- SECP256K1 (std), 7 validators, 50 TPS: Nonce too low (1)
- SECP256K1-MLDSA44, 7 validators, 200 TPS: Initial sync is still in progress (1)

Node CPU is the average over the validator containers (100% = one core); all nodes and the load generator share one machine, so absolute numbers depend on it. Compare the signers with each other.

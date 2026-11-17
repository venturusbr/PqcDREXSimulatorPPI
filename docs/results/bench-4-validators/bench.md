# Load tests

Updated 2026-11-16T22:14:25.127269900Z. Workload: Drex clients paying each other, same-bank Real Tokenizado transfers and inter-bank SwapOneStep swaps. Latency is from submission to the block that includes the transaction. A level is *sustained* when the network included at least 90% of the offered rate with little backlog. The gas-bound ceiling is the block gas limit divided by the gas per transaction and the block interval.

## Highest sustained rate

| Signer | Validators | Block gas limit | Highest sustained target (TPS) | Included TPS | p95 latency | Gas per tx | Gas-bound ceiling (TPS) | Avg tx size |
|---|---|---|---|---|---|---|---|---|
| SECP256K1 (std) | 4 | 50M | 300 | 272.99 | 6739 ms | 65,884 | 301.5 | 194 B |
| FALCON512 | 4 | 50M | 200 | 189.50 | 3103 ms | 91,570 | 275.9 | 1688 B |
| SECP256K1-FALCON512 | 4 | 50M | 200 | 185.47 | 4127 ms | 95,428 | 259.1 | 1753 B |
| MLDSA44 | 4 | 50M | 100 | 94.35 | 2807 ms | 126,684 | 198.6 | 3868 B |
| SECP256K1-MLDSA44 | 4 | 50M | 100 | 93.44 | 3450 ms | 130,233 | 191.3 | 3933 B |
| MLDSA87 | 4 | 50M | 100 | 92.60 | 5107 ms | 191,177 | 121.1 | 7355 B |

## All levels

| Signer | Val. | Gas limit | Target | Offered | Included | p50 | p95 | p99 | Tx/block (max) | Block fill | Block size | Backlog | Rejected | Node CPU | Node mem | Sustained |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SECP256K1 (std) | 4 | 50M | 50 | 50.01 | 49.44 | 2512 ms | 5938 ms | 7130 ms | 107.14 (206) | 14.0% | 32.3 KB | 0 | 0 | 229.7% | 764 MB | yes |
| SECP256K1 (std) | 4 | 50M | 100 | 100.01 | 94.05 | 1711 ms | 2685 ms | 2937 ms | 187.50 (203) | 24.4% | 47.5 KB | 0 | 0 | 123.4% | 860 MB | yes |
| SECP256K1 (std) | 4 | 50M | 200 | 199.64 | 186.88 | 3855 ms | 5813 ms | 6376 ms | 500.00 (704) | 66.1% | 107.3 KB | 0 | 0 | 225.3% | 1105 MB | yes |
| SECP256K1 (std) | 4 | 50M | 300 | 298.83 | 272.99 | 4448 ms | 6739 ms | 7399 ms | 687.15 (771) | 90.5% | 143.4 KB | 66 | 1 | 118.0% | 1200 MB | yes |
| SECP256K1 (std) | 4 | 50M | 400 | 397.70 | 273.29 | 11075 ms | 16465 ms | 17407 ms | 698.82 (779) | 92.1% | 146.2 KB | 120 | 0 | 128.9% | 1226 MB | **no** |
| FALCON512 | 4 | 50M | 50 | 50.01 | 46.96 | 1405 ms | 2297 ms | 2411 ms | 93.75 (103) | 17.1% | 166.7 KB | 0 | 0 | 74.9% | 1222 MB | yes |
| FALCON512 | 4 | 50M | 100 | 100.01 | 93.90 | 1542 ms | 2449 ms | 2552 ms | 187.50 (202) | 34.0% | 321.4 KB | 0 | 0 | 66.0% | 1255 MB | yes |
| FALCON512 | 4 | 50M | 200 | 199.83 | 189.50 | 2118 ms | 3103 ms | 3292 ms | 375.00 (404) | 68.7% | 631.2 KB | 0 | 0 | 173.9% | 1280 MB | yes |
| FALCON512 | 4 | 50M | 300 | 299.13 | 238.47 | 5562 ms | 10120 ms | 11165 ms | 529.41 (561) | 96.8% | 886.5 KB | 0 | 0 | 187.1% | 1436 MB | **no** |
| FALCON512 | 4 | 50M | 400 | 397.63 | 211.92 | 19539 ms | 27005 ms | 28209 ms | 521.74 (565) | 95.4% | 874.2 KB | 0 | 0 | 163.8% | 1434 MB | **no** |
| SECP256K1-FALCON512 | 4 | 50M | 50 | 50.02 | 46.81 | 1408 ms | 2312 ms | 2426 ms | 93.75 (102) | 17.8% | 172.6 KB | 0 | 0 | 59.0% | 1037 MB | yes |
| SECP256K1-FALCON512 | 4 | 50M | 100 | 99.52 | 93.97 | 1792 ms | 2797 ms | 3299 ms | 187.50 (203) | 35.4% | 333.3 KB | 0 | 0 | 101.2% | 1068 MB | yes |
| SECP256K1-FALCON512 | 4 | 50M | 200 | 199.59 | 185.47 | 2808 ms | 4127 ms | 4598 ms | 375.00 (524) | 71.6% | 655.0 KB | 0 | 0 | 194.3% | 1262 MB | yes |
| SECP256K1-FALCON512 | 4 | 50M | 300 | 299.16 | 206.81 | 10586 ms | 16229 ms | 17673 ms | 500.00 (539) | 95.3% | 869.7 KB | 0 | 0 | 164.7% | 1511 MB | **no** |
| SECP256K1-FALCON512 | 4 | 50M | 400 | 322.33 | 163.79 | 26257 ms | 33238 ms | 37537 ms | 522.95 (541) | 99.7% | 909.4 KB | 34 | 0 | 192.1% | 1850 MB | **no** |
| MLDSA44 | 4 | 50M | 50 | 50.02 | 47.08 | 1464 ms | 2362 ms | 2486 ms | 93.75 (102) | 23.8% | 366.3 KB | 0 | 0 | 92.3% | 1057 MB | yes |
| MLDSA44 | 4 | 50M | 100 | 99.77 | 94.35 | 1842 ms | 2807 ms | 3030 ms | 187.50 (203) | 47.5% | 720.8 KB | 0 | 0 | 104.5% | 1261 MB | yes |
| MLDSA44 | 4 | 50M | 200 | 198.91 | 144.19 | 10564 ms | 15409 ms | 16509 ms | 375.00 (398) | 95.7% | 1429.9 KB | 0 | 0 | 209.8% | 1610 MB | **no** |
| MLDSA44 | 4 | 50M | 300 | 285.91 | 136.17 | 25273 ms | 34132 ms | 36107 ms | 388.09 (404) | 98.8% | 1479.6 KB | 105 | 0 | 222.1% | 1761 MB | **no** |
| MLDSA44 | 4 | 50M | 400 | 297.97 | 110.55 | 31937 ms | 64149 ms | 68767 ms | 391.67 (401) | 99.8% | 1493.4 KB | 306 | 0 | 219.3% | 1722 MB | **no** |
| SECP256K1-MLDSA44 | 4 | 50M | 50 | 49.95 | 46.89 | 1512 ms | 2425 ms | 2623 ms | 93.75 (102) | 24.5% | 372.3 KB | 0 | 0 | 62.4% | 1462 MB | yes |
| SECP256K1-MLDSA44 | 4 | 50M | 100 | 99.82 | 93.44 | 2312 ms | 3450 ms | 3734 ms | 187.50 (214) | 48.8% | 732.7 KB | 0 | 0 | 187.6% | 1507 MB | yes |
| SECP256K1-MLDSA44 | 4 | 50M | 200 | 199.55 | 136.06 | 11485 ms | 17000 ms | 18493 ms | 352.94 (391) | 92.6% | 1368.9 KB | 0 | 0 | 211.5% | 1652 MB | **no** |
| SECP256K1-MLDSA44 | 4 | 50M | 300 | 275.90 | 127.77 | 24129 ms | 35338 ms | 38779 ms | 380.55 (391) | 99.6% | 1475.2 KB | 3 | 0 | 177.4% | 2051 MB | **no** |
| SECP256K1-MLDSA44 | 4 | 50M | 400 | 265.23 | 97.71 | 35315 ms | 74554 ms | 78826 ms | 381.00 (388) | 99.8% | 1477.2 KB | 0 | 0 | 201.6% | 1809 MB | **no** |
| MLDSA87 | 4 | 50M | 50 | 49.99 | 47.30 | 1683 ms | 2586 ms | 2878 ms | 93.75 (101) | 35.9% | 685.6 KB | 0 | 0 | 136.3% | 1444 MB | yes |
| MLDSA87 | 4 | 50M | 100 | 99.83 | 92.60 | 3228 ms | 5107 ms | 5593 ms | 200.00 (263) | 76.5% | 1449.1 KB | 0 | 0 | 238.3% | 1545 MB | yes |
| MLDSA87 | 4 | 50M | 200 | 199.22 | 105.21 | 19980 ms | 27953 ms | 30108 ms | 250.00 (265) | 96.1% | 1808.5 KB | 0 | 0 | 198.2% | 1823 MB | **no** |
| MLDSA87 | 4 | 50M | 300 | 198.36 | 80.43 | 33666 ms | 60806 ms | 64566 ms | 259.48 (265) | 99.7% | 1876.8 KB | 271 | 0 | 210.6% | 1774 MB | **no** |
| MLDSA87 | 4 | 50M | 400 | 194.79 | 86.71 | 38543 ms | 48763 ms | 51245 ms | 259.70 (265) | 99.7% | 1878.5 KB | 3360 | 1 | 221.0% | 2161 MB | **no** |

## Most frequent rejection per level

- SECP256K1 (std), 4 validators, 300 TPS: Initial sync is still in progress (1)
- MLDSA87, 4 validators, 400 TPS: Initial sync is still in progress (1)

Node CPU is the average over the validator containers (100% = one core); all nodes and the load generator share one machine, so absolute numbers depend on it. Compare the signers with each other.

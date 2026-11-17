#!/bin/bash
# Runs the drexsim load test on networks of increasing validator count.
#
# For each validator count: starts a fresh besu-pqc-ppi QBFT network with infra/start-network.sh,
# waits for it, runs `drexsim bench` spreading the clients over every node, and stops the network.
# All results go to one bench.csv / bench.md, where the validator count is a column.
#
# Usage: infra/scalability.sh <besu-image> [options]
#   --validators=4,7,10        validator counts (default 4,7,10)
#   --node-dsa=<DSA>           validator key DSA (default SECP256K1-MLDSA44)
#   --dsa=<profiles>           signer profiles for drexsim bench (default SECP256K1:std,SECP256K1-MLDSA44)
#   --rates=<tps,...>          target rates (default 50,100,200,300)
#   --duration=<seconds>       seconds per rate (default 30)
#   --clients=<n>              clients per bank (default 50)
#   --gas-limit=<gas>          block gas limit (default besu-qbft's 50,000,000)
#   --out=<dir>                report directory (default reports/scalability)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
JAR="$REPO_DIR/core/target/drexsim.jar"
COMPOSE="$SCRIPT_DIR/.work/besu-qbft/docker-compose.generated.yaml"

IMAGE=""
VALIDATORS="4,7,10"
NODE_DSA="SECP256K1-MLDSA44"
DSA="SECP256K1:std,SECP256K1-MLDSA44"
RATES="50,100,200,300"
DURATION=30
CLIENTS=50
OUT="$REPO_DIR/reports/scalability"
GAS_LIMIT_ARG=""
while [ $# -gt 0 ]; do
    case "$1" in
        --validators=*) VALIDATORS="${1#*=}" ;;
        --node-dsa=*) NODE_DSA="${1#*=}" ;;
        --dsa=*) DSA="${1#*=}" ;;
        --rates=*) RATES="${1#*=}" ;;
        --duration=*) DURATION="${1#*=}" ;;
        --clients=*) CLIENTS="${1#*=}" ;;
        --out=*) OUT="${1#*=}" ;;
        --gas-limit=*) GAS_LIMIT_ARG="$1" ;;
        -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
        -*) echo "Unknown option: $1"; exit 1 ;;
        *) IMAGE="$1" ;;
    esac
    shift
done
[ -n "$IMAGE" ] || { sed -n '2,17p' "$0"; exit 1; }
[ -f "$JAR" ] || { echo "Build first: mvn package (missing $JAR)"; exit 1; }

stop_network() {
    [ -f "$COMPOSE" ] && docker compose -f "$COMPOSE" down >/dev/null 2>&1 || true
}

rpc() { # rpc <port> <method>
    curl -s -m 2 -X POST -H 'Content-Type: application/json' \
        --data "{\"jsonrpc\":\"2.0\",\"method\":\"$2\",\"params\":[],\"id\":1}" "http://localhost:$1" || true
}

mkdir -p "$OUT"
IFS=',' read -ra COUNTS <<< "$VALIDATORS"
for n in "${COUNTS[@]}"; do
    echo "=== $n validators"
    stop_network
    "$SCRIPT_DIR/start-network.sh" "$IMAGE" --node-dsa="$NODE_DSA" --nodes="$n" $GAS_LIMIT_ARG > "$OUT/network-$n.log" 2>&1 &
    NETWORK_PID=$!

    # Wait until the last node serves blocks, i.e. every node is up and the network makes progress.
    last_port=$((8544 + n))
    for _ in $(seq 1 180); do
        if rpc "$last_port" eth_blockNumber | grep -q '"result":"0x[1-9a-f]'; then break; fi
        sleep 2
    done
    rpc "$last_port" eth_blockNumber | grep -q '"result":"0x[1-9a-f]' || { echo "Network of $n validators did not start; see $OUT/network-$n.log"; stop_network; exit 1; }

    endpoints=$(seq -s, -f "http://localhost:%g" 8545 "$last_port")
    java -jar "$JAR" bench --dsa "$DSA" --rates "$RATES" --duration "$DURATION" --clients "$CLIENTS" \
        --nodes "$endpoints" --out "$OUT" | tee "$OUT/bench-$n.log"

    stop_network
    wait "$NETWORK_PID" 2>/dev/null || true
done
echo "Report: $OUT/bench.md"

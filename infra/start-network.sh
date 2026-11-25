#!/bin/bash
# Starts a besu-pqc-ppi QBFT network for the simulator, using besu-qbft.
#
# besu-qbft is copied to infra/.work/besu-qbft and patched there, so the original checkout is never
# modified. The patch enables every account DSA the simulator can sign with (pure post-quantum
# ones included, which besu-qbft leaves out by default) and sets the number of validators and,
# optionally, the block gas limit and block period.
#
# Usage: infra/start-network.sh <besu-image> [--node-dsa=<DSA>] [--nodes=<N>] [--gas-limit=<gas>] [--block-period=<s>]
#   BESU_QBFT_DIR  path of the besu-qbft checkout (default: ../besu-qbft next to this repository)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
BESU_QBFT_DIR="${BESU_QBFT_DIR:-$(dirname "$REPO_DIR")/besu-qbft}"
WORK_DIR="$SCRIPT_DIR/.work/besu-qbft"

ACCOUNT_DSAS='["SECP256K1", "SECP256R1", "MLDSA44", "MLDSA65", "MLDSA87", "FALCON512", "SECP256K1-MLDSA44", "SECP256K1-FALCON512"]'

usage() {
    echo "Usage: $0 <besu-image> [--node-dsa=<DSA>] [--nodes=<N>]"
    echo "  --node-dsa  validator key DSA, passed to besu-qbft (default SECP256K1-MLDSA44)"
    echo "  --nodes     number of validators (default 4)"
    echo "  --gas-limit block gas limit (default: besu-qbft's 50000000)"
    echo "  --block-period seconds between blocks (default: besu-qbft's 2)"
    echo "Example: $0 hyperledger/besu:26.1.0-pqc --node-dsa=SECP256K1-MLDSA44 --nodes=4"
}

IMAGE=""
NODE_DSA="SECP256K1-MLDSA44"
NODES=4
GAS_LIMIT=""
BLOCK_PERIOD=""
while [ $# -gt 0 ]; do
    case "$1" in
        --node-dsa=*) NODE_DSA="${1#*=}" ;;
        --nodes=*) NODES="${1#*=}" ;;
        --gas-limit=*) GAS_LIMIT="${1#*=}" ;;
        --block-period=*) BLOCK_PERIOD="${1#*=}" ;;
        -h|--help) usage; exit 0 ;;
        -*) echo "Unknown option: $1"; usage; exit 1 ;;
        *) IMAGE="$1" ;;
    esac
    shift
done
[ -n "$IMAGE" ] || { usage; exit 1; }
[ -f "$BESU_QBFT_DIR/init-qbft.sh" ] || { echo "besu-qbft not found at $BESU_QBFT_DIR (set BESU_QBFT_DIR)"; exit 1; }
[[ "$NODES" =~ ^[1-9][0-9]*$ ]] || { echo "--nodes must be a positive integer"; exit 1; }

echo "Copying $BESU_QBFT_DIR to $WORK_DIR"
if [ -f "$WORK_DIR/docker-compose.generated.yaml" ]; then
    (cd "$WORK_DIR" && docker compose -f docker-compose.generated.yaml down 2>/dev/null) || true
fi
# Empty the directory rather than deleting it: on Windows a directory that is some process's
# working directory cannot be removed.
mkdir -p "$WORK_DIR"
find "$WORK_DIR" -mindepth 1 -delete
# Only the tracked files: no node data or generated files of a previous network.
(cd "$BESU_QBFT_DIR" && git ls-files -z | xargs -0 -I{} cp --parents {} "$WORK_DIR/")
chmod +x "$WORK_DIR/init-qbft.sh"

echo "Enabling account DSAs $ACCOUNT_DSAS"
sed -i "s#^accounts-supported-dsas=.*#accounts-supported-dsas=$ACCOUNT_DSAS#" "$WORK_DIR/genesis-generator/config.toml"
grep -q '^accounts-supported-dsas=\["SECP256K1", "SECP256R1", "MLDSA44"' "$WORK_DIR/genesis-generator/config.toml" \
    || { echo "Could not set accounts-supported-dsas in config.toml"; exit 1; }

echo "Setting $NODES validators"
sed -i -E "s#(\"count\": *)[0-9]+#\1$NODES#" "$WORK_DIR/genesis-generator/qbftConfig.json"

if [ -n "$GAS_LIMIT" ]; then
    [[ "$GAS_LIMIT" =~ ^[1-9][0-9]*$ ]] || { echo "--gas-limit must be a positive integer"; exit 1; }
    echo "Setting block gas limit $GAS_LIMIT"
    sed -i -E "s#(\"gasLimit\": *)\"[^\"]*\"#\1\"$(printf '0x%X' "$GAS_LIMIT")\"#" "$WORK_DIR/genesis-generator/qbftConfig.json"
fi
if [ -n "$BLOCK_PERIOD" ]; then
    [[ "$BLOCK_PERIOD" =~ ^[1-9][0-9]*$ ]] || { echo "--block-period must be a positive integer"; exit 1; }
    echo "Setting block period $BLOCK_PERIOD s"
    sed -i -E "s#(\"blockperiodseconds\": *)[0-9]+#\1$BLOCK_PERIOD#" "$WORK_DIR/genesis-generator/qbftConfig.json"
fi

cd "$WORK_DIR"
# Newer besu-qbft sets accounts-supported-dsas itself from --accounts-dsas (SECP256K1 when it is not
# given), which would undo the patch above, so pass the same list there too.
ACCOUNTS_ARG=()
if grep -q -- '--accounts-dsas' init-qbft.sh; then
    ACCOUNTS_ARG=(--accounts-dsas="$(echo "$ACCOUNT_DSAS" | tr -d '[]" ')")
fi
exec ./init-qbft.sh "$IMAGE" --node-dsa="$NODE_DSA" "${ACCOUNTS_ARG[@]}"

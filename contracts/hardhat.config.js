require("@nomicfoundation/hardhat-toolbox");

/**
 * Compiles the Real Digital (Drex) pilot contracts for the besu-pqc-ppi QBFT network.
 * The network genesis activates Prague at block 0, so any EVM target up to Cancun is safe.
 */
module.exports = {
  solidity: {
    version: "0.8.24",
    settings: {
      evmVersion: "cancun",
      optimizer: { enabled: true, runs: 200 },
    },
  },
  networks: {
    // Free gas, like the besu-qbft genesis (zeroBaseFee), so `npx hardhat node` accepts the
    // simulator's zero-fee transactions from unfunded accounts.
    hardhat: { initialBaseFeePerGas: 0 },
  },
  paths: {
    sources: "./contracts",
    tests: "./test",
  },
};

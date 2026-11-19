// Writes <Name>.abi and <Name>.bin for each deployable Drex contract into ./build.
// The Java core generates its web3j wrappers from these files, so it builds without Node.
const fs = require("fs");
const path = require("path");

const CONTRACTS = [
  "RealDigital",
  "RealTokenizado",
  "STR",
  "SwapOneStep",
  "SwapTwoSteps",
  "RealDigitalEnableAccount",
  "RealDigitalDefaultAccount",
  "KeyDictionary",
  "AddressDiscovery",
];

const outDir = path.join(__dirname, "..", "build");
fs.mkdirSync(outDir, { recursive: true });

for (const name of CONTRACTS) {
  const artifactPath = path.join(__dirname, "..", "artifacts", "contracts", "drex", `${name}.sol`, `${name}.json`);
  const artifact = JSON.parse(fs.readFileSync(artifactPath, "utf8"));
  fs.writeFileSync(path.join(outDir, `${name}.abi`), JSON.stringify(artifact.abi));
  fs.writeFileSync(path.join(outDir, `${name}.bin`), artifact.bytecode.replace(/^0x/, ""));
  const runtimeBytes = (artifact.deployedBytecode.length - 2) / 2;
  console.log(`${name}: ${runtimeBytes} bytes of runtime code`);
}

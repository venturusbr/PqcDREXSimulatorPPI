package br.org.venturus.drexsim.scenario;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.org.venturus.drexsim.security.SecurityReport;
import br.org.venturus.drexsim.security.SecurityResult;
import br.org.venturus.drexsim.security.SecuritySuite;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The flow and security suites on a live besu-pqc-ppi network. Skipped unless
 * {@code -Ddrexsim.rpc=http://localhost:8545} is given; each takes a few minutes.
 */
class NetworkSuitesTest {

  private String rpc;

  @BeforeEach
  void requireNetwork() {
    rpc = System.getProperty("drexsim.rpc", "");
    Assumptions.assumeTrue(rpc.startsWith("http"), "set -Ddrexsim.rpc to run against a network");
  }

  @Test
  void everyFlowPassesForTheBaselineAndAHybrid() throws Exception {
    FlowMatrix matrix = FlowMatrix.run(rpc,
        List.of(SignerProfile.BASELINE, SignerProfile.parse("SECP256K1-MLDSA44")), 2, System.out::println);
    System.out.print(matrix.renderText());
    assertTrue(matrix.allPassed());
  }

  @Test
  void everyAttackBehavesAsExpected() throws Exception {
    List<SecurityResult> results = new SecuritySuite(rpc, System.out::println).run();
    System.out.print(SecurityReport.summary(results));
    assertTrue(SecurityReport.allAsExpected(results));
  }
}

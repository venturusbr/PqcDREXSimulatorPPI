package br.org.venturus.drexsim.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.org.venturus.drexsim.crypto.Dsa;
import br.org.venturus.drexsim.tx.TxFormat;
import org.junit.jupiter.api.Test;

class SignerProfileTest {

  @Test
  void parsesFlexibleAndStandardProfiles() {
    assertEquals(new SignerProfile(Dsa.MLDSA44, TxFormat.FLEXIBLE), SignerProfile.parse("MLDSA44"));
    assertEquals(new SignerProfile(Dsa.SECP256K1_MLDSA44, TxFormat.FLEXIBLE), SignerProfile.parse("secp256k1-mldsa44"));
    assertEquals(SignerProfile.BASELINE, SignerProfile.parse("SECP256K1:std"));
    assertEquals(SignerProfile.BASELINE, SignerProfile.parse("SECP256K1 (std)"));
    assertEquals("SECP256K1 (std)", SignerProfile.BASELINE.label());
  }

  @Test
  void refusesStandardPostQuantumProfiles() {
    assertThrows(IllegalArgumentException.class, () -> SignerProfile.parse("MLDSA44:std"));
  }

  @Test
  void allCoversTheBaselineAndEveryDsa() {
    assertEquals(1 + Dsa.values().length, SignerProfile.all().size());
    assertTrue(SignerProfile.all().get(0).isBaseline());
  }
}

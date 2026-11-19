package br.org.venturus.drexsim.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DsaTest {

  private static byte[] randomHash() {
    byte[] hash = new byte[32];
    new SecureRandom().nextBytes(hash);
    return hash;
  }

  @ParameterizedTest
  @EnumSource(Dsa.class)
  void signsAndVerifies(Dsa dsa) {
    RawKeyPair kp = dsa.scheme().generateKeyPair();
    byte[] hash = randomHash();
    byte[] sig = dsa.scheme().sign(hash, kp.privateKey());

    assertTrue(dsa.scheme().verify(hash, sig, kp.publicKey()));
    assertEquals(dsa.publicKeyLength(), kp.publicKey().length);
    assertArrayEquals(kp.publicKey(), dsa.scheme().publicKeyOf(kp.privateKey()));
  }

  @ParameterizedTest
  @EnumSource(Dsa.class)
  void rejectsATamperedSignatureMessageOrKey(Dsa dsa) {
    RawKeyPair kp = dsa.scheme().generateKeyPair();
    RawKeyPair other = dsa.scheme().generateKeyPair();
    byte[] hash = randomHash();
    byte[] sig = dsa.scheme().sign(hash, kp.privateKey());

    byte[] tamperedSig = sig.clone();
    tamperedSig[sig.length - 10] ^= 0x01;
    byte[] tamperedHash = hash.clone();
    tamperedHash[0] ^= 0x01;

    assertFalse(dsa.scheme().verify(hash, tamperedSig, kp.publicKey()), "tampered signature");
    assertFalse(dsa.scheme().verify(tamperedHash, sig, kp.publicKey()), "other message");
    assertFalse(dsa.scheme().verify(hash, sig, other.publicKey()), "other key");
  }

  @ParameterizedTest
  @EnumSource(value = Dsa.class, names = {"MLDSA44", "MLDSA65", "MLDSA87", "SECP256K1_MLDSA44"})
  void fixedLengthSignaturesMatchTheFork(Dsa dsa) {
    int expected = switch (dsa) {
      case MLDSA44 -> 2420;
      case MLDSA65 -> 3309;
      case MLDSA87 -> 4627;
      default -> 2485;
    };
    RawKeyPair kp = dsa.scheme().generateKeyPair();
    assertEquals(expected, dsa.scheme().sign(randomHash(), kp.privateKey()).length);
  }

  @ParameterizedTest
  @EnumSource(value = Dsa.class, names = {"SECP256K1_MLDSA44", "SECP256K1_FALCON512"})
  void hybridNeedsBothHalves(Dsa dsa) {
    // A quantum adversary that broke ECDSA can forge the first half, but not the second.
    HybridScheme hybrid = (HybridScheme) dsa.scheme();
    RawKeyPair victim = hybrid.generateKeyPair();
    RawKeyPair attackerPq = hybrid.postQuantum().generateKeyPair();
    byte[] hash = randomHash();

    byte[] ecdsaSk = HybridScheme.classicalPart(victim.privateKey(), EcdsaScheme.PRIVATE_KEY_LENGTH);
    byte[] forgedEcdsa = hybrid.classical().sign(hash, ecdsaSk);
    byte[] forgedPq = hybrid.postQuantum().sign(HybridScheme.concat(hash, forgedEcdsa), attackerPq.privateKey());

    assertFalse(hybrid.verify(hash, HybridScheme.concat(forgedEcdsa, forgedPq), victim.publicKey()));
  }

  @ParameterizedTest
  @EnumSource(Dsa.class)
  void carriesOnlyWhatCannotBeRecovered(Dsa dsa) {
    RawKeyPair kp = dsa.scheme().generateKeyPair();
    byte[] carried = dsa.carriedPublicKey(kp.publicKey());
    assertEquals(dsa.carriedPublicKeyLength(), carried == null ? 0 : carried.length);
    assertEquals(dsa, Dsa.fromId(dsa.id()));
    assertEquals(dsa, Dsa.fromName(dsa.wireName()));
  }

  @org.junit.jupiter.api.Test
  void ecdsaIsDeterministicAndLowS() {
    RawKeyPair kp = EcdsaScheme.SECP256K1.generateKeyPair();
    byte[] hash = randomHash();
    byte[] a = EcdsaScheme.SECP256K1.sign(hash, kp.privateKey());
    assertArrayEquals(a, EcdsaScheme.SECP256K1.sign(hash, kp.privateKey()));
    assertArrayEquals(kp.publicKey(), EcdsaScheme.SECP256K1.recover(hash, a));
  }
}

package br.org.venturus.drexsim.tx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.org.venturus.drexsim.crypto.Dsa;
import br.org.venturus.drexsim.keystore.Account;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Properties;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.web3j.crypto.SignedRawTransaction;
import org.web3j.crypto.TransactionDecoder;
import org.web3j.utils.Numeric;

class TransactionSignerTest {

  private static final long CHAIN_ID = 381660666L;
  private static final String TO = "0xf17f52151ebef6c7334fad080c5704d77216b732";

  private static UnsignedTransaction call(BigInteger nonce, String to, byte[] data) {
    return new UnsignedTransaction(
        CHAIN_ID, nonce, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(1_000_000), to, BigInteger.ZERO, data);
  }

  private static final byte[] CALLDATA = Hex.decode("a9059cbb000000000000000000000000f17f52151ebef6c7334fad080c5704d77216b7320000000000000000000000000000000000000000000000000000000000002742");

  @ParameterizedTest
  @EnumSource(Dsa.class)
  void flexibleTransactionVerifiesAndRecoversTheSender(Dsa dsa) {
    Account account = Account.generate("a", dsa, TxFormat.FLEXIBLE);
    SignedTransaction signed = TransactionSigner.sign(call(BigInteger.valueOf(3), TO, CALLDATA), account);

    FlexibleTransactionVerifier.Result result = FlexibleTransactionVerifier.verify(signed.raw());
    assertTrue(result.valid(), result.reason());
    assertEquals(account.address(), result.sender());
    assertEquals(dsa.carriedPublicKeyLength(), signed.publicKeyLength());

    FlexibleTransactionVerifier.Decoded decoded = FlexibleTransactionVerifier.decode(signed.raw());
    assertArrayEquals(CALLDATA, decoded.fields().data(), "calldata travels as bytes, not as hex text");
    assertEquals(BigInteger.valueOf(3), decoded.fields().nonce());
  }

  @ParameterizedTest
  @EnumSource(Dsa.class)
  void contractCreationHasAnEmptyRecipient(Dsa dsa) {
    Account account = Account.generate("a", dsa, TxFormat.FLEXIBLE);
    SignedTransaction signed = TransactionSigner.sign(call(BigInteger.ZERO, null, CALLDATA), account);
    assertNull(FlexibleTransactionVerifier.decode(signed.raw()).fields().to());
    assertTrue(FlexibleTransactionVerifier.verify(signed.raw()).valid());
  }

  @ParameterizedTest
  @EnumSource(Dsa.class)
  void anyChangeAfterSigningInvalidatesTheTransactionOrChangesTheSender(Dsa dsa) {
    Account account = Account.generate("a", dsa, TxFormat.FLEXIBLE);
    UnsignedTransaction tx = call(BigInteger.ONE, TO, CALLDATA);
    FlexibleTransactionVerifier.Decoded d =
        FlexibleTransactionVerifier.decode(TransactionSigner.sign(tx, account).raw());

    // Change the amount but keep the signature: a recoverable DSA yields some other sender,
    // every other DSA fails verification. Either way the victim's account is not debited.
    byte[] otherData = CALLDATA.clone();
    otherData[otherData.length - 1] ^= 0x01;
    UnsignedTransaction altered = call(BigInteger.ONE, TO, otherData);
    byte[] forged = TransactionSigner.encodeFlexible(altered, dsa, d.carriedPublicKey(), d.signature());
    FlexibleTransactionVerifier.Result result = FlexibleTransactionVerifier.verify(forged);
    assertFalse(result.valid() && account.address().equals(result.sender()));
  }

  @Test
  void algorithmSubstitutionIsRejected() {
    // Re-label an ML-DSA-44 signature as ML-DSA-65: the signing hash covers dsaType.
    Account account = Account.generate("a", Dsa.MLDSA44, TxFormat.FLEXIBLE);
    FlexibleTransactionVerifier.Decoded d =
        FlexibleTransactionVerifier.decode(TransactionSigner.sign(call(BigInteger.ONE, TO, CALLDATA), account).raw());
    byte[] relabeled = TransactionSigner.encodeFlexible(d.fields(), Dsa.MLDSA65, d.carriedPublicKey(), d.signature());
    assertFalse(FlexibleTransactionVerifier.verify(relabeled).valid());
  }

  @Test
  void carriedKeyOfTheWrongLengthIsRejected() {
    Account account = Account.generate("a", Dsa.SECP256K1_MLDSA44, TxFormat.FLEXIBLE);
    FlexibleTransactionVerifier.Decoded d =
        FlexibleTransactionVerifier.decode(TransactionSigner.sign(call(BigInteger.ONE, TO, CALLDATA), account).raw());
    // The full composite key where only the ML-DSA half is expected.
    byte[] wrong = TransactionSigner.encodeFlexible(d.fields(), d.dsa(), account.publicKey(), d.signature());
    assertThrows(IllegalArgumentException.class, () -> FlexibleTransactionVerifier.decode(wrong));
  }

  @Test
  void standardTransactionIsReadableByWeb3j() throws Exception {
    Account account = Account.generate("a", Dsa.SECP256K1, TxFormat.STANDARD);
    SignedTransaction signed = TransactionSigner.sign(call(BigInteger.TEN, TO, CALLDATA), account);
    SignedRawTransaction decoded = (SignedRawTransaction) TransactionDecoder.decode(signed.rawHex());
    assertEquals(account.address(), decoded.getFrom().toLowerCase());
    assertEquals(BigInteger.TEN, decoded.getNonce());
  }

  @Test
  void standardFormatIsRefusedForPostQuantumDsas() {
    assertThrows(IllegalArgumentException.class, () -> Account.generate("a", Dsa.MLDSA44, TxFormat.STANDARD));
  }

  @Test
  void matchesTheFlexWalletByteForByte() throws Exception {
    // Golden vectors from besu-flex-wallet-ppi, whose transactions besu-pqc-ppi accepts.
    // ECDSA (RFC 6979) and ML-DSA (deterministic) signing make the raw bytes reproducible.
    Properties v = new Properties();
    try (InputStream in = getClass().getResourceAsStream("/flex-wallet-golden-vectors.properties")) {
      v.load(in);
    }
    byte[] ecdsaSk = Hex.decode(v.getProperty("ecdsaPrivateKey"));
    byte[] mldsaSk = Hex.decode(v.getProperty("mldsa44PrivateKey"));
    UnsignedTransaction tx = call(BigInteger.valueOf(7), TO, new byte[0]);

    byte[] hybridSk = new byte[ecdsaSk.length + mldsaSk.length];
    System.arraycopy(ecdsaSk, 0, hybridSk, 0, ecdsaSk.length);
    System.arraycopy(mldsaSk, 0, hybridSk, ecdsaSk.length, mldsaSk.length);
    Account hybrid = Account.fromPrivateKey("h", Dsa.SECP256K1_MLDSA44, TxFormat.FLEXIBLE, hybridSk);
    assertEquals(v.getProperty("mldsa44PublicKey"), Hex.toHexString(Dsa.SECP256K1_MLDSA44.carriedPublicKey(hybrid.publicKey())));
    assertEquals(v.getProperty("hybridMldsa44Raw"), Numeric.cleanHexPrefix(TransactionSigner.sign(tx, hybrid).rawHex()));

    Account standard = Account.fromPrivateKey("s", Dsa.SECP256K1, TxFormat.STANDARD, ecdsaSk);
    assertEquals(v.getProperty("standardSecp256k1Raw"), Numeric.cleanHexPrefix(TransactionSigner.sign(tx, standard).rawHex()));
  }
}

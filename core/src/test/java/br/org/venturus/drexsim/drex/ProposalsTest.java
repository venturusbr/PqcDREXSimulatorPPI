package br.org.venturus.drexsim.drex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.org.venturus.drexsim.drex.DrexClient.Requested;
import br.org.venturus.drexsim.drex.DrexClient.SwapProposal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which two-step payments and key requests are still open, worked out from what the contracts keep. */
class ProposalsTest {

  private static final String BANK_A = "0xaaaa000000000000000000000000000000000001";
  private static final String BANK_B = "0xbbbb000000000000000000000000000000000002";
  private static final String BANK_C = "0xcccc000000000000000000000000000000000003";
  private static final byte[] KEY = DrexClient.clientKey(new BigInteger("12345678901"));
  private static final byte[] OTHER_KEY = DrexClient.clientKey(new BigInteger("23456789012"));

  private static Requested request(long id, byte[] key, String bank) {
    return new Requested(BigInteger.valueOf(id), key, bank, null);
  }

  private static List<Long> open(List<Requested> events, Map<byte[], String> owners) {
    return DrexClient.stillOpen(events, owners::get).stream().map(r -> r.id().longValueExact()).toList();
  }

  @Test
  void aRequestIsOpenUntilTheKeyBelongsToTheBankThatAsked() {
    Map<byte[], String> owners = new HashMap<>(Map.of(KEY, BANK_A));
    List<Requested> events = List.of(request(1, KEY, BANK_B), request(2, KEY, BANK_C));
    assertEquals(List.of(1L, 2L), open(events, owners), "both banks wait for bank A");
    owners.put(KEY, BANK_B);
    assertEquals(List.of(2L), open(events, owners), "bank B's was authorized; bank C's now waits for bank B");
  }

  @Test
  void aKeyThatMovedBackClosesTheRequestsMadeBeforeIt() {
    // Bank B took the key from bank A with request 1, and bank A took it back with request 2. The contract would
    // still accept request 1, which would move the key to bank B again.
    Map<byte[], String> owners = Map.of(KEY, BANK_A, OTHER_KEY, BANK_A);
    List<Requested> events = List.of(request(1, KEY, BANK_B), request(5, OTHER_KEY, BANK_B), request(2, KEY, BANK_A),
        request(3, KEY, BANK_C));
    assertEquals(List.of(5L, 3L), open(events, owners));
  }

  @Test
  void aLaterRequestWithTheSameIdReplacesTheEarlierOne() {
    // The contract's id is keccak256(bank, block time): two requests by a bank in one block share it.
    List<Requested> events = List.of(request(7, KEY, BANK_B), request(7, OTHER_KEY, BANK_B));
    List<Requested> open = DrexClient.stillOpen(events, k -> BANK_A);
    assertEquals(1, open.size());
    assertTrue(java.util.Arrays.equals(OTHER_KEY, open.get(0).key()));
  }

  private static SwapProposal payment(long id, String token, long amount, SwapProposal.Status status, long time) {
    return new SwapProposal(BigInteger.valueOf(id), token, "0xtoken-b", BANK_A, BANK_B, BigInteger.valueOf(amount),
        status, time);
  }

  @Test
  void theAllowanceNeededCoversThePendingPaymentsThatCanStillBeAccepted() {
    long now = 1_000_000;
    long week = DrexClient.PROPOSAL_LIFETIME_SECONDS;
    List<SwapProposal> proposals = List.of(
        payment(1, "0xtoken-a", 100, SwapProposal.Status.PENDING, now - 10),
        payment(2, "0xtoken-a", 200, SwapProposal.Status.EXECUTED, now - 10),
        payment(3, "0xtoken-a", 400, SwapProposal.Status.CANCELLED, now - 10),
        payment(4, "0xtoken-a", 800, SwapProposal.Status.PENDING, now - week),
        payment(5, "0xTOKEN-A", 1600, SwapProposal.Status.PENDING, now - week + 1),
        payment(6, "0xtoken-c", 3200, SwapProposal.Status.PENDING, now - 10));
    assertEquals(BigInteger.valueOf(1700), DrexClient.pendingAmount(proposals, now, BANK_A, "0xtoken-a"));
    assertEquals(BigInteger.ZERO, DrexClient.pendingAmount(proposals, now, BANK_C, "0xtoken-a"));
  }

  @Test
  void aProposalExpiresSevenDaysAfterItWasMade() {
    SwapProposal p = payment(1, "0xtoken-a", 1, SwapProposal.Status.PENDING, 1_000);
    // SwapTwoSteps accepts while timestamp + 7 days > block.timestamp.
    assertFalse(p.expiredAt(1_000 + DrexClient.PROPOSAL_LIFETIME_SECONDS - 1));
    assertTrue(p.expiredAt(1_000 + DrexClient.PROPOSAL_LIFETIME_SECONDS));
  }
}

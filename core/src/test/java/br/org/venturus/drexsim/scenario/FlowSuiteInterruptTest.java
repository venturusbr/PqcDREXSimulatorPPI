package br.org.venturus.drexsim.scenario;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import org.junit.jupiter.api.Test;

/** A stopped run ends; a flow that failed, a read timeout included, only fails that flow. */
class FlowSuiteInterruptTest {

  @Test
  void anInterruptStopsTheRun() {
    assertTrue(FlowSuite.isInterrupt(new InterruptedException()));
    assertTrue(FlowSuite.isInterrupt(new RuntimeException("receipt", new InterruptedException())));
    assertTrue(FlowSuite.isInterrupt(new IOException("rpc", new InterruptedIOException())));
    assertTrue(FlowSuite.isInterrupt(new ClosedByInterruptException()));
  }

  @Test
  void aTimeoutOrAnotherFailureDoesNot() {
    assertFalse(Thread.currentThread().isInterrupted());
    assertFalse(FlowSuite.isInterrupt(new SocketTimeoutException("timeout")));
    assertFalse(FlowSuite.isInterrupt(new RuntimeException("rpc", new SocketTimeoutException("Read timed out"))));
    assertFalse(FlowSuite.isInterrupt(new IOException("connection refused")));
    assertFalse(FlowSuite.isInterrupt(new AssertionError("balance")));
  }

  @Test
  void aClearedExceptionStillCountsWhileTheThreadIsInterrupted() {
    Thread.currentThread().interrupt();
    try {
      assertTrue(FlowSuite.isInterrupt(new IOException("closed")));
    } finally {
      Thread.interrupted();
    }
  }
}

package br.org.venturus.drexsim.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JobRunnerTest {

  private final JobRunner jobs = new JobRunner(new EventHub(new ObjectMapper().registerModule(new JavaTimeModule())));

  @AfterEach
  void close() {
    jobs.close();
  }

  /** Waits for {@code job} to end, failing after 10 s. */
  private static JobRunner.Job awaitEnd(JobRunner.Job job) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (job.endedAt == null) {
      if (System.nanoTime() > deadline) {
        fail("job " + job.id + " is still " + job.status);
      }
      Thread.sleep(5);
    }
    return job;
  }

  /** Waits until the job has logged {@code line}. */
  private static void awaitLine(JobRunner.Job job, String line) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!job.log.contains(line)) {
      if (System.nanoTime() > deadline) {
        fail("job " + job.id + " never logged " + line + ": " + job.log);
      }
      Thread.sleep(5);
    }
  }

  private static List<String> tail(JobRunner.Job job, int n) {
    synchronized (job.log) {
      return List.copyOf(job.log.subList(Math.max(0, job.log.size() - n), job.log.size()));
    }
  }

  @Test
  void runsATaskAndKeepsItsLogAndReport() throws Exception {
    JobRunner.Job job = awaitEnd(jobs.start("t", "Task", log -> {
      log.accept("one");
      log.accept("two");
      return "# Report";
    }));
    assertEquals("done", job.status);
    assertEquals(List.of("one", "two"), job.log);
    assertEquals("# Report", job.report);
    assertEquals(null, job.error);
  }

  @Test
  void aFailedTaskReportsItsRootCause() throws Exception {
    JobRunner.Job job = awaitEnd(jobs.start("t", "Task", log -> {
      throw new RuntimeException("wrapper", new IOException("node down"));
    }));
    assertEquals("failed", job.status);
    assertEquals("node down", job.error);
    assertEquals(List.of("FAILED: node down"), job.log);
  }

  @Test
  void runsOneJobAtATime() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    JobRunner.Job first = jobs.start("t", "First", log -> {
      release.await();
      return null;
    });
    assertThrows(IllegalStateException.class, () -> jobs.start("t", "Second", log -> null));
    release.countDown();
    awaitEnd(first);
    assertEquals("done", awaitEnd(jobs.start("t", "Second", log -> null)).status);
  }

  @Test
  void aStopInterruptsAWaitingTask() throws Exception {
    JobRunner.Job job = jobs.start("t", "Waits", log -> {
      log.accept("waiting");
      Thread.sleep(60_000);
      return "never";
    });
    awaitLine(job, "waiting");
    JobRunner.Job stopping = jobs.stop(job.id);
    assertTrue(Set.of("stopping", "stopped").contains(stopping.status), stopping.status);
    awaitEnd(job);
    assertEquals("stopped", job.status);
    assertEquals(JobRunner.STOPPED, job.error);
    assertEquals(null, job.report);
    assertEquals(List.of("Stopping...", JobRunner.STOPPED), tail(job, 2));
    // The next job starts, and the stop's interrupt does not reach it.
    JobRunner.Job next = awaitEnd(jobs.start("t", "Next", log -> {
      Thread.sleep(50);
      return "ok";
    }));
    assertEquals("done", next.status);
  }

  @Test
  void aStopEndsABusyTaskAtItsNextLogLine() throws Exception {
    JobRunner.Job job = jobs.start("t", "Busy", log -> {
      for (long i = 0; ; i++) {
        log.accept("tick");
        long until = System.nanoTime() + 1_000_000;
        while (System.nanoTime() < until) {
          Thread.onSpinWait(); // never looks at the interrupt flag
        }
      }
    });
    awaitLine(job, "tick");
    jobs.stop(job.id);
    awaitEnd(job);
    assertEquals("stopped", job.status);
    assertEquals(JobRunner.STOPPED, job.log.get(job.log.size() - 1));
    assertEquals(1, job.log.stream().filter(JobRunner.STOPPED::equals).count());
  }

  @Test
  void linesFromAThreadThatOutlivesItsJobAreDropped() throws Exception {
    CountDownLatch ended = new CountDownLatch(1);
    Thread[] straggler = new Thread[1];
    JobRunner.Job job = awaitEnd(jobs.start("t", "Leaves a thread", log -> {
      straggler[0] = new Thread(() -> {
        try {
          ended.await();
        } catch (InterruptedException e) {
          return;
        }
        log.accept("late");
      });
      straggler[0].start();
      return null;
    }));
    ended.countDown();
    straggler[0].join(5_000);
    assertFalse(job.log.contains("late"), job.log.toString());
  }

  @Test
  void aStopAsTheJobEndsNeverLeavesItStopping() throws Exception {
    // Stops that land before the task starts, while it runs and as it returns: each must end the job as
    // done or stopped, and let the next one start.
    for (int i = 0; i < 300; i++) {
      JobRunner.Job job = jobs.start("t", "Quick " + i, log -> {
        log.accept("ran");
        return "ok";
      });
      try {
        jobs.stop(job.id);
      } catch (IllegalStateException alreadyEnded) {
        // the job ended before the stop: also fine
      }
      awaitEnd(job);
      assertTrue(Set.of("done", "stopped").contains(job.status), "job " + i + " ended " + job.status);
      if (job.status.equals("stopped")) {
        // "Stopping..." once, then lines the task logged before it noticed, then "Stopped by the user" once.
        List<String> lines = List.copyOf(job.log);
        assertEquals(JobRunner.STOPPED, lines.get(lines.size() - 1), "job " + i + " log " + lines);
        assertEquals(1, lines.stream().filter(JobRunner.STOPPED::equals).count(), "job " + i + " log " + lines);
        assertEquals(1, lines.stream().filter("Stopping..."::equals).count(), "job " + i + " log " + lines);
      } else {
        assertFalse(job.log.contains("Stopping..."), "job " + i + " was stopped but ended done: " + job.log);
      }
    }
  }

  @Test
  void stopRefusesUnknownAndEndedJobs() throws Exception {
    assertThrows(NoSuchElementException.class, () -> jobs.stop(999));
    JobRunner.Job job = awaitEnd(jobs.start("t", "Task", log -> null));
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> jobs.stop(job.id));
    assertNotNull(e.getMessage());
    assertEquals("done", job.status);
  }
}

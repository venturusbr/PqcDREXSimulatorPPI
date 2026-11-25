package br.org.venturus.drexsim.gui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Runs long tasks (demo setup, deployment, flow, security and load tests) one at a time in the
 * background, streaming their log lines to the browser.
 */
final class JobRunner implements AutoCloseable {

  /** A task's body: writes progress to {@code log}, returns a Markdown report (or null). */
  @FunctionalInterface
  interface Task {
    String run(Consumer<String> log) throws Exception;
  }

  /** A job and its outcome. */
  static final class Job {
    public final long id;
    public final String kind;
    public final String title;
    public final Instant startedAt = Instant.now();
    public volatile Instant endedAt;
    public volatile String status = "running";
    public volatile String error;
    public volatile String report;
    public final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private volatile Future<?> future;
    private volatile Thread worker;
    private volatile boolean stopRequested;

    Job(long id, String kind, String title) {
      this.id = id;
      this.kind = kind;
      this.title = title;
    }
  }

  /** Status of a job without its log, for event payloads and listings. */
  record Summary(long id, String kind, String title, String status, String error, Instant startedAt, Instant endedAt,
      int logLines) {
    static Summary of(Job j) {
      return new Summary(j.id, j.kind, j.title, j.status, j.error, j.startedAt, j.endedAt, j.log.size());
    }
  }

  private final EventHub events;
  private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "job");
    t.setDaemon(true);
    return t;
  });
  private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
  private final AtomicLong ids = new AtomicLong();
  private volatile Job current;

  JobRunner(EventHub events) {
    this.events = events;
  }

  /** Starts a job, or fails if one is still running (jobs share accounts and the network). */
  synchronized Job start(String kind, String title, Task task) {
    if (current != null && current.endedAt == null) {
      throw new IllegalStateException("'" + current.title + "' is still " + current.status);
    }
    Job job = new Job(ids.incrementAndGet(), kind, title);
    jobs.put(job.id, job);
    current = job;
    events.send("job", Summary.of(job));
    job.future = executor.submit(() -> {
      job.worker = Thread.currentThread();
      Consumer<String> log = line -> {
        job.log.add(line);
        events.send("job-log", Map.of("id", job.id, "line", line));
        // A task that is not waiting when it is stopped ends at its next log line.
        if (job.stopRequested && Thread.currentThread() == job.worker) {
          throw new CancellationException(STOPPED);
        }
      };
      try {
        if (job.stopRequested) {
          throw new CancellationException(STOPPED);
        }
        job.report = task.run(log);
        job.status = "done";
      } catch (Throwable e) {
        if (job.stopRequested) {
          job.error = STOPPED;
          job.status = "stopped";
          job.log.add(STOPPED);
          events.send("job-log", Map.of("id", job.id, "line", STOPPED));
          return;
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
          root = root.getCause();
        }
        job.error = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        job.status = "failed";
        log.accept("FAILED: " + job.error);
        System.err.println("Job " + job.id + " (" + job.title + ") failed:");
        e.printStackTrace();
      } finally {
        Thread.interrupted(); // the executor's thread runs the next job: clear a stop's interrupt
        job.endedAt = Instant.now();
        events.send("job", Summary.of(job));
      }
    });
    return job;
  }

  static final String STOPPED = "Stopped by the user";

  /**
   * Asks a running job to stop: interrupts its waits (between transactions, blocks and polls) and ends it
   * at its next log line otherwise. What it already sent stays on the chain. The job is "stopping" until
   * its task returns, and no other job starts before that.
   */
  synchronized Job stop(long id) {
    Job job = jobs.get(id);
    if (job == null) {
      throw new java.util.NoSuchElementException("No such job");
    }
    if (job.endedAt != null) {
      throw new IllegalStateException("'" + job.title + "' is not running");
    }
    if (!job.stopRequested) {
      job.stopRequested = true;
      job.status = "stopping";
      job.log.add("Stopping...");
      events.send("job-log", Map.of("id", job.id, "line", "Stopping..."));
      events.send("job", Summary.of(job));
      if (job.future != null) {
        job.future.cancel(true);
      }
    }
    return job;
  }

  Job get(long id) {
    return jobs.get(id);
  }

  List<Summary> list() {
    return jobs.values().stream().sorted((a, b) -> Long.compare(b.id, a.id)).map(Summary::of).toList();
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}

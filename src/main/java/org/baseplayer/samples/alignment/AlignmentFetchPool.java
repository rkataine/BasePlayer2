package org.baseplayer.samples.alignment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One shared fetch executor for all BAM/CRAM files so open readers/indexes
 * do not each own a thread, and work is naturally serialized to a small pool.
 */
public final class AlignmentFetchPool {

  private static final AlignmentFetchPool INSTANCE = new AlignmentFetchPool();

  private final ExecutorService executor;

  private AlignmentFetchPool() {
    ThreadFactory factory = new ThreadFactory() {
      private final AtomicInteger n = new AtomicInteger();

      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "bam-fetch-" + n.incrementAndGet());
        t.setDaemon(true);
        return t;
      }
    };
    // Single worker: one BAM open+fetch at a time keeps index memory bounded.
    this.executor = Executors.newSingleThreadExecutor(factory);
  }

  public static AlignmentFetchPool get() {
    return INSTANCE;
  }

  public Future<?> submit(Runnable task) {
    return executor.submit(task);
  }

  public ExecutorService executor() {
    return executor;
  }
}

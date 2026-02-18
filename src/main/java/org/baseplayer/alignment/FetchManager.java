package org.baseplayer.alignment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.baseplayer.draw.DrawStack;

/**
 * Centralized fetch coordinator for all viewport-driven data loading.
 * <p>
 * Every fetch that is triggered by viewport navigation — BAM/CRAM reads,
 * sampled coverage, reference bases, feature tracks (conservation, gnomAD) —
 * goes through this singleton so we can:
 * <ul>
 *   <li>Track how many reads / items are being loaded globally</li>
 *   <li>Abort runaway fetches that exceed memory / read-count limits</li>
 *   <li>Prevent new fetches when the JVM is low on memory</li>
 *   <li>Cancel ALL in-flight fetches on chromosome change or large jumps</li>
 *   <li>Cancel specific fetch types or stacks independently</li>
 *   <li>Provide a single point of truth for "is anything loading?"</li>
 * </ul>
 * <p>
 * One-time loads (gene annotations, BED files, COSMIC) stay outside this class.
 * <p>
 * Thread-safety: all public methods are safe to call from the FX thread or
 * from background fetch threads.
 */
public final class FetchManager {

  // ── Fetch types ────────────────────────────────────────────────────────────

  /** Categories of viewport-driven fetches. */
  public enum FetchType {
    /** BAM/CRAM read streaming — heavy, per-item counting, strict limits. */
    READS,
    /** Chromosome-level sampled coverage — sparse index queries. */
    SAMPLED_COVERAGE,
    /** Reference base loading (FASTA) — lightweight async. */
    REFERENCE,
    /** External API feature tracks (conservation, gnomAD, etc.). */
    FEATURE_TRACK
  }

  // ── Singleton ──────────────────────────────────────────────────────────────

  private static final FetchManager INSTANCE = new FetchManager();
  public static FetchManager get() { return INSTANCE; }
  private FetchManager() {}

  // ── Tunables ───────────────────────────────────────────────────────────────

  /** Max reads allowed in a single READS fetch before it is aborted. */
  private volatile int maxReadsPerFetch = 2_000_000;

  /** Max total reads across ALL active READS fetches before new fetches are blocked. */
  private volatile int maxGlobalReads = 5_000_000;

  /** Fraction of max heap that must remain free; below this, new fetches are refused. */
  private volatile double minFreeMemoryFraction = 0.15;

  /** Hard floor: refuse fetches if free memory drops below this many bytes. */
  private volatile long minFreeMemoryBytes = 100L * 1024 * 1024; // 100 MB

  /** Max region size (bp) for READS fetches. */
  private volatile int maxReadRegionBp = 2_500_000;

  // ── Global counters ────────────────────────────────────────────────────────

  /** Total reads currently held across all active READS fetches. */
  private final AtomicLong globalReadCount = new AtomicLong(0);

  /** Number of fetches currently in flight (all types). */
  private final AtomicInteger activeFetches = new AtomicInteger(0);

  /** Per-type active fetch counts. */
  private final ConcurrentHashMap<FetchType, AtomicInteger> activeByType = new ConcurrentHashMap<>();

  /** Monotonically increasing generation counter — bumped on cancelAll(). */
  private volatile long generation = 0;

  /** Per-fetch state keyed by a unique fetch id. */
  private final Map<Long, FetchTicket> tickets = new ConcurrentHashMap<>();

  /** Fetch id sequence. */
  private final AtomicLong nextTicketId = new AtomicLong(1);

  // ── Public API ─────────────────────────────────────────────────────────────

  /**
   * Ask whether a new fetch of the given type and region is allowed.
   * <p>
   * For {@link FetchType#READS}: checks region size, memory, and global read count.
   * For all other types: checks memory only (they have their own region limits).
   *
   * @param type     the category of fetch
   * @param regionBp the genomic region size in base pairs
   * @return true if the fetch may proceed
   */
  public boolean canFetch(FetchType type, int regionBp) {
    // Memory check applies to all types
    if (isMemoryLow()) {
      System.err.println("[FetchManager] Memory too low — blocking " + type + " fetch");
      return false;
    }

    // Type-specific checks
    switch (type) {
      case READS -> {
        if (regionBp > maxReadRegionBp) {
          System.err.println("[FetchManager] READS region too large: " + regionBp + " bp (max " + maxReadRegionBp + ")");
          return false;
        }
        if (globalReadCount.get() > maxGlobalReads) {
          System.err.println("[FetchManager] Global read limit exceeded (" 
              + globalReadCount.get() + " / " + maxGlobalReads + ")");
          return false;
        }
      }
      case SAMPLED_COVERAGE, REFERENCE, FEATURE_TRACK -> {
        // These types manage their own region limits; FetchManager only guards memory
      }
    }
    return true;
  }

  /**
   * Convenience overload: check whether a READS fetch is allowed.
   */
  public boolean canFetch(int regionBp) {
    return canFetch(FetchType.READS, regionBp);
  }

  /**
   * Register a new fetch and obtain a {@link FetchTicket}.
   * The ticket must be {@link #release released} when the fetch completes or is cancelled.
   *
   * @param type  the category of fetch
   * @param owner the object performing the fetch (AlignmentFile, Track, ChromosomeCanvas, etc.)
   * @param stack the DrawStack this fetch belongs to (may be null for stack-independent fetches)
   * @param chrom chromosome name
   * @param start fetch start position (bp)
   * @param end   fetch end position (bp)
   */
  public FetchTicket acquire(FetchType type, Object owner, DrawStack stack,
                             String chrom, int start, int end) {
    long id = nextTicketId.getAndIncrement();
    long gen = generation;
    FetchTicket ticket = new FetchTicket(id, gen, type, owner, stack, chrom, start, end);
    tickets.put(id, ticket);
    activeFetches.incrementAndGet();
    activeByType.computeIfAbsent(type, k -> new AtomicInteger(0)).incrementAndGet();
    return ticket;
  }

  /**
   * Release a ticket and return its accumulated items to the global accounting.
   * Safe to call multiple times (idempotent).
   */
  public void release(FetchTicket ticket) {
    if (ticket == null) return;
    if (tickets.remove(ticket.id) != null) {
      if (ticket.type == FetchType.READS) {
        globalReadCount.addAndGet(-ticket.itemCount.get());
      }
      activeFetches.decrementAndGet();
      AtomicInteger typeCount = activeByType.get(ticket.type);
      if (typeCount != null) typeCount.decrementAndGet();
    }
  }

  /**
   * Record that a READS fetch produced one more read.
   * <p>
   * Only meaningful for {@link FetchType#READS} tickets. For other types
   * the ticket is just used for cancellation detection via {@link FetchTicket#isCancelled()}.
   *
   * @return {@code true} if the fetch may continue;
   *         {@code false} if it must abort (too many reads, memory low, or generation changed).
   */
  public boolean recordRead(FetchTicket ticket) {
    long count = ticket.itemCount.incrementAndGet();
    globalReadCount.incrementAndGet();

    // Check per-fetch limit
    if (count > maxReadsPerFetch) {
      System.err.println("[FetchManager] Per-fetch read limit hit (" + count + ") for "
          + ticket.description() + " " + ticket.chrom + ":" + ticket.start + "-" + ticket.end);
      return false;
    }
    // Periodic global checks (every ~12k reads to avoid overhead)
    if ((count & 0x2FFF) == 0) {
      if (ticket.generation != generation) {
        return false; // generation changed — cancel
      }
      if (globalReadCount.get() > maxGlobalReads) {
        System.err.println("[FetchManager] Global read limit exceeded during fetch");
        return false;
      }
      if (isMemoryLow()) {
        System.err.println("[FetchManager] Memory low during fetch — aborting");
        return false;
      }
    }
    return true;
  }

  /**
   * Cancel ALL in-flight fetches across all types, files, and stacks.
   * Bumps the generation counter so running fetches detect obsolescence quickly.
   * Call this on chromosome change or large navigation jumps.
   */
  public void cancelAll() {
    generation++;
    for (FetchTicket t : tickets.values()) {
      t.cancelled = true;
    }
    System.out.println("[FetchManager] cancelAll — generation " + generation
        + ", active=" + activeFetches.get() + ", reads=" + globalReadCount.get());
  }

  /**
   * Cancel all in-flight fetches of a specific type.
   */
  public void cancelType(FetchType type) {
    for (FetchTicket t : tickets.values()) {
      if (t.type == type) {
        t.cancelled = true;
      }
    }
  }

  /**
   * Cancel fetches for a specific {@link DrawStack} (all types).
   */
  public void cancelForStack(DrawStack stack) {
    for (FetchTicket t : tickets.values()) {
      if (t.stack == stack) {
        t.cancelled = true;
      }
    }
  }

  // ── Memory helpers ─────────────────────────────────────────────────────────

  /** True when JVM free memory is dangerously low. */
  public boolean isMemoryLow() {
    Runtime rt = Runtime.getRuntime();
    long maxMem = rt.maxMemory();
    long usedMem = rt.totalMemory() - rt.freeMemory();
    long free = maxMem - usedMem;
    return free < minFreeMemoryBytes || (double) free / maxMem < minFreeMemoryFraction;
  }

  // ── Status ─────────────────────────────────────────────────────────────────

  /** Number of fetches currently in progress (all types). */
  public int activeFetchCount() { return activeFetches.get(); }

  /** Number of active fetches of a specific type. */
  public int activeFetchCount(FetchType type) {
    AtomicInteger c = activeByType.get(type);
    return c != null ? c.get() : 0;
  }

  /** Total reads currently held in READS fetch buffers. */
  public long globalReadCount() { return globalReadCount.get(); }

  /** Current generation (incremented on cancelAll). */
  public long generation() { return generation; }

  /** Whether any fetch of any type is in progress. */
  public boolean isAnyLoading() { return activeFetches.get() > 0; }

  // ── Settings mutators ──────────────────────────────────────────────────────

  public void setMaxReadsPerFetch(int max)        { this.maxReadsPerFetch = max; }
  public void setMaxGlobalReads(int max)          { this.maxGlobalReads = max; }
  public void setMinFreeMemoryFraction(double f)  { this.minFreeMemoryFraction = f; }
  public void setMinFreeMemoryBytes(long bytes)   { this.minFreeMemoryBytes = bytes; }
  public void setMaxReadRegionBp(int bp)          { this.maxReadRegionBp = bp; }

  public int getMaxReadsPerFetch()       { return maxReadsPerFetch; }
  public int getMaxGlobalReads()         { return maxGlobalReads; }
  public int getMaxReadRegionBp()        { return maxReadRegionBp; }

  // ── Ticket ─────────────────────────────────────────────────────────────────

  /**
   * Token representing one in-flight fetch of any type.
   * <p>
   * For {@link FetchType#READS}, the streaming callback calls
   * {@link FetchManager#recordRead(FetchTicket)} per read.
   * For other types, the ticket is primarily used for cancellation detection
   * via {@link #isCancelled()}.
   */
  public static final class FetchTicket {
    public final long id;
    public final long generation;
    public final FetchType type;
    public final Object owner;
    public final DrawStack stack;
    public final String chrom;
    public final int start;
    public final int end;
    /** Number of items consumed so far (reads for READS, generic counter for others). */
    public final AtomicLong itemCount = new AtomicLong(0);
    /** Set to true by cancelAll / cancelForStack / cancelType. */
    public volatile boolean cancelled;

    FetchTicket(long id, long generation, FetchType type, Object owner,
                DrawStack stack, String chrom, int start, int end) {
      this.id = id;
      this.generation = generation;
      this.type = type;
      this.owner = owner;
      this.stack = stack;
      this.chrom = chrom;
      this.start = start;
      this.end = end;
    }

    /** Whether this ticket has been logically cancelled (by generation bump or explicit cancel). */
    public boolean isCancelled() {
      return cancelled || generation != FetchManager.get().generation;
    }

    /** Human-readable description of the owner for log messages. */
    public String description() {
      return owner != null ? owner.getClass().getSimpleName() : "unknown";
    }
  }
}

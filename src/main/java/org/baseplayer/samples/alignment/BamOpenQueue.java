package org.baseplayer.samples.alignment;

import java.util.Iterator;
import java.util.LinkedHashSet;

import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.samples.Sample;

import javafx.application.Platform;

/**
 * Opens interactive BAM handles off the FX paint path, one at a time.
 * Paint requests an open; this queue does {@link Sample#ensureOpen()} on the
 * shared fetch pool so gene-jump / scroll never blocks the UI thread.
 */
public final class BamOpenQueue {

  private static final BamOpenQueue INSTANCE = new BamOpenQueue();

  private final Object lock = new Object();
  private final LinkedHashSet<Sample> pending = new LinkedHashSet<>();
  private boolean running;

  private BamOpenQueue() {}

  public static BamOpenQueue get() {
    return INSTANCE;
  }

  public void request(Sample sample) {
    if (sample == null || sample.getDataType() != Sample.DataType.BAM) {
      return;
    }
    if (sample.getBamFile() != null || sample.isCoverageOnlyMode()) {
      return;
    }
    synchronized (lock) {
      pending.add(sample);
      if (running) {
        return;
      }
      running = true;
    }
    AlignmentFetchPool.get().submit(this::drainOne);
  }

  private void drainOne() {
    Sample sample;
    synchronized (lock) {
      if (pending.isEmpty()) {
        running = false;
        return;
      }
      Iterator<Sample> it = pending.iterator();
      sample = it.next();
      it.remove();
    }
    try {
      if (sample.getBamFile() == null && !sample.isCoverageOnlyMode()) {
        sample.ensureOpen();
      }
    } catch (Exception ex) {
      System.err.println("BAM open failed for " + sample.getName() + ": " + ex.getMessage());
    }
    Platform.runLater(() -> GenomicCanvas.update.set(!GenomicCanvas.update.get()));
    synchronized (lock) {
      if (pending.isEmpty()) {
        running = false;
      } else {
        AlignmentFetchPool.get().submit(this::drainOne);
      }
    }
  }
}

package org.baseplayer.samples.alignment;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.samples.Sample;

/**
 * Limits how many interactive BAM/CRAM readers stay open for read piles.
 * Coverage background fills use ephemeral opens and do not go through this cache.
 */
public final class BamHandleCache {

  /** Enough for a typical viewport of sample tracks showing read piles. */
  public static final int DEFAULT_MAX_OPEN = 8;

  private static final BamHandleCache INSTANCE = new BamHandleCache();

  private final LinkedHashMap<Sample, Boolean> lru = new LinkedHashMap<>(32, 0.75f, true);
  private int maxOpen = DEFAULT_MAX_OPEN;

  private BamHandleCache() {}

  public static BamHandleCache get() {
    return INSTANCE;
  }

  public synchronized void setMaxOpen(int maxOpen) {
    this.maxOpen = Math.max(1, maxOpen);
    evictIfNeeded();
  }

  public synchronized int getMaxOpen() {
    return maxOpen;
  }

  public synchronized void touch(Sample sample) {
    if (sample == null || sample.getDataType() != Sample.DataType.BAM) {
      return;
    }
    lru.put(sample, Boolean.TRUE);
    evictIfNeeded();
  }

  public synchronized void remove(Sample sample) {
    if (sample != null) {
      lru.remove(sample);
    }
  }

  private void evictIfNeeded() {
    while (lru.size() > maxOpen) {
      Iterator<Map.Entry<Sample, Boolean>> it = lru.entrySet().iterator();
      if (!it.hasNext()) {
        break;
      }
      Sample victim = it.next().getKey();
      it.remove();
      if (victim != null) {
        victim.releaseBam();
      }
    }
  }

  public synchronized List<Sample> snapshot() {
    return new ArrayList<>(lru.keySet());
  }

  public synchronized int size() {
    return lru.size();
  }
}

package org.baseplayer.io.readers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import org.baseplayer.utils.FeatureNameColors;

import javafx.scene.paint.Color;

/**
 * Continues past {@link BedFeatureNameCatalog#SAMPLE_DATA_LINES}, collecting new
 * feature-type names until EOF or a long stretch with no new names.
 */
public final class BedFeatureNameDiscovery {

  /**
   * Stop after this many consecutive named data rows that only repeat already
   * known types (“for a while” with no novelty).
   */
  public static final int IDLE_ROWS_WITHOUT_NEW = 50_000;

  @FunctionalInterface
  public interface CatalogListener {
    void onCatalog(BedFeatureNameCatalog.Catalog catalog);
  }

  private final Path filePath;
  private final boolean stopOnIdle;
  private final long coalesceNanos;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final Thread thread;

  private BedFeatureNameDiscovery(
      Path filePath,
      BedFeatureNameCatalog.Catalog initial,
      boolean stopOnIdle,
      long coalesceMillis,
      CatalogListener listener,
      Runnable onFinished) {
    this.filePath = Objects.requireNonNull(filePath, "filePath");
    this.stopOnIdle = stopOnIdle;
    this.coalesceNanos = Math.max(0L, coalesceMillis) * 1_000_000L;
    this.thread = new Thread(
        () -> run(initial, listener, onFinished),
        "bed-feature-names");
    this.thread.setDaemon(true);
    this.thread.setPriority(Thread.MIN_PRIORITY);
  }

  /**
   * Start background discovery with idle-stop enabled (post-open enrichment).
   * {@code initial} may be {@code null} (sample is built first).
   */
  public static BedFeatureNameDiscovery start(
      Path filePath,
      BedFeatureNameCatalog.Catalog initial,
      CatalogListener listener,
      Runnable onFinished) {
    return start(filePath, initial, true, 150L, listener, onFinished);
  }

  /**
   * @param stopOnIdle when false, scan until EOF/cancel (use while filter dialog
   *        is open so late types like L1HS still appear)
   * @param coalesceMillis minimum gap between live catalog notifications
   */
  public static BedFeatureNameDiscovery start(
      Path filePath,
      BedFeatureNameCatalog.Catalog initial,
      boolean stopOnIdle,
      long coalesceMillis,
      CatalogListener listener,
      Runnable onFinished) {
    BedFeatureNameDiscovery discovery = new BedFeatureNameDiscovery(
        filePath, initial, stopOnIdle, coalesceMillis, listener, onFinished);
    discovery.thread.start();
    return discovery;
  }

  public void cancel() {
    cancelled.set(true);
  }

  public boolean isAlive() {
    return thread.isAlive();
  }

  private void run(
      BedFeatureNameCatalog.Catalog initial,
      CatalogListener listener,
      Runnable onFinished) {
    try {
      Map<String, Mutable> seen = new LinkedHashMap<>();
      int sampleRows = Math.max(1, BedFeatureNameCatalog.SAMPLE_DATA_LINES);

      if (initial != null) {
        sampleRows = Math.max(1, initial.sampleRows());
        for (BedFeatureNameCatalog.Entry entry : initial.entries()) {
          if (entry.otherBucket()) {
            continue;
          }
          seen.put(entry.name(), new Mutable(entry.color(), entry.count()));
        }
        if (!initial.hasOtherBucket()) {
          if (listener != null) {
            listener.onCatalog(initial);
          }
          return;
        }
      }

      boolean isGzipped = filePath.toString().endsWith(".gz");
      int dataLines = 0;
      int idleRows = 0;
      boolean reachedEof = true;
      long lastNotifyNanos = 0L;

      try (BufferedReader reader = openReader(isGzipped)) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (cancelled.get()) {
            reachedEof = false;
            break;
          }
          if (isHeaderOrEmpty(line)) {
            continue;
          }
          String[] parts = line.split("\t", -1);
          if (parts.length < 3) {
            continue;
          }
          dataLines++;
          String name = parts.length > 3 ? parts[3].trim() : "";
          if (name.isEmpty()) {
            continue;
          }
          Color itemRgb = parts.length > 8 ? parseItemRgb(parts[8]) : null;

          if (dataLines <= BedFeatureNameCatalog.SAMPLE_DATA_LINES) {
            if (initial == null) {
              accumulateSample(seen, name, itemRgb);
              if (dataLines == BedFeatureNameCatalog.SAMPLE_DATA_LINES
                  && listener != null
                  && !seen.isEmpty()) {
                sampleRows = dataLines;
                listener.onCatalog(buildCatalog(seen, sampleRows, true));
                lastNotifyNanos = System.nanoTime();
              }
            }
            continue;
          }

          // Past sample window: collect novel names until idle or EOF.
          if (seen.containsKey(name)) {
            idleRows++;
            if (stopOnIdle && idleRows >= IDLE_ROWS_WITHOUT_NEW) {
              reachedEof = false;
              break;
            }
            continue;
          }

          idleRows = 0;
          Color color = itemRgb != null ? itemRgb : FeatureNameColors.colorForName(name);
          seen.put(name, new Mutable(color, 0));
          if (listener != null) {
            long now = System.nanoTime();
            if (coalesceNanos == 0L || now - lastNotifyNanos >= coalesceNanos) {
              listener.onCatalog(buildCatalog(seen, sampleRows, true));
              lastNotifyNanos = now;
            }
          }
        }
      }

      if (cancelled.get() || seen.isEmpty()) {
        return;
      }

      // Short file: never hit the sample limit.
      if (dataLines > 0 && dataLines < BedFeatureNameCatalog.SAMPLE_DATA_LINES) {
        sampleRows = Math.max(1, dataLines);
        reachedEof = true;
      }

      if (listener != null) {
        listener.onCatalog(buildCatalog(seen, sampleRows, !reachedEof));
      }
    } catch (IOException ex) {
      System.err.println(
          "BED feature-name discovery failed for " + filePath.getFileName() + ": "
              + ex.getMessage());
    } finally {
      if (onFinished != null) {
        try {
          onFinished.run();
        } catch (RuntimeException ignored) {
        }
      }
    }
  }

  private BufferedReader openReader(boolean isGzipped) throws IOException {
    if (isGzipped) {
      return new BufferedReader(new InputStreamReader(
          new GZIPInputStream(Files.newInputStream(filePath))));
    }
    return Files.newBufferedReader(filePath);
  }

  private static boolean isHeaderOrEmpty(String line) {
    return line.isEmpty()
        || line.charAt(0) == '#'
        || line.startsWith("track")
        || line.startsWith("browser");
  }

  private static void accumulateSample(Map<String, Mutable> seen, String name, Color itemRgb) {
    Mutable m = seen.get(name);
    if (m == null) {
      Color color = itemRgb != null ? itemRgb : FeatureNameColors.colorForName(name);
      seen.put(name, new Mutable(color, 1));
    } else {
      m.count++;
      if (m.color == null && itemRgb != null) {
        m.color = itemRgb;
      }
    }
  }

  static BedFeatureNameCatalog.Catalog buildCatalog(
      Map<String, Mutable> seen, int sampleRows, boolean hasMoreFile) {
    List<Map.Entry<String, Mutable>> ranked = new ArrayList<>(seen.entrySet());
    ranked.sort(Comparator
        .comparingLong((Map.Entry<String, Mutable> e) -> e.getValue().count)
        .reversed()
        .thenComparing(Map.Entry::getKey));

    List<BedFeatureNameCatalog.Entry> entries = new ArrayList<>(ranked.size() + 1);
    Set<String> listed = new LinkedHashSet<>();
    for (Map.Entry<String, Mutable> e : ranked) {
      Mutable m = e.getValue();
      Color color = m.color != null ? m.color : FeatureNameColors.colorForName(e.getKey());
      entries.add(new BedFeatureNameCatalog.Entry(e.getKey(), color, m.count, false));
      listed.add(e.getKey());
    }
    if (hasMoreFile) {
      entries.add(new BedFeatureNameCatalog.Entry(
          BedFeatureNameCatalog.OTHER_NAME,
          Color.rgb(140, 140, 150),
          0,
          true));
    }
    return new BedFeatureNameCatalog.Catalog(
        List.copyOf(entries),
        Set.copyOf(listed),
        hasMoreFile,
        Math.max(1, sampleRows));
  }

  private static Color parseItemRgb(String csvRgb) {
    if (csvRgb == null || csvRgb.isBlank() || ".".equals(csvRgb.trim())) {
      return null;
    }
    try {
      String[] parts = csvRgb.split(",");
      if (parts.length == 3) {
        int r = Integer.parseInt(parts[0].trim());
        int g = Integer.parseInt(parts[1].trim());
        int b = Integer.parseInt(parts[2].trim());
        if (r == 0 && g == 0 && b == 0) {
          return null;
        }
        return Color.rgb(r, g, b);
      }
    } catch (RuntimeException ignored) {
    }
    return null;
  }

  static final class Mutable {
    Color color;
    long count;

    Mutable(Color color, long count) {
      this.color = color;
      this.count = count;
    }
  }
}

package org.baseplayer.features;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.baseplayer.io.VcfManager;
import org.baseplayer.io.readers.BedFileReader.BedFeature;
import org.baseplayer.services.FeatureTrackViewportRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.utils.ChromosomeNames;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VariantTypeVisuals;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

/**
 * BED feature tracks used as custom annotation for sample (VCF) variants.
 *
 * <p>Any number of tracks may be enabled. Each contributes an Excel column with
 * overlapping feature names. Optional {@link Mode#INTERSECT} / {@link Mode#SUBTRACT}
 * also hide variants on canvas/tables:
 * <ul>
 *   <li>INTERSECT — variant must overlap <em>every</em> such track</li>
 *   <li>SUBTRACT — variant must overlap <em>none</em> of those tracks</li>
 *   <li>{@link Mode#ANNOTATE} — Excel only, no visibility filter</li>
 * </ul>
 */
public final class BedVariantAnnotation {

  public enum Mode {
    OFF,
    /** Excel column only; does not hide variants. */
    ANNOTATE,
    INTERSECT,
    SUBTRACT;

    public static Mode fromPersisted(String value) {
      if (value == null || value.isBlank()) {
        return OFF;
      }
      return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "annotate", "annotation", "on" -> ANNOTATE;
        case "intersect" -> INTERSECT;
        case "subtract" -> SUBTRACT;
        default -> OFF;
      };
    }

    public String toPersisted() {
      return switch (this) {
        case ANNOTATE -> "annotate";
        case INTERSECT -> "intersect";
        case SUBTRACT -> "subtract";
        case OFF -> null;
      };
    }

    public boolean isActive() {
      return this != OFF;
    }

    public boolean filtersVisibility() {
      return this == INTERSECT || this == SUBTRACT;
    }
  }

  private static final int INDEXED_ANNOTATION_MAX_FEATURES = 2_000_000;

  private static volatile int generation;
  /**
   * When false, INTERSECT/SUBTRACT tracks stay configured (Excel still works) but
   * do not hide variants — toggled by play/stop on the feature track row.
   */
  private static volatile boolean filterRunning = true;
  private static volatile boolean filterBusy;
  private static volatile double busyAngleDeg;
  private static final IntegerProperty uiPulse = new SimpleIntegerProperty(0);
  private static final AtomicInteger refreshSerial = new AtomicInteger();
  private static AnimationTimer busyTimer;
  /** Cache key {@code gen|trackId|chrom} → features. */
  private static final ConcurrentHashMap<String, List<BedFeature>> featureCache =
      new ConcurrentHashMap<>();

  private BedVariantAnnotation() {}

  public static int generation() {
    return generation;
  }

  /** True while at least one track is INTERSECT or SUBTRACT. */
  public static boolean hasVisibilityFilterTracks() {
    for (BedTrack track : annotationTracks()) {
      if (track.getVariantAnnotationMode().filtersVisibility()) {
        return true;
      }
    }
    return false;
  }

  /** Whether INTERSECT/SUBTRACT hiding is currently applied. */
  public static boolean isFilterRunning() {
    return filterRunning;
  }

  /** True while a filter rebuild (play/stop or mode change) is in progress. */
  public static boolean isFilterBusy() {
    return filterBusy;
  }

  public static double busyAngleDeg() {
    return busyAngleDeg;
  }

  /** Bumped while the busy spinner animates; feature list listens to redraw. */
  public static IntegerProperty uiPulseProperty() {
    return uiPulse;
  }

  public static void setFilterRunning(boolean running) {
    if (filterRunning == running) {
      return;
    }
    filterRunning = running;
    bumpAndRefreshVariants();
  }

  public static void toggleFilterRunning() {
    if (filterBusy) {
      return;
    }
    setFilterRunning(!filterRunning);
  }

  /** Fragment included in variant visible-chain filter keys. */
  public static String filterKey() {
    List<BedTrack> tracks = annotationTracks();
    if (tracks.isEmpty()) {
      return "bedAnn=off";
    }
    StringBuilder sb = new StringBuilder("bedAnn=")
        .append(generation)
        .append("|run=")
        .append(filterRunning);
    for (BedTrack track : tracks) {
      sb.append('|').append(track.getVariantAnnotationMode().name()).append(':');
      if (track.getSourcePath() != null) {
        sb.append(track.getSourcePath());
      } else {
        sb.append(track.getName());
      }
    }
    return sb.toString();
  }

  /** All BED feature tracks currently marked for variant annotation. */
  public static List<BedTrack> annotationTracks() {
    FeatureTrackViewportRegistry registry =
        ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();
    if (registry == null) {
      return List.of();
    }
    List<BedTrack> out = new ArrayList<>();
    for (Track track : registry.getFeatureTracks()) {
      if (track instanceof BedTrack bed && bed.getVariantAnnotationMode().isActive()) {
        out.add(bed);
      }
    }
    return out;
  }

  /**
   * Set annotation mode on {@code track}. Multiple tracks may be active at once.
   */
  public static void setMode(BedTrack track, Mode mode) {
    if (track == null) {
      return;
    }
    Mode next = mode != null ? mode : Mode.OFF;
    Mode prev = track.getVariantAnnotationMode();
    if (prev == next) {
      return;
    }
    track.setVariantAnnotationMode(next);
    if (!hasVisibilityFilterTracks()) {
      filterRunning = true;
    }
    bumpAndRefreshVariants();
  }

  public static void clearIfTrack(Track track) {
    if (track instanceof BedTrack bed && bed.getVariantAnnotationMode().isActive()) {
      bed.setVariantAnnotationMode(Mode.OFF);
      if (!hasVisibilityFilterTracks()) {
        filterRunning = true;
      }
      bumpAndRefreshVariants();
    }
  }

  public static void bumpAndRefreshVariants() {
    generation++;
    featureCache.clear();
    // Visibility filters (and toggling them off) rebuild chains off the FX thread
    // with a spinner; annotate-only changes stay cheap.
    boolean needsRebuild = hasVisibilityFilterTracks() || !filterRunning;
    if (needsRebuild) {
      startAsyncFilterRefresh();
    } else {
      lightRefreshUi();
    }
  }

  private static void lightRefreshUi() {
    Runnable refresh = () -> {
      VcfManager vcf = VcfManager.getInstance();
      if (vcf != null) {
        vcf.invalidateVisibleChainKeysForCache();
        vcf.redrawSampleCanvases();
      }
      uiPulse.set(uiPulse.get() + 1);
      org.baseplayer.draw.GenomicCanvas.update.set(
          !org.baseplayer.draw.GenomicCanvas.update.get());
    };
    if (Platform.isFxApplicationThread()) {
      refresh.run();
    } else {
      Platform.runLater(refresh);
    }
  }

  private static void startAsyncFilterRefresh() {
    final int serial = refreshSerial.incrementAndGet();
    final int gen = generation;
    Runnable startBusy = () -> {
      filterBusy = true;
      startBusyTimer();
      uiPulse.set(uiPulse.get() + 1);
      VcfManager vcf = VcfManager.getInstance();
      if (vcf != null) {
        vcf.invalidateVisibleChainKeysForCache();
      }
    };
    if (Platform.isFxApplicationThread()) {
      startBusy.run();
    } else {
      Platform.runLater(startBusy);
    }

    final VariantFilter filterSnapshot = snapshotFilter();
    ThreadRunner.get().submit(
        "BED variant filter",
        () -> {
          VcfManager vcf = VcfManager.getInstance();
          if (vcf != null && serial == refreshSerial.get() && gen == generation) {
            // Prefetch annotation intervals for cached chromosomes, then rebuild.
            for (VariantList list : vcf.snapshotVariantCache().values()) {
              if (list == null || list.isEmpty()) {
                continue;
              }
              for (BedTrack track : annotationTracks()) {
                if (track.getVariantAnnotationMode().filtersVisibility()) {
                  featuresFor(track, list.getChromosome());
                }
              }
            }
            vcf.rebuildVisibleChainsForCache(filterSnapshot);
          }
          return null;
        },
        ignored -> {
          if (serial != refreshSerial.get()) {
            return;
          }
          filterBusy = false;
          stopBusyTimer();
          VcfManager vcf = VcfManager.getInstance();
          if (vcf != null) {
            vcf.redrawSampleCanvases();
          }
          uiPulse.set(uiPulse.get() + 1);
          org.baseplayer.draw.GenomicCanvas.update.set(
              !org.baseplayer.draw.GenomicCanvas.update.get());
        });
  }

  private static VariantFilter snapshotFilter() {
    VcfManager vcf = VcfManager.getInstance();
    if (vcf == null || vcf.getCurrentFilter() == null) {
      return new VariantFilter();
    }
    return vcf.getCurrentFilter().copy();
  }

  private static void startBusyTimer() {
    if (busyTimer == null) {
      busyTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
          busyAngleDeg = (now / 2_000_000.0) % 360.0;
          uiPulse.set(uiPulse.get() + 1);
        }
      };
    }
    busyTimer.start();
  }

  private static void stopBusyTimer() {
    if (busyTimer != null) {
      busyTimer.stop();
    }
  }

  /**
   * Whether {@code node} passes INTERSECT/SUBTRACT rules from all annotation
   * tracks. {@link Mode#ANNOTATE} tracks do not affect visibility.
   */
  public static boolean passes(VariantList list, VariantNode node) {
    if (list == null || node == null || !filterRunning) {
      return true;
    }
    List<BedTrack> tracks = annotationTracks();
    if (tracks.isEmpty()) {
      return true;
    }
    long start1 = node.position;
    long end1 = node.position;
    if (VariantTypeVisuals.isSpanningCall(node.type) && node.svEnd > node.position) {
      end1 = node.svEnd;
    }
    String chrom = list.getChromosome();
    for (BedTrack track : tracks) {
      Mode mode = track.getVariantAnnotationMode();
      if (!mode.filtersVisibility()) {
        continue;
      }
      boolean overlaps = overlapsAny(featuresFor(track, chrom), start1, end1);
      if (mode == Mode.INTERSECT && !overlaps) {
        return false;
      }
      if (mode == Mode.SUBTRACT && overlaps) {
        return false;
      }
    }
    return true;
  }

  /**
   * Stable Excel column headers for active annotation tracks (track display names,
   * disambiguated if duplicated).
   */
  public static List<String> excelColumnHeaders() {
    List<BedTrack> tracks = annotationTracks();
    if (tracks.isEmpty()) {
      return List.of();
    }
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (BedTrack track : tracks) {
      String base = excelHeaderBase(track);
      counts.merge(base, 1, Integer::sum);
    }
    Map<String, Integer> seen = new LinkedHashMap<>();
    List<String> headers = new ArrayList<>(tracks.size());
    for (BedTrack track : tracks) {
      String base = excelHeaderBase(track);
      if (counts.getOrDefault(base, 0) > 1) {
        int n = seen.merge(base, 1, Integer::sum);
        headers.add(base + " (" + n + ")");
      } else {
        headers.add(base);
      }
    }
    return headers;
  }

  /**
   * Per-track overlapping feature names for Excel (same order as
   * {@link #excelColumnHeaders()}). Empty string when no overlap.
   */
  public static List<String> excelColumnValues(String chromosome, VariantNode node) {
    List<BedTrack> tracks = annotationTracks();
    if (tracks.isEmpty() || node == null) {
      return List.of();
    }
    long start1 = node.position;
    long end1 = node.position;
    if (VariantTypeVisuals.isSpanningCall(node.type) && node.svEnd > node.position) {
      end1 = node.svEnd;
    }
    List<String> values = new ArrayList<>(tracks.size());
    for (BedTrack track : tracks) {
      values.add(overlappingNames(track, chromosome, start1, end1));
    }
    return values;
  }

  private static String excelHeaderBase(BedTrack track) {
    String name = track.getName();
    if (name == null || name.isBlank()) {
      return "BED annotation";
    }
    return name.trim();
  }

  private static String overlappingNames(
      BedTrack track, String chromosome, long start1, long end1) {
    List<BedFeature> features = featuresFor(track, chromosome);
    if (features.isEmpty()) {
      return "";
    }
    Set<String> names = new LinkedHashSet<>();
    int i = BedTrack.findFirstOverlappingIndex(features, start1, end1);
    while (i < features.size() && features.get(i).start() + 1 <= end1) {
      BedFeature f = features.get(i);
      if (f.end() >= start1) {
        String name = f.name();
        if (name != null && !name.isBlank() && !".".equals(name)) {
          names.add(name.trim());
        } else {
          names.add(f.start() + "-" + f.end());
        }
      }
      i++;
    }
    if (names.isEmpty()) {
      return "";
    }
    return String.join(";", names);
  }

  private static List<BedFeature> featuresFor(BedTrack track, String chromosome) {
    String bare = ChromosomeNames.strip(chromosome);
    if (track == null || bare == null || bare.isEmpty()) {
      return List.of();
    }
    String trackId = track.getSourcePath() != null
        ? track.getSourcePath().toString()
        : ("name:" + track.getName());
    String key = generation + "|" + trackId + "|" + bare;
    return featureCache.computeIfAbsent(key, k -> {
      List<BedFeature> features =
          track.getAnnotationFeatures(bare, INDEXED_ANNOTATION_MAX_FEATURES);
      return features != null ? features : List.of();
    });
  }

  /** 1-based inclusive genomic interval vs BED 0-based half-open features. */
  static boolean overlapsAny(List<BedFeature> features, long start1, long end1) {
    if (features == null || features.isEmpty() || end1 < start1) {
      return false;
    }
    int i = BedTrack.findFirstOverlappingIndex(features, start1, end1);
    while (i < features.size() && features.get(i).start() + 1 <= end1) {
      BedFeature f = features.get(i);
      if (f.end() >= start1) {
        return true;
      }
      i++;
    }
    return false;
  }
}

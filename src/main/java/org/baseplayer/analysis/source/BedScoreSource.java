package org.baseplayer.analysis.source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.baseplayer.analysis.CollectContext;
import org.baseplayer.analysis.ComparisonUnit;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.GeneOverlapIndex;
import org.baseplayer.analysis.GenomicScope;
import org.baseplayer.analysis.signal.SignalScopeHelper;
import org.baseplayer.analysis.signal.SignalScopeHelper.Region;
import org.baseplayer.analysis.signal.SignalSourceSpec;
import org.baseplayer.features.BedTrack;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.io.readers.BedFileReader.BedFeature;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;

/**
 * Maps per-sample BED scores onto genes, windows, or native features.
 */
public final class BedScoreSource implements ValueSource {

  private static final int MAX_FEATURES_PER_CHROM = 5_000_000;

  private final boolean requireScores;

  public BedScoreSource() {
    this(true);
  }

  public BedScoreSource(boolean requireScores) {
    this.requireScores = requireScores;
  }

  @Override
  public String label() {
    return requireScores ? "BED scores" : "BED features";
  }

  @Override
  public String collect(List<SampleTrack> tracks, FeatureValueMatrix matrix, SignalSourceSpec spec) {
    return collect(tracks, matrix, spec, null);
  }

  @Override
  public String collect(
      List<SampleTrack> tracks,
      FeatureValueMatrix matrix,
      SignalSourceSpec spec,
      CollectContext ctx) {
    if (tracks == null || matrix == null) {
      return "No tracks";
    }
    SignalSourceSpec effective = spec != null ? spec : new SignalSourceSpec();
    boolean require = effective.isRequireBedScores() || requireScores;
    ComparisonUnit unit = effective.getUnit() != null ? effective.getUnit() : ComparisonUnit.GENE;
    return switch (unit) {
      case GENE -> collectGenes(tracks, matrix, effective, require, ctx);
      case WINDOW -> collectWindows(tracks, matrix, effective, require, ctx);
      case NATIVE_FEATURE -> collectNative(tracks, matrix, effective, require, ctx);
    };
  }

  private String collectGenes(
      List<SampleTrack> tracks,
      FeatureValueMatrix matrix,
      SignalSourceSpec spec,
      boolean require,
      CollectContext ctx) {
    int used = 0;
    int skipped = 0;
    StringBuilder notes = new StringBuilder();
    GenomicScope scope = spec.getScope();
    int total = Math.max(1, tracks.size());
    int step = 0;
    for (SampleTrack track : tracks) {
      check(ctx);
      step++;
      progress(ctx, step, total, track.getDisplayName());
      BedTrack bed = firstBed(track);
      if (bed == null || (require && !bed.hasScores())) {
        skipped++;
        continue;
      }
      Map<String, List<Double>> perGene = new HashMap<>();
      Map<String, Gene> geneMeta = new HashMap<>();
      Set<String> chroms = new HashSet<>(SignalScopeHelper.chromosomes(scope, bed.getChromosomes()));
      if (chroms.isEmpty()) {
        chroms.addAll(bed.getLoadedChromosomes());
      }
      for (String chrom : chroms) {
        check(ctx);
        List<BedFeature> features = bed.getAnnotationFeatures(chrom, MAX_FEATURES_PER_CHROM);
        if (features == null || features.isEmpty()) {
          continue;
        }
        for (int i = 0; i < features.size(); i++) {
          if ((i & 0x3FF) == 0) {
            check(ctx);
          }
          BedFeature feature = features.get(i);
          if (!inScope(feature, chrom, scope)) {
            continue;
          }
          Gene gene = GeneOverlapIndex.resolveByName(feature.name());
          if (gene == null) {
            long start1 = feature.start() + 1;
            long end1 = Math.max(start1, feature.end());
            gene = GeneOverlapIndex.bestGeneOverlapping(chrom, start1, end1);
          }
          if (gene == null || gene.name() == null || gene.name().isBlank()) {
            continue;
          }
          perGene.computeIfAbsent(gene.name(), k -> new ArrayList<>()).add(feature.score());
          geneMeta.putIfAbsent(gene.name(), gene);
        }
      }
      if (perGene.isEmpty()) {
        skipped++;
        continue;
      }
      used++;
      putCollapsed(matrix, track, perGene, geneMeta, spec);
      appendNote(notes, track.getDisplayName(), perGene.size() + " genes");
    }
    return formatNotes(used, skipped, notes);
  }

  private String collectWindows(
      List<SampleTrack> tracks,
      FeatureValueMatrix matrix,
      SignalSourceSpec spec,
      boolean require,
      CollectContext ctx) {
    Set<String> available = new HashSet<>();
    for (SampleTrack track : tracks) {
      BedTrack bed = firstBed(track);
      if (bed != null) {
        available.addAll(bed.getChromosomes());
      }
    }
    List<Region> windows = SignalScopeHelper.windowsForScope(spec.getScope(), spec.getWindowBp(), available);
    if (windows.isEmpty()) {
      return label() + ": no windows in scope (try Current view)";
    }
    int used = 0;
    int skipped = 0;
    StringBuilder notes = new StringBuilder();
    int total = Math.max(1, tracks.size());
    int step = 0;
    for (SampleTrack track : tracks) {
      check(ctx);
      step++;
      progress(ctx, step, total, track.getDisplayName() + " · windows");
      BedTrack bed = firstBed(track);
      if (bed == null || (require && !bed.hasScores())) {
        skipped++;
        continue;
      }
      int filled = 0;
      for (int w = 0; w < windows.size(); w++) {
        if ((w & 0xFF) == 0) {
          check(ctx);
        }
        Region window = windows.get(w);
        List<BedFeature> features = bed.getAnnotationFeatures(window.chrom(), MAX_FEATURES_PER_CHROM);
        List<Double> scores = new ArrayList<>();
        if (features != null) {
          for (BedFeature feature : features) {
            long start1 = feature.start() + 1;
            long end1 = Math.max(start1, feature.end());
            if (end1 < window.start1Based() || start1 > window.end1Based()) {
              continue;
            }
            scores.add(feature.score());
          }
        }
        if (scores.isEmpty()) {
          continue;
        }
        double collapsed = FeatureValueMatrix.collapse(scores, spec.getAggregation());
        if (Double.isNaN(collapsed)) {
          continue;
        }
        String id = SignalScopeHelper.windowUnitId(window);
        matrix.put(id, track, collapsed);
        matrix.putLocus(id, window.chrom(), window.start1Based(), window.end1Based());
        filled++;
      }
      if (filled == 0) {
        skipped++;
        continue;
      }
      used++;
      appendNote(notes, track.getDisplayName(), filled + " windows");
    }
    String cap = windows.size() >= SignalSourceSpec.MAX_WINDOW_UNITS
        ? " (capped at " + SignalSourceSpec.MAX_WINDOW_UNITS + " windows)"
        : "";
    return formatNotes(used, skipped, notes) + cap;
  }

  private String collectNative(
      List<SampleTrack> tracks,
      FeatureValueMatrix matrix,
      SignalSourceSpec spec,
      boolean require,
      CollectContext ctx) {
    int used = 0;
    int skipped = 0;
    StringBuilder notes = new StringBuilder();
    GenomicScope scope = spec.getScope();
    int total = Math.max(1, tracks.size());
    int step = 0;
    for (SampleTrack track : tracks) {
      check(ctx);
      step++;
      progress(ctx, step, total, track.getDisplayName());
      BedTrack bed = firstBed(track);
      if (bed == null || (require && !bed.hasScores())) {
        skipped++;
        continue;
      }
      int filled = 0;
      Set<String> chroms = new HashSet<>(SignalScopeHelper.chromosomes(scope, bed.getChromosomes()));
      if (chroms.isEmpty()) {
        chroms.addAll(bed.getLoadedChromosomes());
      }
      for (String chrom : chroms) {
        check(ctx);
        List<BedFeature> features = bed.getAnnotationFeatures(chrom, MAX_FEATURES_PER_CHROM);
        if (features == null) {
          continue;
        }
        int index = 0;
        for (int i = 0; i < features.size(); i++) {
          if ((i & 0x3FF) == 0) {
            check(ctx);
          }
          BedFeature feature = features.get(i);
          if (!inScope(feature, chrom, scope)) {
            continue;
          }
          long start1 = feature.start() + 1;
          long end1 = Math.max(start1, feature.end());
          String name = feature.name() != null && !feature.name().isBlank() && !".".equals(feature.name())
              ? feature.name()
              : (chrom + ":" + start1 + "-" + end1);
          String id = name + "#" + index++;
          matrix.put(id, track, feature.score());
          matrix.putLocus(id, chrom, start1, end1);
          filled++;
        }
      }
      if (filled == 0) {
        skipped++;
        continue;
      }
      used++;
      appendNote(notes, track.getDisplayName(), filled + " features");
    }
    return formatNotes(used, skipped, notes);
  }

  private static void check(CollectContext ctx) {
    if (ctx != null) {
      ctx.checkCancelled();
    }
  }

  private static void progress(CollectContext ctx, int done, int total, String msg) {
    if (ctx != null) {
      ctx.progress(done, total, msg);
    }
  }

  private static void putCollapsed(
      FeatureValueMatrix matrix,
      SampleTrack track,
      Map<String, List<Double>> perUnit,
      Map<String, Gene> geneMeta,
      SignalSourceSpec spec) {
    for (Map.Entry<String, List<Double>> e : perUnit.entrySet()) {
      double collapsed = FeatureValueMatrix.collapse(e.getValue(), spec.getAggregation());
      if (Double.isNaN(collapsed)) {
        continue;
      }
      matrix.put(e.getKey(), track, collapsed);
      Gene gene = geneMeta.get(e.getKey());
      if (gene != null) {
        matrix.putLocus(e.getKey(), gene.chrom(), gene.start(), gene.end());
      }
    }
  }

  private static boolean inScope(BedFeature feature, String chrom, GenomicScope scope) {
    if (scope == GenomicScope.ALL_CHROMS || scope == null) {
      return true;
    }
    Region view = SignalScopeHelper.currentView();
    if (view == null) {
      return scope != GenomicScope.CURRENT_VIEW;
    }
    if (!chrom.equals(view.chrom()) && !chrom.equals("chr" + view.chrom())) {
      return false;
    }
    if (scope == GenomicScope.CURRENT_CHROM) {
      return true;
    }
    long start1 = feature.start() + 1;
    long end1 = Math.max(start1, feature.end());
    return end1 >= view.start1Based() && start1 <= view.end1Based();
  }

  private String formatNotes(int used, int skipped, StringBuilder notes) {
    return String.format(
        Locale.ROOT,
        "%s: %d samples used, %d skipped%s%s",
        label(),
        used,
        skipped,
        notes.length() > 0 ? " — " : "",
        notes);
  }

  private static void appendNote(StringBuilder notes, String name, String detail) {
    if (notes.length() > 0) {
      notes.append("; ");
    }
    notes.append(name).append('=').append(detail);
  }

  public static BedTrack firstBed(SampleTrack track) {
    if (track == null) {
      return null;
    }
    for (Sample sample : track.getSamples()) {
      if (sample != null && sample.getDataType() == Sample.DataType.BED && sample.getBedTrack() != null) {
        return sample.getBedTrack();
      }
    }
    return null;
  }

  public static boolean trackHasScoredBed(SampleTrack track) {
    BedTrack bed = firstBed(track);
    return bed != null && bed.hasScores();
  }
}

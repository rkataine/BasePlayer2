package org.baseplayer.analysis.source;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.baseplayer.analysis.signal.BigWigCompanionRule;
import org.baseplayer.analysis.CollectContext;
import org.baseplayer.analysis.ComparisonUnit;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.GenomicScope;
import org.baseplayer.analysis.signal.SignalScopeHelper;
import org.baseplayer.analysis.signal.SignalScopeHelper.Region;
import org.baseplayer.analysis.signal.SignalSourceSpec;
import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.features.BigWigTrack;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.utils.ChromosomeNames;
import org.baseplayer.utils.GeneBiotypeVisibility;

/**
 * BigWig signal → gene / window / native (gene-sized query) matrix.
 */
public final class BigWigValueSource implements ValueSource {

  @Override
  public String label() {
    return "BigWig signal";
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
    ComparisonUnit unit = effective.getUnit() != null ? effective.getUnit() : ComparisonUnit.GENE;
    return switch (unit) {
      case GENE -> collectGenes(tracks, matrix, effective, ctx);
      case WINDOW -> collectWindows(tracks, matrix, effective, ctx);
      case NATIVE_FEATURE -> collectGenes(tracks, matrix, effective, ctx);
    };
  }

  private String collectGenes(
      List<SampleTrack> tracks,
      FeatureValueMatrix matrix,
      SignalSourceSpec spec,
      CollectContext ctx) {
    if (!AnnotationData.isGenesLoaded()) {
      return "Gene annotation not loaded";
    }
    int used = 0;
    int skipped = 0;
    StringBuilder notes = new StringBuilder();
    Map<String, List<Gene>> byChrom = AnnotationData.getGenesByChrom();
    List<String> chroms = SignalScopeHelper.chromosomes(spec.getScope(), byChrom.keySet());
    Region view = SignalScopeHelper.currentView();
    int totalSteps = Math.max(1, tracks.size() * Math.max(1, chroms.size()));
    int step = 0;
    for (SampleTrack track : tracks) {
      check(ctx);
      BigWigTrack wig = resolveBigWig(track, spec);
      if (wig == null) {
        skipped++;
        step += Math.max(1, chroms.size());
        progress(ctx, step, totalSteps, track.getDisplayName() + " (skip)");
        continue;
      }
      int geneCount = 0;
      for (String chrom : chroms) {
        check(ctx);
        step++;
        progress(ctx, step, totalSteps, track.getDisplayName() + " · chr" + chrom);
        List<Gene> genes = byChrom.get(ChromosomeNames.strip(chrom));
        if (genes == null) {
          genes = byChrom.get(chrom);
        }
        if (genes == null) {
          continue;
        }
        GeneBiotypeVisibility visibility = GeneBiotypeVisibility.get();
        for (Gene gene : genes) {
          if ((geneCount & 0x3FF) == 0) {
            check(ctx);
          }
          if (gene.name() == null || gene.name().isBlank()) {
            continue;
          }
          if (!visibility.isVisible(gene)) {
            continue;
          }
          if (spec.getScope() == GenomicScope.CURRENT_VIEW && view != null) {
            if (gene.end() < view.start1Based() || gene.start() > view.end1Based()) {
              continue;
            }
          }
          Double mean = wig.meanValue(gene.chrom() != null ? gene.chrom() : chrom, gene.start(), gene.end());
          if (mean == null || Double.isNaN(mean)) {
            continue;
          }
          matrix.put(gene.name(), track, mean);
          matrix.putLocus(gene.name(), gene.chrom(), gene.start(), gene.end());
          geneCount++;
        }
      }
      if (geneCount == 0) {
        skipped++;
        continue;
      }
      used++;
      if (notes.length() > 0) {
        notes.append("; ");
      }
      notes.append(track.getDisplayName()).append('=').append(wig.getName());
    }
    return String.format(
        Locale.ROOT,
        "%s: %d samples used, %d skipped%s%s",
        label(),
        used,
        skipped,
        notes.length() > 0 ? " — " : "",
        notes);
  }

  private String collectWindows(
      List<SampleTrack> tracks,
      FeatureValueMatrix matrix,
      SignalSourceSpec spec,
      CollectContext ctx) {
    Set<String> available = new HashSet<>();
    if (AnnotationData.isGenesLoaded()) {
      available.addAll(AnnotationData.getGenesByChrom().keySet());
    }
    List<Region> windows = SignalScopeHelper.windowsForScope(spec.getScope(), spec.getWindowBp(), available);
    if (windows.isEmpty()) {
      return label() + ": no windows in scope (try Current view)";
    }
    int used = 0;
    int skipped = 0;
    StringBuilder notes = new StringBuilder();
    int totalSteps = Math.max(1, tracks.size());
    int step = 0;
    for (SampleTrack track : tracks) {
      check(ctx);
      step++;
      progress(ctx, step, totalSteps, track.getDisplayName() + " · windows");
      BigWigTrack wig = resolveBigWig(track, spec);
      if (wig == null) {
        skipped++;
        continue;
      }
      int filled = 0;
      for (int i = 0; i < windows.size(); i++) {
        if ((i & 0xFF) == 0) {
          check(ctx);
        }
        Region window = windows.get(i);
        Double mean = wig.meanValue(window.chrom(), window.start1Based(), window.end1Based());
        if (mean == null || Double.isNaN(mean)) {
          continue;
        }
        String id = SignalScopeHelper.windowUnitId(window);
        matrix.put(id, track, mean);
        matrix.putLocus(id, window.chrom(), window.start1Based(), window.end1Based());
        filled++;
      }
      if (filled == 0) {
        skipped++;
        continue;
      }
      used++;
      if (notes.length() > 0) {
        notes.append("; ");
      }
      notes.append(track.getDisplayName()).append('=').append(wig.getName());
    }
    String cap = windows.size() >= SignalSourceSpec.MAX_WINDOW_UNITS
        ? " (capped at " + SignalSourceSpec.MAX_WINDOW_UNITS + " windows)"
        : "";
    return String.format(
        Locale.ROOT,
        "%s windows: %d samples used, %d skipped%s%s%s",
        label(),
        used,
        skipped,
        notes.length() > 0 ? " — " : "",
        notes,
        cap);
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

  public static BigWigTrack resolveBigWig(SampleTrack track, SignalSourceSpec spec) {
    if (track == null) {
      return null;
    }
    BigWigCompanionRule rule = spec != null && spec.getBigWigRule() != null
        ? spec.getBigWigRule()
        : BigWigCompanionRule.FIRST;
    String contains = spec != null ? spec.getBigWigNameContains() : "";
    BigWigTrack first = null;
    for (Sample sample : track.getSamples()) {
      if (sample == null
          || sample.getDataType() != Sample.DataType.BIGWIG
          || sample.getBigWigTrack() == null) {
        continue;
      }
      BigWigTrack wig = sample.getBigWigTrack();
      if (first == null) {
        first = wig;
      }
      if (rule == BigWigCompanionRule.FIRST) {
        return wig;
      }
      if (rule.matches(wig, contains)) {
        return wig;
      }
    }
    return rule == BigWigCompanionRule.FIRST ? first : null;
  }

  public static BigWigTrack firstBigWig(SampleTrack track) {
    return resolveBigWig(track, null);
  }

  public static boolean trackHasBigWig(SampleTrack track) {
    return firstBigWig(track) != null;
  }

  public static boolean trackHasMatchingBigWig(SampleTrack track, SignalSourceSpec spec) {
    return resolveBigWig(track, spec) != null;
  }
}

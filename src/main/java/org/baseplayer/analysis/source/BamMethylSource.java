package org.baseplayer.analysis.source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.baseplayer.analysis.CollectContext;
import org.baseplayer.analysis.ComparisonUnit;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.GeneOverlapIndex;
import org.baseplayer.analysis.signal.SignalScopeHelper;
import org.baseplayer.analysis.signal.SignalScopeHelper.Region;
import org.baseplayer.analysis.signal.SignalSourceSpec;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.samples.alignment.AlignmentFile;
import org.baseplayer.samples.alignment.draw.CoverageDrawer;
import org.baseplayer.utils.ChromosomeNames;

/**
 * Viewport BAM methylation ratios aggregated to genes or windows.
 * Uses already-computed {@link CoverageDrawer} rows (no whole-genome scan).
 */
public final class BamMethylSource implements ValueSource {

  private final DrawStack drawStack;

  public BamMethylSource(DrawStack drawStack) {
    this.drawStack = drawStack;
  }

  public BamMethylSource() {
    this(SignalScopeHelper.activeStack());
  }

  @Override
  public String label() {
    return "BAM methylation (current view)";
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
    DrawStack stack = drawStack != null ? drawStack : SignalScopeHelper.activeStack();
    if (stack == null
        || stack.sampleAggregateCanvas == null
        || stack.sampleAggregateCanvas.getCoverageDrawer() == null) {
      return "Coverage drawer unavailable — zoom into a region with methylation BAMs first";
    }
    CoverageDrawer drawer = stack.sampleAggregateCanvas.getCoverageDrawer();
    CoverageDrawer.SampleRow[] rows = drawer.getRows();
    if (rows == null || rows.length == 0) {
      return "No methylation coverage in the current view yet — wait for load, then Run";
    }

    String chromKey = ChromosomeNames.strip(stack.getChromosome());
    long start = Math.max(1, (long) Math.floor(stack.getViewStart()));
    long end = Math.max(start, (long) Math.ceil(stack.getViewEnd()));
    long span = Math.max(1, end - start + 1);

    Map<SampleTrack, CoverageDrawer.SampleRow> rowByTrack = new IdentityHashMap<>();
    for (CoverageDrawer.SampleRow row : rows) {
      if (row == null || row.sample == null || !row.sample.isMethylationData()) {
        continue;
      }
      SampleTrack track = row.sample.getTrack();
      if (track != null) {
        rowByTrack.put(track, row);
      }
    }

    SignalSourceSpec effective = spec != null ? spec : new SignalSourceSpec();
    ComparisonUnit unit = effective.getUnit() != null ? effective.getUnit() : ComparisonUnit.GENE;
    if (unit == ComparisonUnit.NATIVE_FEATURE) {
      unit = ComparisonUnit.WINDOW;
    }

    int used = 0;
    int skipped = 0;
    int total = Math.max(1, tracks.size());
    int step = 0;
    for (SampleTrack track : tracks) {
      if (ctx != null) {
        ctx.checkCancelled();
      }
      step++;
      if (ctx != null) {
        ctx.progress(step, total, track.getDisplayName());
      }
      CoverageDrawer.SampleRow row = rowByTrack.get(track);
      if (row == null || row.smoothedMethylRatio == null) {
        skipped++;
        continue;
      }
      int n = row.smoothedMethylRatio.length;
      if (unit == ComparisonUnit.WINDOW) {
        int windowBp = effective.getWindowBp();
        Map<String, List<Double>> perWindow = new HashMap<>();
        Map<String, Region> windowMeta = new HashMap<>();
        for (int i = 0; i < n; i++) {
          double ratio = row.smoothedMethylRatio[i];
          if (ratio < 0 || Double.isNaN(ratio)) {
            continue;
          }
          long pos = start + (span * i) / n;
          long wStart = start + ((pos - start) / windowBp) * windowBp;
          long wEnd = Math.min(end, wStart + windowBp - 1);
          Region region = new Region(chromKey, wStart, wEnd);
          String id = SignalScopeHelper.windowUnitId(region);
          perWindow.computeIfAbsent(id, k -> new ArrayList<>()).add(ratio);
          windowMeta.putIfAbsent(id, region);
        }
        if (perWindow.isEmpty()) {
          skipped++;
          continue;
        }
        used++;
        for (Map.Entry<String, List<Double>> e : perWindow.entrySet()) {
          double collapsed = FeatureValueMatrix.collapse(e.getValue(), effective.getAggregation());
          if (Double.isNaN(collapsed)) {
            continue;
          }
          matrix.put(e.getKey(), track, collapsed);
          Region region = windowMeta.get(e.getKey());
          if (region != null) {
            matrix.putLocus(e.getKey(), region.chrom(), region.start1Based(), region.end1Based());
          }
        }
      } else {
        Map<String, List<Double>> perGene = new HashMap<>();
        Map<String, Gene> geneMeta = new HashMap<>();
        for (int i = 0; i < n; i++) {
          double ratio = row.smoothedMethylRatio[i];
          if (ratio < 0 || Double.isNaN(ratio)) {
            continue;
          }
          long pos = start + (span * i) / n;
          Gene gene = GeneOverlapIndex.bestGeneAt(chromKey, pos);
          if (gene == null || gene.name() == null) {
            continue;
          }
          perGene.computeIfAbsent(gene.name(), k -> new ArrayList<>()).add(ratio);
          geneMeta.putIfAbsent(gene.name(), gene);
        }
        if (perGene.isEmpty()) {
          skipped++;
          continue;
        }
        used++;
        for (Map.Entry<String, List<Double>> e : perGene.entrySet()) {
          double collapsed = FeatureValueMatrix.collapse(e.getValue(), effective.getAggregation());
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
    }
    return String.format(
        Locale.ROOT,
        "%s %s:%d-%d — %d samples used, %d skipped",
        label(),
        chromKey,
        start,
        end,
        used,
        skipped);
  }

  public static AlignmentFile firstMethylBam(SampleTrack track) {
    if (track == null) {
      return null;
    }
    for (Sample sample : track.getSamples()) {
      if (sample != null && sample.isMethylationData() && sample.getBamFile() != null) {
        return sample.getBamFile();
      }
    }
    return null;
  }

  public static boolean trackHasMethylBam(SampleTrack track) {
    return firstMethylBam(track) != null;
  }
}

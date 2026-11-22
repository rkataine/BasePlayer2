package org.baseplayer.analysis.source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.baseplayer.analysis.CollectContext;
import org.baseplayer.analysis.ComparisonUnit;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.GeneOverlapIndex;
import org.baseplayer.analysis.GenomicScope;
import org.baseplayer.analysis.signal.SignalScopeHelper;
import org.baseplayer.analysis.signal.SignalScopeHelper.Region;
import org.baseplayer.analysis.signal.SignalSourceSpec;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.io.table.MappedTableData;
import org.baseplayer.io.table.MappedTableRow;
import org.baseplayer.io.table.SharedMatrixLoci;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;

/** Gene scores from mapped tables that expose a Value/Score column. */
public final class MappedTableValueSource implements ValueSource {

  @Override
  public String label() {
    return "Mapped table values";
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
    if (effective.getUnit() != ComparisonUnit.GENE) {
      return label() + ": only gene units are supported currently";
    }
    int used = 0;
    int skipped = 0;
    StringBuilder notes = new StringBuilder();
    GenomicScope scope = effective.getScope();
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
      MappedTableData table = firstValueTable(track);
      if (table == null) {
        skipped++;
        continue;
      }
      if (table.hasUnitIds() && table.sharedLoci() != null) {
        int n = collectFromUnitIds(table, track, matrix, scope, ctx);
        if (n <= 0) {
          skipped++;
        } else {
          used++;
          appendNote(notes, track.getDisplayName(), n + " genes");
        }
        continue;
      }
      Map<String, List<Double>> perGene = new HashMap<>();
      Map<String, Gene> geneMeta = new HashMap<>();
      List<MappedTableRow> rows = table.getRows();
      for (int i = 0; i < rows.size(); i++) {
        if (ctx != null && (i & 0x3FF) == 0) {
          ctx.checkCancelled();
        }
        MappedTableRow row = rows.get(i);
        if (!inScope(row, scope)) {
          continue;
        }
        double value = row.analysisValue();
        if (!Double.isFinite(value)) {
          continue;
        }
        List<String> geneNames = GeneOverlapIndex.overlappingGeneNames(
            row.chromosome(), row.start1Based(), row.end1Based());
        if (geneNames.isEmpty()) {
          Gene best = GeneOverlapIndex.bestGeneOverlapping(
              row.chromosome(), row.start1Based(), row.end1Based());
          if (best != null && best.name() != null && !best.name().isBlank()) {
            geneNames = List.of(best.name());
            geneMeta.putIfAbsent(best.name(), best);
          }
        }
        for (String geneName : geneNames) {
          Gene gene = geneMeta.get(geneName);
          if (gene == null) {
            gene = GeneOverlapIndex.resolveByName(geneName);
            if (gene != null) {
              geneMeta.put(geneName, gene);
            }
          }
          perGene.computeIfAbsent(geneName, k -> new ArrayList<>()).add(value);
        }
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
      appendNote(notes, track.getDisplayName(), perGene.size() + " genes");
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

  public static MappedTableData firstValueTable(SampleTrack track) {
    if (track == null) {
      return null;
    }
    for (Sample sample : track.getSamples()) {
      if (sample != null
          && sample.getDataType() == Sample.DataType.TABLE
          && sample.getMappedTable() != null
          && sample.getMappedTable().hasValueColumn()) {
        return sample.getMappedTable();
      }
    }
    return null;
  }

  public static boolean trackHasValueTable(SampleTrack track) {
    return firstValueTable(track) != null;
  }

  /**
   * Gene matrix: unit ids already identify genes — put values + loci directly
   * without interval re-annotation. Prefer gene symbol when ENSG/id resolves.
   */
  private static int collectFromUnitIds(
      MappedTableData table,
      SampleTrack track,
      FeatureValueMatrix matrix,
      GenomicScope scope,
      CollectContext ctx) {
    SharedMatrixLoci loci = table.sharedLoci();
    float[] values = table.compactValues();
    if (loci == null || values == null) {
      return 0;
    }
    int n = 0;
    for (int i = 0; i < loci.size(); i++) {
      if (ctx != null && (i & 0x3FF) == 0) {
        ctx.checkCancelled();
      }
      float v = values[i];
      if (!Float.isFinite(v)) {
        continue;
      }
      String unit = loci.unitId(i);
      if (unit == null || unit.isBlank()) {
        continue;
      }
      String chrom = loci.chrom(i);
      long start = loci.start1Based(i);
      long end = loci.end1Based(i);
      if (!inScope(chrom, start, end, scope)) {
        continue;
      }
      // unitId already prefers gene symbol (resolved at matrix open).
      matrix.put(unit, track, v);
      matrix.putLocus(unit, chrom, start, end);
      n++;
    }
    return n;
  }

  private static boolean inScope(MappedTableRow row, GenomicScope scope) {
    return inScope(row.chromosome(), row.start1Based(), row.end1Based(), scope);
  }

  private static boolean inScope(
      String chrom, long start1Based, long end1Based, GenomicScope scope) {
    if (scope == null || scope == GenomicScope.ALL_CHROMS) {
      return true;
    }
    Region view = SignalScopeHelper.currentView();
    if (view == null) {
      return scope != GenomicScope.CURRENT_VIEW;
    }
    if (chrom == null) {
      return false;
    }
    if (!chrom.equals(view.chrom())
        && !chrom.equals("chr" + view.chrom())
        && !(("chr" + chrom).equals(view.chrom()))) {
      return false;
    }
    if (scope == GenomicScope.CURRENT_CHROM) {
      return true;
    }
    return end1Based >= view.start1Based() && start1Based <= view.end1Based();
  }

  private static void appendNote(StringBuilder notes, String name, String detail) {
    if (notes.length() > 0) {
      notes.append("; ");
    }
    notes.append(name).append('=').append(detail);
  }
}

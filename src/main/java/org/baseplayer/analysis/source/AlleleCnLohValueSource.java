package org.baseplayer.analysis.source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.baseplayer.analysis.CollectContext;
import org.baseplayer.analysis.ComparisonUnit;
import org.baseplayer.analysis.FeatureValueMatrix;
import org.baseplayer.analysis.GeneOverlapIndex;
import org.baseplayer.analysis.GenomicScope;
import org.baseplayer.analysis.SignalScopeHelper;
import org.baseplayer.analysis.SignalScopeHelper.Region;
import org.baseplayer.analysis.SignalSourceSpec;
import org.baseplayer.analysis.allelecn.AlleleCnSegment;
import org.baseplayer.analysis.allelecn.LohFromAlleleCn;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.io.table.MappedTableData;
import org.baseplayer.io.table.MappedTableRow;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;

/**
 * Maps allele-specific CN columns from opened tables → gene×sample LOH scores.
 */
public final class AlleleCnLohValueSource implements ValueSource {

  private final LohFromAlleleCn policy;

  public AlleleCnLohValueSource() {
    this(LohFromAlleleCn.binary());
  }

  public AlleleCnLohValueSource(LohFromAlleleCn policy) {
    this.policy = policy != null ? policy : LohFromAlleleCn.binary();
  }

  @Override
  public String label() {
    return "Table allele-CN LOH";
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
    if (unit != ComparisonUnit.GENE) {
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
      MappedTableData table = firstAlleleCnTable(track);
      if (table == null) {
        skipped++;
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
        if (!row.hasAlleleCn() || !inScope(row, scope)) {
          continue;
        }
        AlleleCnSegment seg = toSegment(row);
        double score = policy.score(seg);
        if (Double.isNaN(score)) {
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
          perGene.computeIfAbsent(geneName, k -> new ArrayList<>()).add(score);
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
          collapsed = e.getValue().stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
        }
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
    if (matrix.getMissingDefault() == null) {
      matrix.setMissingDefault(0.0);
    }
    return String.format(
        Locale.ROOT,
        "%s (%s): %d samples used, %d skipped%s%s",
        label(),
        policy.scoreMode().name().toLowerCase(Locale.ROOT),
        used,
        skipped,
        notes.length() > 0 ? " — " : "",
        notes);
  }

  public static MappedTableData firstAlleleCnTable(SampleTrack track) {
    if (track == null) {
      return null;
    }
    for (Sample sample : track.getSamples()) {
      if (sample != null
          && sample.getDataType() == Sample.DataType.TABLE
          && sample.getMappedTable() != null
          && sample.getMappedTable().hasAlleleCn()) {
        return sample.getMappedTable();
      }
    }
    return null;
  }

  public static boolean trackHasAlleleCn(SampleTrack track) {
    return firstAlleleCnTable(track) != null;
  }

  private static AlleleCnSegment toSegment(MappedTableRow row) {
    return new AlleleCnSegment(
        row.chromosome(),
        row.start1Based(),
        row.end1Based(),
        row.sampleId(),
        row.minorCn(),
        row.majorCn(),
        row.totalCn(),
        row.score());
  }

  private static boolean inScope(MappedTableRow row, GenomicScope scope) {
    if (scope == null || scope == GenomicScope.ALL_CHROMS) {
      return true;
    }
    Region view = SignalScopeHelper.currentView();
    if (view == null) {
      return scope != GenomicScope.CURRENT_VIEW;
    }
    String chrom = row.chromosome();
    if (!chrom.equals(view.chrom())
        && !chrom.equals("chr" + view.chrom())
        && !(("chr" + chrom).equals(view.chrom()))) {
      return false;
    }
    if (scope == GenomicScope.CURRENT_CHROM) {
      return true;
    }
    return row.end1Based() >= view.start1Based() && row.start1Based() <= view.end1Based();
  }

  private static void appendNote(StringBuilder notes, String name, String detail) {
    if (notes.length() > 0) {
      notes.append("; ");
    }
    notes.append(name).append('=').append(detail);
  }
}

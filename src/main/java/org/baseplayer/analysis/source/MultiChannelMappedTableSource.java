package org.baseplayer.analysis.source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.baseplayer.analysis.CollectContext;
import org.baseplayer.analysis.ComparisonUnit;
import org.baseplayer.analysis.grid.FeatureValueBundle;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.GeneOverlapIndex;
import org.baseplayer.analysis.GenomicScope;
import org.baseplayer.analysis.signal.SignalChannel;
import org.baseplayer.analysis.signal.SignalScopeHelper;
import org.baseplayer.analysis.signal.SignalScopeHelper.Region;
import org.baseplayer.analysis.signal.SignalSourceSpec;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.io.table.MappedTableColumnRole;
import org.baseplayer.io.table.MappedTableData;
import org.baseplayer.io.table.MappedTableRow;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;

/**
 * Collects raw per-channel values from mapped tables (Minor/Major/Value/Score)
 * into a {@link FeatureValueBundle}.
 */
public final class MultiChannelMappedTableSource implements ValueSource {

  @Override
  public String label() {
    return "Mapped table channels";
  }

  @Override
  public String collect(List<SampleTrack> tracks, FeatureValueMatrix matrix, SignalSourceSpec spec) {
    return collect(tracks, matrix, spec, null);
  }

  /**
   * Fills {@code bundle} with raw channel values. Also mirrors the first channel into
   * {@code matrix} when non-null (group-comparison compat).
   */
  public String collectBundle(
      List<SampleTrack> tracks,
      FeatureValueBundle bundle,
      FeatureValueMatrix mirrorPrimary,
      SignalSourceSpec spec,
      CollectContext ctx) {
    if (tracks == null || bundle == null || bundle.channels().isEmpty()) {
      return "No channels";
    }
    SignalSourceSpec effective = spec != null ? spec : new SignalSourceSpec();
    if (effective.getUnit() != ComparisonUnit.GENE) {
      return label() + ": only gene units are supported currently";
    }
    List<SignalChannel> channels = bundle.channels();
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
      MappedTableData table = firstPayloadTable(track);
      if (table == null) {
        skipped++;
        continue;
      }
      Map<String, Map<String, List<Double>>> perChannelGene = new HashMap<>();
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
        List<String> geneNames = overlappingGenes(row, geneMeta);
        if (geneNames.isEmpty()) {
          continue;
        }
        for (SignalChannel ch : channels) {
          double value = valueFor(row, ch.sourceRole());
          if (!Double.isFinite(value)) {
            continue;
          }
          for (String geneName : geneNames) {
            perChannelGene
                .computeIfAbsent(ch.id(), k -> new HashMap<>())
                .computeIfAbsent(geneName, k -> new ArrayList<>())
                .add(value);
          }
        }
      }
      if (perChannelGene.isEmpty()) {
        skipped++;
        continue;
      }
      used++;
      int geneCount = 0;
      for (SignalChannel ch : channels) {
        Map<String, List<Double>> perGene = perChannelGene.get(ch.id());
        if (perGene == null) {
          continue;
        }
        geneCount = Math.max(geneCount, perGene.size());
        for (Map.Entry<String, List<Double>> e : perGene.entrySet()) {
          double collapsed = FeatureValueMatrix.collapse(e.getValue(), effective.getAggregation());
          if (Double.isNaN(collapsed)) {
            continue;
          }
          collapsed = ch.type().normalize(collapsed);
          // Skip threshold-fail cells (e.g. diploid Allele CN 1) — keeps the matrix sparse.
          if (ch.thresholds().isEnabled() && !ch.thresholds().passes(collapsed)) {
            continue;
          }
          bundle.put(ch.id(), e.getKey(), track, collapsed);
          if (mirrorPrimary != null && ch.id().equals(channels.get(0).id())) {
            mirrorPrimary.put(e.getKey(), track, collapsed);
          }
          Gene gene = geneMeta.get(e.getKey());
          if (gene != null) {
            bundle.putLocus(e.getKey(), gene.chrom(), gene.start(), gene.end());
            if (mirrorPrimary != null) {
              mirrorPrimary.putLocus(e.getKey(), gene.chrom(), gene.start(), gene.end());
            }
          }
        }
      }
      appendNote(notes, track.getDisplayName(), geneCount + " genes");
    }
    return String.format(
        Locale.ROOT,
        "%s (%d ch): %d samples used, %d skipped%s%s",
        label(),
        channels.size(),
        used,
        skipped,
        notes.length() > 0 ? " — " : "",
        notes);
  }

  @Override
  public String collect(
      List<SampleTrack> tracks,
      FeatureValueMatrix matrix,
      SignalSourceSpec spec,
      CollectContext ctx) {
    List<SignalChannel> channels = spec != null ? spec.getChannels() : List.of();
    if (channels.isEmpty()) {
      return "No channels configured";
    }
    FeatureValueBundle bundle = new FeatureValueBundle(channels);
    String notes = collectBundle(tracks, bundle, matrix, spec, ctx);
    // Stash for annotate path via primary matrix loci already mirrored.
    return notes;
  }

  public static MappedTableData firstPayloadTable(SampleTrack track) {
    if (track == null) {
      return null;
    }
    for (Sample sample : track.getSamples()) {
      if (sample != null
          && sample.getDataType() == Sample.DataType.TABLE
          && sample.getMappedTable() != null
          && sample.getMappedTable().getMapping() != null
          && sample.getMappedTable().getMapping().hasPayload()) {
        return sample.getMappedTable();
      }
    }
    return null;
  }

  public static boolean trackHasPayload(SampleTrack track) {
    return firstPayloadTable(track) != null;
  }

  private static double valueFor(MappedTableRow row, MappedTableColumnRole role) {
    if (row == null || role == null) {
      return Double.NaN;
    }
    return switch (role) {
      case MINOR_CN -> row.minorCn() != null ? row.minorCn() : Double.NaN;
      case MAJOR_CN -> row.majorCn() != null ? row.majorCn() : Double.NaN;
      case VALUE -> row.value() != null ? row.value() : Double.NaN;
      case SCORE -> row.score() != null ? row.score() : Double.NaN;
      case TOTAL_CN -> row.totalCn() != null ? row.totalCn() : Double.NaN;
      default -> Double.NaN;
    };
  }

  private static List<String> overlappingGenes(MappedTableRow row, Map<String, Gene> geneMeta) {
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
      if (!geneMeta.containsKey(geneName)) {
        Gene gene = GeneOverlapIndex.resolveByName(geneName);
        if (gene != null) {
          geneMeta.put(geneName, gene);
        }
      }
    }
    return geneNames;
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

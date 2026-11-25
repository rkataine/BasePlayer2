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
import org.baseplayer.analysis.signal.SignalEventDirection;
import org.baseplayer.analysis.signal.SignalValueType;
import org.baseplayer.analysis.signal.SignalScopeHelper;
import org.baseplayer.analysis.signal.SignalScopeHelper.Region;
import org.baseplayer.analysis.signal.SignalSourceSpec;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.io.table.MappedTableColumnRole;
import org.baseplayer.io.table.MappedTableData;
import org.baseplayer.io.table.MappedTableRow;
import org.baseplayer.io.table.SharedMatrixLoci;
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
      int geneCount;
      if (table.hasUnitIds() && table.sharedLoci() != null) {
        geneCount = collectCompactChannels(
            table, track, bundle, mirrorPrimary, channels, scope, ctx);
      } else {
        geneCount = collectClassicRows(
            table, track, bundle, mirrorPrimary, channels, scope, effective, ctx);
      }
      if (geneCount <= 0) {
        skipped++;
        continue;
      }
      used++;
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

  /**
   * Gene-matrix / Salmon path: unit ids already on shared loci — no row materialization.
   * @return number of genes written for this track
   */
  private static int collectCompactChannels(
      MappedTableData table,
      SampleTrack track,
      FeatureValueBundle bundle,
      FeatureValueMatrix mirrorPrimary,
      List<SignalChannel> channels,
      GenomicScope scope,
      CollectContext ctx) {
    SharedMatrixLoci loci = table.sharedLoci();
    if (loci == null || channels == null || channels.isEmpty()) {
      return 0;
    }
    java.util.LinkedHashSet<String> written = new java.util.LinkedHashSet<>();
    for (int i = 0; i < loci.size(); i++) {
      if (ctx != null && (i & 0x3FF) == 0) {
        ctx.checkCancelled();
      }
      String unitId = loci.unitId(i);
      if (unitId == null || unitId.isBlank()) {
        continue;
      }
      if (!inScopeLocus(loci.chrom(i), loci.start1Based(i), loci.end1Based(i), scope)) {
        continue;
      }
      boolean any = false;
      for (SignalChannel ch : channels) {
        float raw = table.compactValue(i, ch.sourceRole());
        if (!Float.isFinite(raw)) {
          continue;
        }
        double value = ch.type().normalize(raw);
        if (ch.thresholds().isEnabled() && !ch.thresholds().passes(value)) {
          continue;
        }
        bundle.put(ch.id(), unitId, track, value);
        if (mirrorPrimary != null && ch.id().equals(channels.get(0).id())) {
          mirrorPrimary.put(unitId, track, value);
        }
        bundle.putLocus(unitId, loci.chrom(i), loci.start1Based(i), loci.end1Based(i));
        if (mirrorPrimary != null) {
          mirrorPrimary.putLocus(unitId, loci.chrom(i), loci.start1Based(i), loci.end1Based(i));
        }
        any = true;
      }
      if (any) {
        written.add(unitId);
      }
    }
    return written.size();
  }

  /** Classic segment/table rows (Minor/Major/Value). */
  private static int collectClassicRows(
      MappedTableData table,
      SampleTrack track,
      FeatureValueBundle bundle,
      FeatureValueMatrix mirrorPrimary,
      List<SignalChannel> channels,
      GenomicScope scope,
      SignalSourceSpec effective,
      CollectContext ctx) {
    if (channels.stream().allMatch(ch -> ch != null && ch.type() == SignalValueType.ALLELE_CN)) {
      return collectLongestAlleleCnRows(
          table, track, bundle, mirrorPrimary, channels, scope, ctx);
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
      return 0;
    }
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
    return geneCount;
  }

  /**
   * Allele-CN segments overlapping one gene: keep minor/major from the longest overlap
   * so the stored copy numbers are values from the file, not a mean of segments.
   */
  private static int collectLongestAlleleCnRows(
      MappedTableData table,
      SampleTrack track,
      FeatureValueBundle bundle,
      FeatureValueMatrix mirrorPrimary,
      List<SignalChannel> channels,
      GenomicScope scope,
      CollectContext ctx) {
    Map<String, BestRow> best = new HashMap<>();
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
      for (String geneName : geneNames) {
        long span = GeneOverlapIndex.overlapBp(
            row.start1Based(), row.end1Based(), geneMeta.get(geneName));
        BestRow prev = best.get(geneName);
        if (prev == null || span > prev.span()) {
          best.put(geneName, new BestRow(span, row));
        }
      }
    }
    if (best.isEmpty()) {
      return 0;
    }
    int geneCount = 0;
    for (Map.Entry<String, BestRow> e : best.entrySet()) {
      MappedTableRow row = e.getValue().row();
      // Diploid / no-event segments stay out of the matrix; type filters pick Gain/Loss/LOH.
      double minorRaw = valueFor(row, MappedTableColumnRole.MINOR_CN);
      double majorRaw = valueFor(row, MappedTableColumnRole.MAJOR_CN);
      Double minor = Double.isFinite(minorRaw) ? Double.valueOf(minorRaw) : null;
      Double major = Double.isFinite(majorRaw) ? Double.valueOf(majorRaw) : null;
      if (SignalEventDirection.fromAlleleCn(minor, major) == SignalEventDirection.NONE) {
        continue;
      }
      boolean wrote = false;
      for (SignalChannel ch : channels) {
        double value = valueFor(row, ch.sourceRole());
        if (!Double.isFinite(value)) {
          continue;
        }
        value = ch.type().normalize(value);
        if (ch.thresholds().isEnabled() && !ch.thresholds().passes(value)) {
          continue;
        }
        bundle.put(ch.id(), e.getKey(), track, value);
        if (mirrorPrimary != null && ch.id().equals(channels.get(0).id())) {
          mirrorPrimary.put(e.getKey(), track, value);
        }
        wrote = true;
      }
      if (!wrote) {
        continue;
      }
      geneCount++;
      Gene gene = geneMeta.get(e.getKey());
      if (gene != null) {
        bundle.putLocus(e.getKey(), gene.chrom(), gene.start(), gene.end());
        if (mirrorPrimary != null) {
          mirrorPrimary.putLocus(e.getKey(), gene.chrom(), gene.start(), gene.end());
        }
      }
    }
    return geneCount;
  }

  private record BestRow(long span, MappedTableRow row) {}

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

  private static boolean inScopeLocus(
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

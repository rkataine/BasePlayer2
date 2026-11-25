package org.baseplayer.analysis.signal;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.baseplayer.features.BigWigTrack;
import org.baseplayer.io.table.MappedTableData;
import org.baseplayer.io.table.SalmonQuantOpen;
import org.baseplayer.io.table.SharedMatrixLoci;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;

/**
 * Maps known signal filetypes / kinds to Analysis tab names.
 * Empty {@link Optional} means the UI should prompt the user.
 */
public final class SignalDatasetNaming {

  private SignalDatasetNaming() {}

  /**
   * Suggest a tab name from open paths, inferred kind, and optional table channels.
   * Returns empty when the type is not recognized and the user should name it.
   */
  public static Optional<String> suggest(
      List<Path> paths,
      SignalValueKind kind,
      List<SignalChannel> channels) {
    if (paths != null) {
      for (Path path : paths) {
        Optional<String> fromPath = suggestFromPath(path);
        if (fromPath.isPresent()) {
          return fromPath;
        }
      }
    }
    if (channels != null && !channels.isEmpty()) {
      boolean allExpression = true;
      boolean allAlleleCn = true;
      boolean allMethyl = true;
      for (SignalChannel ch : channels) {
        if (ch == null) {
          continue;
        }
        if (ch.type() != SignalValueType.EXPRESSION) {
          allExpression = false;
        }
        if (ch.type() != SignalValueType.ALLELE_CN) {
          allAlleleCn = false;
        }
        if (ch.type() != SignalValueType.METHYLATION) {
          allMethyl = false;
        }
      }
      if (allExpression) {
        return Optional.of("RNA");
      }
      if (allAlleleCn) {
        return Optional.of("Allele CN");
      }
      if (kind == SignalValueKind.TABLE_LOH) {
        return Optional.of("LOH");
      }
      if (allMethyl || kind == SignalValueKind.BAM_METHYL) {
        return Optional.of("Methylation");
      }
    }
    if (kind == SignalValueKind.TABLE_LOH) {
      return Optional.of("LOH");
    }
    if (kind == SignalValueKind.BAM_METHYL) {
      return Optional.of("Methylation");
    }
    if (kind == SignalValueKind.BED_SCORES) {
      return Optional.of("BED");
    }
    if (kind == SignalValueKind.BED_ANY) {
      return Optional.of("Features");
    }
    // Generic TABLE_VALUES / BIGWIG without path heuristics → prompt.
    return Optional.empty();
  }

  public static Optional<String> suggestFromPath(Path path) {
    if (path == null) {
      return Optional.empty();
    }
    if (SalmonQuantOpen.isSalmonQuantPath(path)) {
      return Optional.of("RNA");
    }
    String name = path.getFileName() != null
        ? path.getFileName().toString().toLowerCase(Locale.ROOT)
        : "";
    if (name.contains(".ascat") || name.contains(".allelecn")
        || name.contains(".segments") || name.endsWith(".cn.tsv")
        || name.endsWith(".cn.txt") || name.endsWith(".cn.csv")) {
      return Optional.of("Allele CN");
    }
    if (BigWigTrack.looksLikeSegFile(path)) {
      return Optional.of("Copy ratio");
    }
    if (BigWigTrack.looksLikeBafFile(path)) {
      return Optional.of("BAF");
    }
    if (name.endsWith(".bed") || name.endsWith(".bed.gz")) {
      // Scored vs unscored decided by kind; path alone → BED.
      return Optional.of("BED");
    }
    return Optional.empty();
  }

  /** Infer kind from member tracks (same priority as legacy resolveSpec, but scoped). */
  public static SignalValueKind inferKind(List<SampleTrack> tracks) {
    if (tracks == null || tracks.isEmpty()) {
      return SignalValueKind.TABLE_VALUES;
    }
    List<SignalChannel> channels = channelsFromTracks(tracks);
    boolean alleleCn = tracks.stream().anyMatch(
        org.baseplayer.analysis.source.AlleleCnLohValueSource::trackHasAlleleCn);
    // Allele-CN Minor+Major → multi-channel TABLE_VALUES (Gain/Loss/LOH + type filters).
    // Channel-less allele-CN falls back to legacy TABLE_LOH gene-score path.
    if (alleleCn && SignalChannels.isAlleleCnOnly(channels)) {
      return SignalValueKind.TABLE_VALUES;
    }
    if (!channels.isEmpty()) {
      return SignalValueKind.TABLE_VALUES;
    }
    if (alleleCn) {
      return SignalValueKind.TABLE_LOH;
    }
    if (tracks.stream().anyMatch(
        org.baseplayer.analysis.source.MappedTableValueSource::trackHasValueTable)) {
      return SignalValueKind.TABLE_VALUES;
    }
    if (tracks.stream().anyMatch(
        org.baseplayer.analysis.source.BedScoreSource::trackHasScoredBed)) {
      return SignalValueKind.BED_SCORES;
    }
    if (tracks.stream().anyMatch(
        t -> org.baseplayer.analysis.source.BedScoreSource.firstBed(t) != null)) {
      return SignalValueKind.BED_ANY;
    }
    if (tracks.stream().anyMatch(
        org.baseplayer.analysis.source.BigWigValueSource::trackHasBigWig)) {
      return SignalValueKind.BIGWIG;
    }
    if (tracks.stream().anyMatch(
        org.baseplayer.analysis.source.BamMethylSource::trackHasMethylBam)) {
      return SignalValueKind.BAM_METHYL;
    }
    return SignalValueKind.TABLE_VALUES;
  }

  public static List<SignalChannel> channelsFromTracks(List<SampleTrack> tracks) {
    if (tracks == null) {
      return List.of();
    }
    for (SampleTrack track : tracks) {
      MappedTableData table =
          org.baseplayer.analysis.source.MultiChannelMappedTableSource.firstPayloadTable(track);
      if (table != null) {
        List<SignalChannel> derived = SignalChannels.fromTable(table);
        if (!derived.isEmpty()) {
          return derived;
        }
      }
    }
    return List.of();
  }

  /** Shared loci from the first interval-bar table among tracks. */
  public static SharedMatrixLoci sharedLociFromTracks(List<SampleTrack> tracks) {
    if (tracks == null) {
      return null;
    }
    for (SampleTrack track : tracks) {
      if (track == null) {
        continue;
      }
      for (Sample sample : track.getSamples()) {
        if (sample == null || sample.getMappedTable() == null) {
          continue;
        }
        MappedTableData table = sample.getMappedTable();
        if (table.isIntervalBars() && table.sharedLoci() != null) {
          return table.sharedLoci();
        }
      }
    }
    return null;
  }
}

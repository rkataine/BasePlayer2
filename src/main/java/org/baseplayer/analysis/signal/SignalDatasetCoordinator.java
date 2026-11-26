package org.baseplayer.analysis.signal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.baseplayer.MainApp;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.source.AlleleCnLohValueSource;
import org.baseplayer.analysis.source.BamMethylSource;
import org.baseplayer.analysis.source.BedScoreSource;
import org.baseplayer.analysis.source.BigWigValueSource;
import org.baseplayer.analysis.source.MappedTableValueSource;
import org.baseplayer.analysis.source.MultiChannelMappedTableSource;
import org.baseplayer.components.AppDialog;
import org.baseplayer.io.table.SharedMatrixLoci;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

import javafx.stage.Window;

/**
 * Creates or appends {@link SignalDataset}s after signal files are opened,
 * then focuses the matching Analysis tab.
 */
public final class SignalDatasetCoordinator {

  private SignalDatasetCoordinator() {}

  /**
   * Register opened tracks as a named Signal dataset (or append to a compatible one).
   * Prompts for a name when the filetype is unrecognized.
   *
   * @return the dataset id, or empty if the user cancelled the name prompt
   */
  public static Optional<String> registerOpened(
      List<SampleTrack> tracks,
      List<Path> paths,
      SignalValueKind kindHint) {
    return registerOpened(tracks, paths, kindHint, true);
  }

  /**
   * Like {@link #registerOpened(List, List, SignalValueKind)} but never prompts —
   * uses a kind fallback name when the filetype is unrecognized (project restore).
   */
  public static Optional<String> registerOpenedSilent(
      List<SampleTrack> tracks,
      List<Path> paths,
      SignalValueKind kindHint) {
    return registerOpened(tracks, paths, kindHint, false);
  }

  private static Optional<String> registerOpened(
      List<SampleTrack> tracks,
      List<Path> paths,
      SignalValueKind kindHint,
      boolean promptIfUnknown) {
    if (tracks == null || tracks.isEmpty()) {
      return Optional.empty();
    }
    List<SampleTrack> members = new ArrayList<>();
    for (SampleTrack track : tracks) {
      if (track != null) {
        members.add(track);
      }
    }
    if (members.isEmpty()) {
      return Optional.empty();
    }

    SignalValueKind kind = kindHint != null
        ? kindHint
        : SignalDatasetNaming.inferKind(members);
    List<SignalChannel> channels = SignalDatasetNaming.channelsFromTracks(members);
    SharedMatrixLoci loci = SignalDatasetNaming.sharedLociFromTracks(members);

    Optional<String> suggested = SignalDatasetNaming.suggest(paths, kind, channels);
    String displayName;
    if (suggested.isPresent()) {
      displayName = suggested.get();
    } else if (promptIfUnknown) {
      Window owner = MainApp.stage;
      Optional<String> named = AppDialog.prompt(
          owner,
          "Name this data",
          "Name this signal dataset",
          "This file type is not recognized. Choose a name for the Analysis tab.",
          "Signal");
      if (named.isEmpty()) {
        return Optional.empty();
      }
      displayName = named.get();
    } else {
      displayName = fallbackName(kind);
    }

    SignalDatasetRegistry registry = SignalDatasetRegistry.get();
    SignalDataset existing = registry.findCompatible(displayName, kind, loci);
    if (existing != null) {
      existing.addTracks(members);
      if (paths != null) {
        for (Path path : paths) {
          existing.addSourcePath(path);
        }
      }
      if (loci != null && existing.getSharedLoci() == null) {
        existing.setSharedLoci(loci);
      }
      AnalysisSession.get().ensureSignalSlice(existing.getId());
      AnalysisSession.get().setActiveSignalDatasetId(existing.getId());
      return Optional.of(existing.getId());
    }

    String unique = registry.uniqueDisplayName(displayName);
    SignalDataset created = new SignalDataset(unique, kind);
    created.addTracks(members);
    created.setSharedLoci(loci);
    if (paths != null) {
      for (Path path : paths) {
        created.addSourcePath(path);
      }
    }
    registry.register(created);
    AnalysisSession.get().ensureSignalSlice(created.getId());
    AnalysisSession.get().setActiveSignalDatasetId(created.getId());
    return Optional.of(created.getId());
  }

  /**
   * After project restore (or whenever Signal tabs are missing), create named
   * datasets for every open comparable track that is not yet a member.
   * Never prompts — uses filetype map / kind fallbacks.
   */
  public static void ensureDatasetsForOpenTracks() {
    SampleRegistry sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    if (sampleRegistry == null) {
      return;
    }
    SignalDatasetRegistry datasets = SignalDatasetRegistry.get();
    List<SampleTrack> orphans = new ArrayList<>();
    for (SampleTrack track : sampleRegistry.snapshotSampleTracks()) {
      if (track == null || datasets.findByTrack(track) != null) {
        continue;
      }
      if (isComparableSignalTrack(track)) {
        orphans.add(track);
      }
    }
    if (orphans.isEmpty()) {
      return;
    }

    // Group by kind, then by shared loci for gene matrices.
    Map<SignalValueKind, List<SampleTrack>> byKind = new LinkedHashMap<>();
    for (SampleTrack track : orphans) {
      SignalValueKind kind = SignalDatasetNaming.inferKind(List.of(track));
      byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(track);
    }
    for (Map.Entry<SignalValueKind, List<SampleTrack>> entry : byKind.entrySet()) {
      SignalValueKind kind = entry.getKey();
      List<SampleTrack> kindTracks = entry.getValue();
      if (kind == SignalValueKind.TABLE_VALUES || kind == SignalValueKind.TABLE_LOH) {
        Map<SharedMatrixLoci, List<SampleTrack>> byLoci = new IdentityHashMap<>();
        List<SampleTrack> noLoci = new ArrayList<>();
        for (SampleTrack track : kindTracks) {
          SharedMatrixLoci loci = SignalDatasetNaming.sharedLociFromTracks(List.of(track));
          if (loci != null) {
            byLoci.computeIfAbsent(loci, k -> new ArrayList<>()).add(track);
          } else {
            noLoci.add(track);
          }
        }
        for (List<SampleTrack> group : byLoci.values()) {
          registerOpenedSilent(group, List.of(), kind);
        }
        if (!noLoci.isEmpty()) {
          registerOpenedSilent(noLoci, List.of(), kind);
        }
      } else {
        registerOpenedSilent(kindTracks, List.of(), kind);
      }
    }
  }

  private static boolean isComparableSignalTrack(SampleTrack track) {
    return BedScoreSource.trackHasScoredBed(track)
        || BigWigValueSource.trackHasBigWig(track)
        || BamMethylSource.trackHasMethylBam(track)
        || BedScoreSource.firstBed(track) != null
        || MappedTableValueSource.trackHasValueTable(track)
        || MultiChannelMappedTableSource.trackHasPayload(track)
        || AlleleCnLohValueSource.trackHasAlleleCn(track);
  }

  private static String fallbackName(SignalValueKind kind) {
    if (kind == null) {
      return "Signal";
    }
    return switch (kind) {
      case TABLE_LOH -> "LOH";
      case BAM_METHYL -> "Methylation";
      case BED_SCORES -> "BED";
      case BED_ANY -> "Features";
      case TABLE_VALUES -> "RNA";
      case BIGWIG -> "Signal";
    };
  }

  /** Open Analysis and select the dataset tab when present. */
  public static void openAnalysisForDataset(String datasetId) {
    if (datasetId == null || datasetId.isBlank()) {
      org.baseplayer.analysis.manager.AnalysisManagerFacade.openForSignalAnalysis();
      return;
    }
    org.baseplayer.analysis.manager.AnalysisManagerFacade.openForSignalDataset(datasetId);
  }
}

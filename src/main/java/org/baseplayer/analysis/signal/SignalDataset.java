package org.baseplayer.analysis.signal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.baseplayer.io.table.SharedMatrixLoci;
import org.baseplayer.samples.SampleTrack;

/**
 * One named Signal analysis unit: frozen kind, member tracks, optional shared loci.
 * Each dataset owns its own Analysis tab / workspace / session slice.
 */
public final class SignalDataset {

  private final String id;
  private String displayName;
  private final SignalValueKind kind;
  private final Set<SampleTrack> tracks = Collections.newSetFromMap(new IdentityHashMap<>());
  private SharedMatrixLoci sharedLoci;
  private final List<Path> sourcePaths = new ArrayList<>();

  public SignalDataset(String displayName, SignalValueKind kind) {
    this(UUID.randomUUID().toString(), displayName, kind);
  }

  public SignalDataset(String id, String displayName, SignalValueKind kind) {
    this.id = id != null && !id.isBlank() ? id : UUID.randomUUID().toString();
    this.displayName = displayName != null && !displayName.isBlank() ? displayName.trim() : "Signal";
    this.kind = Objects.requireNonNull(kind, "kind");
  }

  public String getId() {
    return id;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    if (displayName != null && !displayName.isBlank()) {
      this.displayName = displayName.trim();
    }
  }

  public SignalValueKind getKind() {
    return kind;
  }

  public SharedMatrixLoci getSharedLoci() {
    return sharedLoci;
  }

  public void setSharedLoci(SharedMatrixLoci sharedLoci) {
    this.sharedLoci = sharedLoci;
  }

  public List<Path> getSourcePaths() {
    return Collections.unmodifiableList(sourcePaths);
  }

  public void addSourcePath(Path path) {
    if (path != null && !sourcePaths.contains(path)) {
      sourcePaths.add(path);
    }
  }

  public void addTrack(SampleTrack track) {
    if (track != null) {
      tracks.add(track);
    }
  }

  public void addTracks(Iterable<SampleTrack> toAdd) {
    if (toAdd == null) {
      return;
    }
    for (SampleTrack track : toAdd) {
      addTrack(track);
    }
  }

  public boolean removeTrack(SampleTrack track) {
    return track != null && tracks.remove(track);
  }

  public boolean containsTrack(SampleTrack track) {
    return track != null && tracks.contains(track);
  }

  public boolean isEmpty() {
    return tracks.isEmpty();
  }

  /** Live membership (identity set). */
  public Set<SampleTrack> tracks() {
    return Collections.unmodifiableSet(tracks);
  }

  /** Snapshot for collect/annotate. */
  public List<SampleTrack> trackList() {
    return new ArrayList<>(tracks);
  }

  /** Display names for project persistence (soft rematch on load). */
  public List<String> trackDisplayNames() {
    List<String> names = new ArrayList<>(tracks.size());
    for (SampleTrack track : tracks) {
      if (track != null && track.getDisplayName() != null) {
        names.add(track.getDisplayName());
      }
    }
    return names;
  }
}

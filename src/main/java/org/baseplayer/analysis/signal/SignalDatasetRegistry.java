package org.baseplayer.analysis.signal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.baseplayer.io.table.SharedMatrixLoci;
import org.baseplayer.samples.SampleTrack;

/**
 * Live registry of named Signal datasets (Analysis tabs). Cleared on sample wipe.
 */
public final class SignalDatasetRegistry {

  private static final SignalDatasetRegistry INSTANCE = new SignalDatasetRegistry();

  private final Map<String, SignalDataset> byId = new LinkedHashMap<>();
  private final CopyOnWriteArrayList<Consumer<SignalDatasetRegistry>> listeners =
      new CopyOnWriteArrayList<>();

  private SignalDatasetRegistry() {}

  public static SignalDatasetRegistry get() {
    return INSTANCE;
  }

  public synchronized List<SignalDataset> all() {
    return List.copyOf(byId.values());
  }

  public synchronized boolean isEmpty() {
    return byId.isEmpty();
  }

  public synchronized SignalDataset get(String id) {
    return id != null ? byId.get(id) : null;
  }

  /**
   * Find an existing dataset that can receive more tracks of this kind/name
   * (same display name + kind, or same shared loci for gene matrices).
   */
  public synchronized SignalDataset findCompatible(
      String displayName, SignalValueKind kind, SharedMatrixLoci loci) {
    if (kind == null) {
      return null;
    }
    if (loci != null) {
      for (SignalDataset ds : byId.values()) {
        if (ds.getKind() == kind && ds.getSharedLoci() == loci) {
          return ds;
        }
      }
    }
    if (displayName == null || displayName.isBlank()) {
      return null;
    }
    String needle = displayName.trim();
    for (SignalDataset ds : byId.values()) {
      if (ds.getKind() == kind && needle.equalsIgnoreCase(ds.getDisplayName())) {
        return ds;
      }
    }
    return null;
  }

  public synchronized SignalDataset findByTrack(SampleTrack track) {
    if (track == null) {
      return null;
    }
    for (SignalDataset ds : byId.values()) {
      if (ds.containsTrack(track)) {
        return ds;
      }
    }
    return null;
  }

  public synchronized void register(SignalDataset dataset) {
    Objects.requireNonNull(dataset, "dataset");
    byId.put(dataset.getId(), dataset);
    notifyListeners();
  }

  public synchronized SignalDataset remove(String id) {
    if (id == null) {
      return null;
    }
    SignalDataset removed = byId.remove(id);
    if (removed != null) {
      notifyListeners();
    }
    return removed;
  }

  /**
   * Drop a track from every dataset. Returns datasets that became empty
   * (caller should close their Analysis tabs).
   */
  public synchronized List<SignalDataset> removeTrack(SampleTrack track) {
    if (track == null) {
      return List.of();
    }
    List<SignalDataset> emptied = new ArrayList<>();
    for (SignalDataset ds : byId.values()) {
      if (ds.removeTrack(track) && ds.isEmpty()) {
        emptied.add(ds);
      }
    }
    if (!emptied.isEmpty()) {
      for (SignalDataset ds : emptied) {
        byId.remove(ds.getId());
      }
      notifyListeners();
    } else {
      // Membership may have changed without emptying.
      notifyListeners();
    }
    return emptied;
  }

  public synchronized void clear() {
    if (byId.isEmpty()) {
      return;
    }
    byId.clear();
    notifyListeners();
  }

  public void addListener(Consumer<SignalDatasetRegistry> listener) {
    if (listener != null) {
      listeners.add(listener);
    }
  }

  public void removeListener(Consumer<SignalDatasetRegistry> listener) {
    listeners.remove(listener);
  }

  private void notifyListeners() {
    for (Consumer<SignalDatasetRegistry> listener : listeners) {
      try {
        listener.accept(this);
      } catch (RuntimeException ignored) {
        // keep others
      }
    }
  }

  /** Case-insensitive unique tab title among current datasets. */
  public synchronized String uniqueDisplayName(String preferred) {
    String base = preferred != null && !preferred.isBlank() ? preferred.trim() : "Signal";
    if (findByDisplayNameIgnoreCase(base) == null) {
      return base;
    }
    int n = 2;
    while (findByDisplayNameIgnoreCase(base + " (" + n + ")") != null) {
      n++;
    }
    return base + " (" + n + ")";
  }

  private SignalDataset findByDisplayNameIgnoreCase(String name) {
    if (name == null) {
      return null;
    }
    for (SignalDataset ds : byId.values()) {
      if (name.equalsIgnoreCase(ds.getDisplayName())) {
        return ds;
      }
    }
    return null;
  }

  /** Soft rematch tracks by display name (project load). */
  public static List<SampleTrack> matchTracksByName(
      Iterable<SampleTrack> registryTracks, List<String> names) {
    if (registryTracks == null || names == null || names.isEmpty()) {
      return List.of();
    }
    List<SampleTrack> matched = new ArrayList<>();
    for (String name : names) {
      if (name == null || name.isBlank()) {
        continue;
      }
      String needle = name.trim().toLowerCase(Locale.ROOT);
      for (SampleTrack track : registryTracks) {
        if (track == null || track.getDisplayName() == null) {
          continue;
        }
        if (needle.equals(track.getDisplayName().trim().toLowerCase(Locale.ROOT))
            && !containsIdentity(matched, track)) {
          matched.add(track);
          break;
        }
      }
    }
    return matched;
  }

  private static boolean containsIdentity(List<SampleTrack> list, SampleTrack track) {
    for (SampleTrack existing : list) {
      if (existing == track) {
        return true;
      }
    }
    return false;
  }
}

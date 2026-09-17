package org.baseplayer.project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.baseplayer.features.BedTrack;
import org.baseplayer.features.BigWigTrack;
import org.baseplayer.features.Track;
import org.baseplayer.io.VcfManager;
import org.baseplayer.services.ServiceRegistry;

/**
 * Lightweight UI session state for the open project file (not a domain store).
 */
public final class ProjectSessionState {

  private static final ProjectSessionState INSTANCE = new ProjectSessionState();

  private Path file;
  private String name = "Untitled";
  private boolean dirty;
  private boolean suppressDirty;
  private final List<Consumer<ProjectSessionState>> listeners = new ArrayList<>();

  private ProjectSessionState() {}

  public static ProjectSessionState get() {
    return INSTANCE;
  }

  public Path getFile() {
    return file;
  }

  public String getName() {
    return name;
  }

  /**
   * True only when there are unsaved changes <em>and</em> the session has open data.
   * Empty sessions (no samples/tracks/VCFs) are never treated as dirty.
   */
  public boolean isDirty() {
    return dirty && hasOpenSessionData();
  }

  public String getDisplayLabel() {
    String base = (name == null || name.isBlank()) ? "Untitled" : name;
    return isDirty() ? base + " *" : base;
  }

  public void addListener(Consumer<ProjectSessionState> listener) {
    if (listener != null) {
      listeners.add(listener);
    }
  }

  /**
   * Suppress dirty notifications while a session file is being restored.
   * Callers must clear this (typically via {@link #setOpened}) when restore finishes.
   */
  public void setSuppressDirty(boolean suppress) {
    this.suppressDirty = suppress;
  }

  /**
   * Mark dirty only when samples, VCFs, or user feature tracks are open.
   * Clears the dirty flag if the session is empty.
   */
  public void markDirty() {
    if (suppressDirty) {
      return;
    }

    boolean hasData = hasOpenSessionData();
    boolean wasDisplayedDirty = dirty && hasData;

    if (!hasData) {
      if (dirty) {
        dirty = false;
        notifyListeners();
      }
      return;
    }

    dirty = true;
    if (!wasDisplayedDirty) {
      notifyListeners();
    }
  }

  public void markClean() {
    if (dirty) {
      dirty = false;
      notifyListeners();
    } else {
      notifyListeners();
    }
  }

  public void setOpened(Path projectFile, String displayName) {
    this.file = projectFile;
    if (displayName != null && !displayName.isBlank()) {
      this.name = displayName;
    } else if (projectFile != null) {
      String fileName = projectFile.getFileName().toString();
      int dot = fileName.lastIndexOf('.');
      this.name = dot > 0 ? fileName.substring(0, dot) : fileName;
    } else {
      this.name = "Untitled";
    }
    this.dirty = false;
    this.suppressDirty = false;
    notifyListeners();
  }

  public void clearSession() {
    this.file = null;
    this.name = "Untitled";
    this.dirty = false;
    this.suppressDirty = false;
    notifyListeners();
  }

  public void setName(String name) {
    String next = (name == null || name.isBlank()) ? "Untitled" : name;
    if (!Objects.equals(this.name, next)) {
      this.name = next;
      notifyListeners();
    }
  }

  /**
   * Whether the live session has anything worth saving (samples, VCFs, or user feature tracks).
   * Built-in default tracks (phyloP / gnomAD) do not count.
   */
  public static boolean hasOpenSessionData() {
    ServiceRegistry services = ServiceRegistry.getInstance();
    if (!services.getSampleRegistry().getSampleTracks().isEmpty()) {
      return true;
    }
    if (VcfManager.getInstance().hasLoadedVcf()) {
      return true;
    }
    for (Track track : services.getFeatureTrackViewportRegistry().getFeatureTracks()) {
      if (track instanceof BedTrack || track instanceof BigWigTrack) {
        return true;
      }
      String ucscId = track.getUcscTrackId();
      if (ucscId != null && !"phyloP100way".equals(ucscId)) {
        return true;
      }
    }
    return false;
  }

  private void notifyListeners() {
    for (Consumer<ProjectSessionState> listener : new ArrayList<>(listeners)) {
      try {
        listener.accept(this);
      } catch (Exception e) {
        System.err.println("ProjectSessionState listener error: " + e.getMessage());
      }
    }
  }
}

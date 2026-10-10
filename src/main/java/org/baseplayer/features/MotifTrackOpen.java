package org.baseplayer.features;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.baseplayer.features.motif.MotifMatrix;
import org.baseplayer.io.readers.JasparPfmReader;

import javafx.stage.Window;

/**
 * Interactive open for JASPAR/PFM motif tracks: parse file, motif picker, construct track.
 */
public final class MotifTrackOpen {

  private MotifTrackOpen() {}

  /**
   * Open a motif track. Empty when the user cancels the picker.
   */
  public static Optional<MotifTrack> openInteractive(Path filePath, Window owner)
      throws IOException {
    if (filePath == null) {
      return Optional.empty();
    }
    List<MotifMatrix> matrices = JasparPfmReader.read(filePath);
    Optional<MotifFilterDialog.Outcome> choice = MotifFilterDialog.show(
        owner, filePath.getFileName().toString(), matrices);
    if (choice.isEmpty()) {
      return Optional.empty();
    }
    Set<String> selected = choice.get().selectedIds();
    MotifTrack track = new MotifTrack(filePath, matrices, selected);
    return Optional.of(track);
  }

  /** Restore without dialog (e.g. session load). */
  public static MotifTrack openWithSelection(
      Path filePath, Set<String> selectedIds) throws IOException {
    List<MotifMatrix> matrices = JasparPfmReader.read(filePath);
    return new MotifTrack(filePath, matrices, selectedIds);
  }
}

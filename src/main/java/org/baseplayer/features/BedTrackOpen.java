package org.baseplayer.features;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.baseplayer.io.readers.BedFeatureNameCatalog;

import javafx.stage.Window;

/**
 * Interactive BED open: feature-name filter dialog when the file has named
 * features, then construct {@link BedTrack}.
 */
public final class BedTrackOpen {

  private BedTrackOpen() {}

  /**
   * Open a BED track, prompting for feature types when the catalog finds names.
   * Empty when the user cancels the filter dialog.
   */
  public static Optional<BedTrack> openInteractive(Path filePath, Window owner)
      throws IOException {
    if (filePath == null) {
      return Optional.empty();
    }
    Optional<BedFeatureNameCatalog.Catalog> catalog =
        BedFeatureNameCatalog.scanForDialog(filePath);
    BedFeatureFilterDialog.Outcome filter = null;
    if (catalog.isPresent()) {
      Optional<BedFeatureFilterDialog.Outcome> choice = BedFeatureFilterDialog.show(
          owner,
          filePath.getFileName().toString(),
          catalog.get());
      if (choice.isEmpty()) {
        return Optional.empty();
      }
      filter = choice.get();
    }
    BedTrack track = new BedTrack(filePath);
    if (filter != null && !filter.loadAll()) {
      track.setNameFilter(filter.selectedKeys(), filter.listedNames());
    }
    return Optional.of(track);
  }
}

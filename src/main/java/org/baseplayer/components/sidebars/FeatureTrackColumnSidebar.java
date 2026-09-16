package org.baseplayer.components.sidebars;

import java.io.File;

import org.baseplayer.features.BedTrack;
import org.baseplayer.features.BigWigTrack;
import org.baseplayer.samples.alignment.draw.TrackBodyCanvas;
import org.baseplayer.features.UcscTracksBrowser;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.FeatureTrackViewportRegistry;
import org.baseplayer.services.ServiceRegistry;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;

public class FeatureTrackColumnSidebar extends TrackColumnSidebar {

  private final FeatureTrackListPanel trackList;
  private final FeatureTrackViewportRegistry featureTrackViewportRegistry =
      ServiceRegistry.getInstance().getFeatureTrackViewportRegistry();
  private final DrawStackManager drawStackManager =
      ServiceRegistry.getInstance().getDrawStackManager();
  private final ReferenceGenomeService referenceGenomeService =
      ServiceRegistry.getInstance().getReferenceGenomeService();
  private TrackBodyCanvas featureTrackCanvas;

  public FeatureTrackColumnSidebar(StackPane parent) {
    super(parent, ServiceRegistry.getInstance().getFeatureTrackViewportRegistry());
    trackList = new FeatureTrackListPanel(contentPane);
  }

  public void setFeatureTrackCanvas(TrackBodyCanvas canvas) {
    featureTrackCanvas = canvas;
  }

  @Override
  protected TrackListPanel getTrackListPanel() {
    return trackList;
  }

  @Override
  protected String getTitle() {
    return "Feature Tracks";
  }

  @Override
  protected int getItemCount() {
    return featureTrackViewportRegistry.getDisplayedTrackCount();
  }

  @Override
  protected double estimateTrackBodyViewportHeightPixels() {
    if (!drawStackManager.isEmpty() && drawStackManager.getFirst().featureTrackCanvas != null) {
      double fromCanvas = drawStackManager.getFirst().featureTrackCanvas.getHeight();
      if (fromCanvas > 0) {
        return fromCanvas;
      }
    }
    double derived = featureTrackViewportRegistry.getTrackRowHeightPixels()
        * Math.max(1, featureTrackViewportRegistry.getVisibleTrackSlotCount());
    return Math.max(0, derived);
  }

  @Override
  protected String getVisibleTracksRangeLabelTitle() {
    return "Visible tracks";
  }

  @Override
  protected ContextMenu buildAddTrackMenu() {
    ContextMenu menu = new ContextMenu();
    MenuItem bedItem = new MenuItem("BED file...");
    bedItem.setOnAction(e -> addBedTrack());
    MenuItem bigWigItem = new MenuItem("BigWig file...");
    bigWigItem.setOnAction(e -> addBigWigTrack());
    MenuItem ucscBrowserItem = new MenuItem("Browse UCSC Tracks...");
    ucscBrowserItem.setOnAction(e -> showUcscTracksBrowser());
    menu.getItems().addAll(bedItem, bigWigItem, new SeparatorMenuItem(), ucscBrowserItem);
    return menu;
  }

  @Override
  protected ContextMenu buildSidebarSettingsMenu() {
    ContextMenu menu = new ContextMenu();
    menu.setStyle("-fx-background-color: #2B2B2B; -fx-border-color: #555; -fx-border-width: 1;");
    MenuItem removeAll = new MenuItem("Remove all feature tracks");
    removeAll.setOnAction(e -> {
      if (featureTrackCanvas == null) {
        return;
      }
      for (org.baseplayer.features.Track track : featureTrackCanvas.getTracks()) {
        featureTrackCanvas.removeTrack(track);
      }
      draw();
    });
    MenuItem placeholder = new MenuItem("Track Analysis (coming soon)");
    placeholder.setDisable(true);
    menu.getItems().addAll(removeAll, new SeparatorMenuItem(), placeholder);
    return menu;
  }

  private void addBedTrack() {
    if (featureTrackCanvas == null) {
      return;
    }
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Open BED File");
    File lastDirectory = UserPreferences.getLastDirectory("BED");
    if (lastDirectory != null) {
      try {
        fileChooser.setInitialDirectory(lastDirectory);
      } catch (IllegalArgumentException ignored) {
      }
    }
    fileChooser.getExtensionFilters().addAll(
        new FileChooser.ExtensionFilter("BED files", "*.bed", "*.bed.gz"),
        new FileChooser.ExtensionFilter("All files", "*.*"));
    File file = fileChooser.showOpenDialog(headerPane.getScene().getWindow());
    if (file == null) {
      return;
    }
    UserPreferences.setLastDirectory("BED", file.getParentFile());
    try {
      BedTrack bedTrack = new BedTrack(file.toPath());
      bedTrack.setVisible(true);
      featureTrackCanvas.addTrack(bedTrack);
      draw();
    } catch (java.io.IOException ex) {
      System.err.println("Failed to load BED file: " + ex.getMessage());
    }
  }

  private void addBigWigTrack() {
    if (featureTrackCanvas == null) {
      return;
    }
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Open BigWig File");
    File lastDirectory = UserPreferences.getLastDirectory("BIGWIG");
    if (lastDirectory != null) {
      try {
        fileChooser.setInitialDirectory(lastDirectory);
      } catch (IllegalArgumentException ignored) {
      }
    }
    fileChooser.getExtensionFilters().addAll(
        new FileChooser.ExtensionFilter("BigWig files", "*.bw", "*.bigwig", "*.bigWig"),
        new FileChooser.ExtensionFilter("All files", "*.*"));
    File file = fileChooser.showOpenDialog(headerPane.getScene().getWindow());
    if (file == null) {
      return;
    }
    UserPreferences.setLastDirectory("BIGWIG", file.getParentFile());
    try {
      BigWigTrack bigWigTrack = new BigWigTrack(file.toPath());
      bigWigTrack.setVisible(true);
      featureTrackCanvas.addTrack(bigWigTrack);
      draw();
    } catch (java.io.IOException ex) {
      System.err.println("Failed to load BigWig file: " + ex.getMessage());
    }
  }

  private void showUcscTracksBrowser() {
    if (featureTrackCanvas == null
        || headerPane.getScene() == null
        || headerPane.getScene().getWindow() == null) {
      return;
    }
    String genome = "hg38";
    if (referenceGenomeService.hasGenome()) {
      String name = referenceGenomeService.getCurrentGenome().getName();
      genome = switch (name.toLowerCase()) {
        case "grch38" -> "hg38";
        case "grch37" -> "hg19";
        case "grcm38" -> "mm10";
        case "grcm39" -> "mm39";
        default -> name.toLowerCase();
      };
    }
    new UcscTracksBrowser(
        (javafx.stage.Stage) headerPane.getScene().getWindow(),
        featureTrackCanvas,
        genome).show();
  }
}

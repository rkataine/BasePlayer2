package org.baseplayer.components.sidebars;

import java.io.File;

import org.baseplayer.features.BedTrack;
import org.baseplayer.features.BigWigTrack;
import org.baseplayer.features.FeatureTracksCanvas;
import org.baseplayer.features.UcscTracksBrowser;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.services.ServiceRegistry;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;

/**
 * Sidebar for feature tracks — extends {@link SidebarBase} for consistent
 * header chrome (⚙ / title / +) and delegates the track-row list to
 * {@link FeatureTracksPanel}.
 */
public class FeatureTracksSidebar extends SidebarBase {

  private final FeatureTracksPanel trackList;

  // Menus
  private final ContextMenu addTracksMenu;
  private final ContextMenu masterSettingsMenu;

  // Services
  private final ReferenceGenomeService referenceGenomeService;

  public FeatureTracksSidebar(StackPane parent) {
    super(parent, DEFAULT_HEADER_HEIGHT);
    this.referenceGenomeService = ServiceRegistry.getInstance().getReferenceGenomeService();

    trackList = new FeatureTracksPanel(contentPane);
    addTracksMenu = buildAddTracksMenu();
    masterSettingsMenu = buildMasterSettingsMenu();
  }

  /** Connect this sidebar to a specific {@link FeatureTracksCanvas}. */
  public void setFeatureTracksCanvas(FeatureTracksCanvas canvas) {
    trackList.setFeatureTracksCanvas(canvas);
  }

  // ── SidebarBase contract ──────────────────────────────────────────────────

  @Override protected String getTitle() { return "Feature Tracks"; }

  @Override protected int getItemCount() {
    FeatureTracksCanvas ftc = trackList.getFeatureTracksCanvas();
    return ftc != null ? ftc.getTracks().size() : 0;
  }

  @Override protected void onSettingsClicked(double screenX, double screenY) {
    masterSettingsMenu.show(headerPane, screenX, screenY);
  }

  @Override protected void onAddClicked(double screenX, double screenY) {
    addTracksMenu.show(headerPane, screenX, screenY);
  }

  @Override protected void drawContent() {
    trackList.draw();
  }

  // ── Add-tracks menu ───────────────────────────────────────────────────────

  private ContextMenu buildAddTracksMenu() {
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

  // ── Settings menu ─────────────────────────────────────────────────────────

  private ContextMenu buildMasterSettingsMenu() {
    ContextMenu menu = new ContextMenu();
    menu.setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");

    MenuItem placeholder = new MenuItem("Track Analysis (coming soon)");
    placeholder.setDisable(true);
    menu.getItems().add(placeholder);
    return menu;
  }

  // ── File loaders ──────────────────────────────────────────────────────────

  private void addBedTrack() {
    FeatureTracksCanvas ftc = trackList.getFeatureTracksCanvas();
    FileChooser fc = new FileChooser();
    fc.setTitle("Open BED File");
    File lastDir = UserPreferences.getLastDirectory("BED");
    if (lastDir != null) {
      try { fc.setInitialDirectory(lastDir); }
      catch (IllegalArgumentException ignored) { }
    }
    fc.getExtensionFilters().addAll(
        new FileChooser.ExtensionFilter("BED files", "*.bed", "*.bed.gz"),
        new FileChooser.ExtensionFilter("All files", "*.*"));

    File file = fc.showOpenDialog(headerPane.getScene().getWindow());
    if (file != null && ftc != null) {
      UserPreferences.setLastDirectory("BED", file.getParentFile());
      try {
        ftc.addTrack(new BedTrack(file.toPath()));
        ftc.setCollapsed(false);
        draw();
      } catch (java.io.IOException ex) {
        System.err.println("Failed to load BED file: " + ex.getMessage());
      }
    }
  }

  private void addBigWigTrack() {
    FeatureTracksCanvas ftc = trackList.getFeatureTracksCanvas();
    FileChooser fc = new FileChooser();
    fc.setTitle("Open BigWig File");
    File lastDir = UserPreferences.getLastDirectory("BIGWIG");
    if (lastDir != null) {
      try { fc.setInitialDirectory(lastDir); }
      catch (IllegalArgumentException ignored) { }
    }
    fc.getExtensionFilters().addAll(
        new FileChooser.ExtensionFilter("BigWig files", "*.bw", "*.bigwig", "*.bigWig"),
        new FileChooser.ExtensionFilter("All files", "*.*"));

    File file = fc.showOpenDialog(headerPane.getScene().getWindow());
    if (file != null && ftc != null) {
      UserPreferences.setLastDirectory("BIGWIG", file.getParentFile());
      try {
        ftc.addTrack(new BigWigTrack(file.toPath()));
        ftc.setCollapsed(false);
        draw();
      } catch (java.io.IOException ex) {
        System.err.println("Failed to load BigWig file: " + ex.getMessage());
      }
    }
  }

  private void showUcscTracksBrowser() {
    FeatureTracksCanvas ftc = trackList.getFeatureTracksCanvas();
    if (headerPane.getScene() == null || headerPane.getScene().getWindow() == null) return;

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
        ftc, genome
    ).show();
  }
}

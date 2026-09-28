package org.baseplayer.components;

import java.util.List;

import org.baseplayer.components.sidebars.SampleGroupDialog;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.DrawColors;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Window;

/**
 * Viewport overlay shown while sample tracks are filtered to a gene focus
 * (Variant Manager gene double-click). Clear manually or auto-clear when the
 * view leaves / zooms out from the gene. Offers saving focused samples as a group.
 */
public class GeneFocusBanner extends HBox {

  private static final String BANNER_STYLE =
      "-fx-background-color: linear-gradient(to bottom, #3d3420 0%, #2a2418 100%);"
          + "-fx-background-radius: 6;"
          + "-fx-border-color: #d0a050;"
          + "-fx-border-radius: 6;"
          + "-fx-border-width: 1;"
          + "-fx-padding: 6 10 6 12;";

  private static final String LABEL_STYLE =
      "-fx-text-fill: #f0e0c0; -fx-font-size: 12; -fx-font-weight: bold;";

  private static final String ACTION_STYLE =
      "-fx-background-color: #2a4a6a;"
          + "-fx-text-fill: #d8e8ff;"
          + "-fx-font-size: 11;"
          + "-fx-font-weight: bold;"
          + "-fx-background-radius: 4;"
          + "-fx-border-color: #4db8ff;"
          + "-fx-border-radius: 4;"
          + "-fx-border-width: 1;"
          + "-fx-cursor: hand;"
          + "-fx-padding: 2 10 2 10;";

  private static final String CLEAR_STYLE =
      "-fx-background-color: #4a3c28;"
          + "-fx-text-fill: #f0d090;"
          + "-fx-font-size: 11;"
          + "-fx-font-weight: bold;"
          + "-fx-background-radius: 4;"
          + "-fx-border-color: #c09040;"
          + "-fx-border-radius: 4;"
          + "-fx-border-width: 1;"
          + "-fx-cursor: hand;"
          + "-fx-padding: 2 10 2 10;";

  private final SampleRegistry sampleRegistry;
  private final DrawStackManager stackManager;
  private final Label messageLabel;
  private final Button groupButton;
  private boolean listenersAttached;

  public GeneFocusBanner() {
    this.sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();
    this.stackManager = ServiceRegistry.getInstance().getDrawStackManager();

    setManaged(true);
    setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
    setAlignment(Pos.CENTER_LEFT);
    setSpacing(12);
    setStyle(BANNER_STYLE);
    setVisible(false);
    setMouseTransparent(false);

    messageLabel = new Label();
    messageLabel.setStyle(LABEL_STYLE);
    messageLabel.setWrapText(false);

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.NEVER);

    groupButton = new Button("Add to group…");
    groupButton.setStyle(ACTION_STYLE);
    groupButton.setOnAction(e -> promptAddFocusedToGroup());

    Button clearButton = new Button("Clear");
    clearButton.setStyle(CLEAR_STYLE);
    clearButton.setOnAction(e -> clearGeneFocus());

    getChildren().addAll(messageLabel, spacer, groupButton, clearButton);
    setPadding(Insets.EMPTY);
  }

  public void attachListeners() {
    if (listenersAttached) {
      return;
    }
    listenersAttached = true;
    sampleRegistry.geneFocusRevisionProperty().addListener((obs, o, n) -> refreshOnFxThread());
    sampleRegistry.sampleGroupsRevisionProperty().addListener((obs, o, n) -> refreshOnFxThread());
    GenomicCanvas.update.addListener((obs, o, n) -> checkAutoClearAndRefresh());
    refresh();
  }

  private void promptAddFocusedToGroup() {
    List<SampleTrack> tracks = sampleRegistry.getFocusedTracks();
    if (tracks.isEmpty()) {
      return;
    }
    String gene = sampleRegistry.getFocusedGeneName();
    String suggested = (gene != null && !gene.isBlank())
        ? gene.trim()
        : sampleRegistry.suggestNextGroupName();
    Color initial = DrawColors.SAMPLE_GROUP_COLORS[
        sampleRegistry.getSampleGroups().size() % DrawColors.SAMPLE_GROUP_COLORS.length];

    Window owner = getScene() != null ? getScene().getWindow() : null;
    SampleGroupDialog.show(owner, tracks.size(), suggested, initial).ifPresent(outcome -> {
      if (outcome instanceof SampleGroupDialog.Outcome.Add add) {
        sampleRegistry.createGroupForTracks(tracks, add.name(), add.color());
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
        refresh();
      }
    });
  }

  private void clearGeneFocus() {
    if (!sampleRegistry.hasGeneFocusBanner()) {
      return;
    }
    sampleRegistry.clearSubsetSource(SampleRegistry.SubsetSource.GENE_FOCUS);
    int trackCount = sampleRegistry.getDisplayedTrackCount();
    // Expand visible range to the restored full displayed set.
    if (trackCount > 0) {
      sampleRegistry.setVisibleSamples(0, trackCount - 1,
          sampleRegistry.getSampleHeight() * Math.min(trackCount, 40));
    }
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
    refresh();
  }

  private void checkAutoClearAndRefresh() {
    DrawStack stack = stackManager.getHoverStack();
    if (stack == null && !stackManager.isEmpty()) {
      stack = stackManager.getFirst();
    }
    if (stack != null && sampleRegistry.hasGeneFocusBanner()) {
      sampleRegistry.maybeClearGeneFocusForView(
          stack.getChromosome(), stack.getViewStart(), stack.getViewEnd());
    }
    refreshOnFxThread();
  }

  private void refreshOnFxThread() {
    if (Platform.isFxApplicationThread()) {
      refresh();
    } else {
      Platform.runLater(this::refresh);
    }
  }

  private void refresh() {
    if (!sampleRegistry.hasGeneFocusBanner()) {
      setVisible(false);
      return;
    }
    String gene = sampleRegistry.getFocusedGeneName();
    int sampleCount = sampleRegistry.getDisplayedTrackCount();
    messageLabel.setText(
        "Gene focus: " + gene
            + "  ·  showing " + sampleCount
            + (sampleCount == 1 ? " sample" : " samples")
            + " with mutation"
            + "  ·  save as a sample group?");
    groupButton.setDisable(sampleCount <= 0);
    setVisible(true);
  }
}

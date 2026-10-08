package org.baseplayer.components;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.baseplayer.components.sidebars.SampleGroupDialog;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.draw.ZoomController;
import org.baseplayer.samples.SampleTag;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.DrawColors;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;

/**
 * Floating overlay on the main frame while sample tracks are subsetted — gene
 * focus or sidebar text filter. Draggable; × hides the card without clearing
 * the subset. Clear removes the filter/focus. Quick tools apply tags / new group
 * to currently displayed samples.
 */
public class GeneFocusBanner extends StackPane {

  private static final String ROOT_STYLE_GENE =
      "-fx-background-color: linear-gradient(to bottom, #3d3420 0%, #2a2418 100%);"
          + "-fx-background-radius: 8;"
          + "-fx-border-color: #d0a050;"
          + "-fx-border-radius: 8;"
          + "-fx-border-width: 1;"
          + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 10, 0.2, 0, 2);";

  private static final String ROOT_STYLE_FILTER =
      "-fx-background-color: linear-gradient(to bottom, #1e3348 0%, #152636 100%);"
          + "-fx-background-radius: 8;"
          + "-fx-border-color: #4db8ff;"
          + "-fx-border-radius: 8;"
          + "-fx-border-width: 1;"
          + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 10, 0.2, 0, 2);";

  private static final String LABEL_STYLE =
      "-fx-text-fill: #f0e0c0; -fx-font-size: 12; -fx-font-weight: bold;";

  private static final String LABEL_STYLE_FILTER =
      "-fx-text-fill: #d8e8ff; -fx-font-size: 12; -fx-font-weight: bold;";

  private static final String META_STYLE =
      "-fx-text-fill: #c8b890; -fx-font-size: 11;";

  private static final String META_STYLE_FILTER =
      "-fx-text-fill: #9bb8d0; -fx-font-size: 11;";

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
      "-fx-background-color: #4a3030;"
          + "-fx-text-fill: #ffd0d0;"
          + "-fx-font-size: 11;"
          + "-fx-font-weight: bold;"
          + "-fx-background-radius: 4;"
          + "-fx-border-color: #aa6666;"
          + "-fx-border-radius: 4;"
          + "-fx-border-width: 1;"
          + "-fx-cursor: hand;"
          + "-fx-padding: 2 10 2 10;";

  private static final String CLOSE_NORMAL =
      "-fx-background-color: transparent;"
          + "-fx-text-fill: #c8b890;"
          + "-fx-font-size: 16;"
          + "-fx-font-weight: bold;"
          + "-fx-padding: 0 6 2 6;"
          + "-fx-background-radius: 4;"
          + "-fx-cursor: hand;";

  private static final String CLOSE_HOVER =
      "-fx-background-color: rgba(180,50,50,0.8);"
          + "-fx-text-fill: white;"
          + "-fx-font-size: 16;"
          + "-fx-font-weight: bold;"
          + "-fx-padding: 0 6 2 6;"
          + "-fx-background-radius: 4;"
          + "-fx-cursor: hand;";

  private final SampleRegistry sampleRegistry;
  private final Label titleLabel;
  private final Label detailLabel;
  private final Label tagsHeading;
  private final FlowPane tagChips;
  private final Map<SampleTag, ToggleButton> tagButtons = new EnumMap<>(SampleTag.class);
  private final Button groupButton;
  private final Button clearButton;
  private final Button closeButton;
  private boolean listenersAttached;
  private boolean updatingTagButtons;

  /** User hid the card with ×; subset stays active until Clear or query changes. */
  private boolean userDismissed;
  private String dismissedForQuery = "";
  private String dismissedForGene = "";

  private boolean dragging;
  private boolean userMoved;
  private double dragSceneX;
  private double dragSceneY;
  private double dragOriginTranslateX;
  private double dragOriginTranslateY;

  public GeneFocusBanner() {
    this.sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();

    getStyleClass().add("gene-focus-banner");
    setStyle(ROOT_STYLE_GENE);
    setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
    setVisible(false);
    setMouseTransparent(false);
    setPickOnBounds(true);
    setCursor(Cursor.MOVE);

    titleLabel = new Label();
    titleLabel.setStyle(LABEL_STYLE);
    titleLabel.setWrapText(false);
    titleLabel.setMaxWidth(480);
    titleLabel.setMouseTransparent(true);

    detailLabel = new Label();
    detailLabel.setStyle(META_STYLE);
    detailLabel.setWrapText(false);
    detailLabel.setMaxWidth(480);
    detailLabel.setMouseTransparent(true);

    tagsHeading = new Label("Tags");
    tagsHeading.setStyle(META_STYLE);
    tagsHeading.setMouseTransparent(true);

    tagChips = new FlowPane(6, 4);
    tagChips.setMaxWidth(480);
    for (SampleTag tag : SampleTag.values()) {
      ToggleButton chip = new ToggleButton(tag.displayName());
      chip.setFocusTraversable(false);
      chip.setCursor(Cursor.HAND);
      chip.setTooltip(new Tooltip(tag.description()));
      styleTagChip(chip, tag, false);
      chip.selectedProperty().addListener((obs, o, on) -> {
        if (updatingTagButtons) {
          return;
        }
        applyTagToDisplayed(tag, Boolean.TRUE.equals(on));
        styleTagChip(chip, tag, Boolean.TRUE.equals(on));
      });
      tagButtons.put(tag, chip);
      tagChips.getChildren().add(chip);
    }

    groupButton = new Button("New group…");
    groupButton.setStyle(ACTION_STYLE);
    groupButton.setFocusTraversable(false);
    groupButton.setCursor(Cursor.HAND);
    groupButton.setOnAction(e -> promptAddDisplayedToGroup());

    clearButton = new Button("Clear filter");
    clearButton.setStyle(CLEAR_STYLE);
    clearButton.setFocusTraversable(false);
    clearButton.setCursor(Cursor.HAND);
    clearButton.setOnAction(e -> clearActiveSubset());

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    spacer.setMouseTransparent(true);

    HBox actionRow = new HBox(8, groupButton, clearButton, spacer);
    actionRow.setAlignment(Pos.CENTER_LEFT);

    VBox body = new VBox(6, titleLabel, detailLabel, tagsHeading, tagChips, actionRow);
    body.setPadding(new Insets(10, 28, 10, 12));
    body.setAlignment(Pos.CENTER_LEFT);

    closeButton = new Button("\u00D7");
    closeButton.setFocusTraversable(false);
    closeButton.setStyle(CLOSE_NORMAL);
    closeButton.setCursor(Cursor.HAND);
    closeButton.setOnMouseEntered(e -> closeButton.setStyle(CLOSE_HOVER));
    closeButton.setOnMouseExited(e -> closeButton.setStyle(CLOSE_NORMAL));
    closeButton.setOnAction(e -> dismissCard());

    getChildren().addAll(body, closeButton);
    StackPane.setAlignment(closeButton, Pos.TOP_RIGHT);
    StackPane.setMargin(closeButton, new Insets(2, 2, 0, 0));

    installDragHandlers();
  }

  /**
   * Prefer the main-frame glass host so the card can be dragged over the whole
   * window; fall back to {@code overlayPane} if glass is not installed yet.
   */
  public void attachTo(StackPane overlayPane) {
    StackPane host = ZoomController.getGlassHost();
    if (host == null) {
      host = overlayPane;
    }
    if (host == null) {
      return;
    }
    if (getParent() instanceof Pane oldParent && oldParent != host) {
      oldParent.getChildren().remove(this);
    }
    if (!host.getChildren().contains(this)) {
      host.getChildren().add(this);
      StackPane.setAlignment(this, Pos.TOP_LEFT);
      StackPane.setMargin(this, Insets.EMPTY);
    }
    toFront();
    attachListeners();
  }

  public void attachListeners() {
    if (listenersAttached) {
      return;
    }
    listenersAttached = true;
    sampleRegistry.geneFocusRevisionProperty().addListener((obs, o, n) -> refreshOnFxThread());
    sampleRegistry.sampleGroupsRevisionProperty().addListener((obs, o, n) -> refreshOnFxThread());
    GenomicCanvas.update.addListener((obs, o, n) -> refreshOnFxThread());
    parentProperty().addListener((obs, o, n) -> {
      if (n instanceof Pane pane) {
        pane.widthProperty().addListener((wObs, wo, wn) -> clampToParent());
        pane.heightProperty().addListener((hObs, ho, hn) -> clampToParent());
      }
    });
    refresh();
  }

  private void installDragHandlers() {
    addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
      if (e.getButton() != MouseButton.PRIMARY || isInteractiveTarget(e)) {
        return;
      }
      dragging = true;
      dragSceneX = e.getSceneX();
      dragSceneY = e.getSceneY();
      dragOriginTranslateX = getTranslateX();
      dragOriginTranslateY = getTranslateY();
      toFront();
      e.consume();
    });
    addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
      if (!dragging || e.getButton() != MouseButton.PRIMARY) {
        return;
      }
      setTranslateX(dragOriginTranslateX + (e.getSceneX() - dragSceneX));
      setTranslateY(dragOriginTranslateY + (e.getSceneY() - dragSceneY));
      userMoved = true;
      clampToParent();
      e.consume();
    });
    addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
      if (dragging) {
        dragging = false;
        clampToParent();
        e.consume();
      }
    });
  }

  private boolean isInteractiveTarget(MouseEvent e) {
    if (!(e.getTarget() instanceof Node node)) {
      return false;
    }
    Node cur = node;
    while (cur != null && cur != this) {
      if (cur == closeButton || cur == groupButton || cur == clearButton
          || cur == tagChips || cur instanceof ToggleButton) {
        return true;
      }
      cur = cur.getParent();
    }
    return false;
  }

  private void clampToParent() {
    if (!(getParent() instanceof Pane parent) || !isVisible()) {
      return;
    }
    double parentW = parent.getWidth();
    double parentH = parent.getHeight();
    double w = getWidth() > 1 ? getWidth() : prefWidth(-1);
    double h = getHeight() > 1 ? getHeight() : prefHeight(-1);
    if (parentW <= 0 || parentH <= 0 || w <= 0 || h <= 0) {
      return;
    }
    double maxX = Math.max(0, parentW - w);
    double maxY = Math.max(0, parentH - h);
    setTranslateX(Math.max(0, Math.min(getTranslateX(), maxX)));
    setTranslateY(Math.max(0, Math.min(getTranslateY(), maxY)));
  }

  private void dismissCard() {
    userDismissed = true;
    dismissedForQuery = nullToEmpty(sampleRegistry.getActiveSampleFilterQuery());
    dismissedForGene = nullToEmpty(sampleRegistry.getFocusedGeneName());
    setVisible(false);
  }

  private void promptAddDisplayedToGroup() {
    List<SampleTrack> tracks = sampleRegistry.getDisplayedTracks();
    if (tracks.isEmpty()) {
      return;
    }
    String suggested;
    if (sampleRegistry.hasGeneFocusBanner()) {
      String gene = sampleRegistry.getFocusedGeneName();
      suggested = (gene != null && !gene.isBlank())
          ? gene.trim()
          : sampleRegistry.suggestNextGroupName();
    } else {
      String query = sampleRegistry.getActiveSampleFilterQuery();
      suggested = (query != null && !query.isBlank())
          ? query.trim()
          : sampleRegistry.suggestNextGroupName();
    }
    Color initial = DrawColors.SAMPLE_GROUP_COLORS[
        sampleRegistry.getSampleGroups().size() % DrawColors.SAMPLE_GROUP_COLORS.length];

    Window owner = getScene() != null ? getScene().getWindow() : null;
    SampleGroupDialog.show(owner, tracks.size(), suggested, initial).ifPresent(outcome -> {
      if (outcome instanceof SampleGroupDialog.Outcome.Add add) {
        sampleRegistry.createGroupForTracks(tracks, add.name(), add.color());
        if (add.tags() != null && !add.tags().isEmpty()) {
          sampleRegistry.setTracksTags(tracks, add.tags());
        }
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
        refresh();
      }
    });
  }

  private void applyTagToDisplayed(SampleTag tag, boolean wantOn) {
    List<SampleTrack> tracks = sampleRegistry.getDisplayedTracks();
    if (tracks.isEmpty() || tag == null) {
      return;
    }
    if (wantOn) {
      sampleRegistry.addTagToTracks(tracks, tag);
    } else {
      for (SampleTrack track : tracks) {
        if (track.hasTag(tag)) {
          sampleRegistry.toggleTrackTag(track, tag);
        }
      }
    }
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
  }

  private void clearActiveSubset() {
    boolean hadSubset = sampleRegistry.hasActiveSubset();
    if (sampleRegistry.hasGeneFocusBanner() || sampleRegistry.hasFocusedTracks()) {
      sampleRegistry.clearSubsetSource(SampleRegistry.SubsetSource.GENE_FOCUS);
    }
    if (sampleRegistry.hasActiveSampleFilterQuery()) {
      sampleRegistry.clearSubsetSource(SampleRegistry.SubsetSource.TEXT_FILTER);
    }
    userDismissed = false;
    dismissedForQuery = "";
    dismissedForGene = "";
    if (!hadSubset) {
      refresh();
      return;
    }
    sampleRegistry.showDefaultHeightWindowFromStart();
    GenomicCanvas.update.set(!GenomicCanvas.update.get());
    refresh();
  }

  private void refreshOnFxThread() {
    if (Platform.isFxApplicationThread()) {
      refresh();
    } else {
      Platform.runLater(this::refresh);
    }
  }

  private void refresh() {
    boolean geneFocus = sampleRegistry.hasGeneFocusBanner();
    boolean textFilter = sampleRegistry.hasActiveSampleFilterQuery();
    if (!geneFocus && !textFilter) {
      userDismissed = false;
      dismissedForQuery = "";
      dismissedForGene = "";
      setVisible(false);
      return;
    }

    String query = nullToEmpty(sampleRegistry.getActiveSampleFilterQuery());
    String gene = nullToEmpty(sampleRegistry.getFocusedGeneName());
    if (userDismissed
        && query.equals(dismissedForQuery)
        && gene.equals(dismissedForGene)) {
      setVisible(false);
      return;
    }
    // Subset changed since dismiss — show again.
    if (userDismissed) {
      userDismissed = false;
    }

    boolean wasHidden = !isVisible();
    int sampleCount = sampleRegistry.getDisplayedTrackCount();
    if (geneFocus) {
      setStyle(ROOT_STYLE_GENE);
      titleLabel.setStyle(LABEL_STYLE);
      detailLabel.setStyle(META_STYLE);
      tagsHeading.setStyle(META_STYLE);
      titleLabel.setText("Gene focus: " + (gene.isBlank() ? "" : gene));
      StringBuilder detail = new StringBuilder(
          "Showing " + sampleCount
              + (sampleCount == 1 ? " sample" : " samples")
              + " with mutation");
      if (textFilter) {
        detail.append(" · filter \"").append(query).append('"');
      }
      detailLabel.setText(detail.toString());
      clearButton.setText(textFilter ? "Clear focus & filter" : "Clear focus");
    } else {
      setStyle(ROOT_STYLE_FILTER);
      titleLabel.setStyle(LABEL_STYLE_FILTER);
      detailLabel.setStyle(META_STYLE_FILTER);
      tagsHeading.setStyle(META_STYLE_FILTER);
      titleLabel.setText("Sample filter: \"" + query + "\"");
      detailLabel.setText(
          "Showing " + sampleCount
              + (sampleCount == 1 ? " sample" : " samples")
              + " — tag or group them below");
      clearButton.setText("Clear filter");
    }

    syncTagButtons();
    boolean hasTracks = sampleCount > 0;
    groupButton.setDisable(!hasTracks);
    for (ToggleButton chip : tagButtons.values()) {
      chip.setDisable(!hasTracks);
    }

    setVisible(true);
    toFront();
    if (wasHidden && !userMoved) {
      Platform.runLater(this::placeDefault);
    } else {
      Platform.runLater(this::clampToParent);
    }
  }

  private void syncTagButtons() {
    List<SampleTrack> tracks = sampleRegistry.getDisplayedTracks();
    updatingTagButtons = true;
    try {
      for (Map.Entry<SampleTag, ToggleButton> entry : tagButtons.entrySet()) {
        SampleTag tag = entry.getKey();
        ToggleButton chip = entry.getValue();
        boolean allHave = !tracks.isEmpty();
        for (SampleTrack track : tracks) {
          if (!track.hasTag(tag)) {
            allHave = false;
            break;
          }
        }
        chip.setSelected(allHave);
        styleTagChip(chip, tag, allHave);
      }
    } finally {
      updatingTagButtons = false;
    }
  }

  private static void styleTagChip(ToggleButton chip, SampleTag tag, boolean on) {
    chip.setStyle(
        "-fx-background-color: " + (on ? tag.toCssHex() : "#243040") + ";"
            + "-fx-text-fill: " + (on ? "#111" : "#c8d8e8") + ";"
            + "-fx-font-size: 11; -fx-font-weight: bold;"
            + "-fx-padding: 2 8 2 8; -fx-background-radius: 10; -fx-cursor: hand;"
            + (on ? "" : "-fx-border-color: #4a6080; -fx-border-radius: 10;"));
  }

  private void placeDefault() {
    if (!(getParent() instanceof Pane parent)) {
      return;
    }
    autosize();
    double parentW = parent.getWidth();
    double w = getWidth() > 1 ? getWidth() : prefWidth(-1);
    if (parentW <= 0 || w <= 0) {
      return;
    }
    setTranslateX(Math.max(0, (parentW - w) / 2.0));
    setTranslateY(8);
    clampToParent();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}

package org.baseplayer.components;

import java.util.List;

import org.baseplayer.components.sidebars.SampleGroupDialog;
import org.baseplayer.draw.GenomicCanvas;
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
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;

/**
 * Floating overlay shown while sample tracks are filtered to a gene focus
 * (Variant Manager gene double-click). Self-contained: draggable card with a
 * top-right close control. Offers saving focused samples as a group.
 */
public class GeneFocusBanner extends StackPane {

  private static final String ROOT_STYLE =
      "-fx-background-color: linear-gradient(to bottom, #3d3420 0%, #2a2418 100%);"
          + "-fx-background-radius: 8;"
          + "-fx-border-color: #d0a050;"
          + "-fx-border-radius: 8;"
          + "-fx-border-width: 1;"
          + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 10, 0.2, 0, 2);";

  private static final String LABEL_STYLE =
      "-fx-text-fill: #f0e0c0; -fx-font-size: 12; -fx-font-weight: bold;";

  private static final String META_STYLE =
      "-fx-text-fill: #c8b890; -fx-font-size: 11;";

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
  private final Button groupButton;
  private final Button closeButton;
  private boolean listenersAttached;

  private boolean dragging;
  private boolean userMoved;
  private double dragSceneX;
  private double dragSceneY;
  private double dragOriginTranslateX;
  private double dragOriginTranslateY;

  public GeneFocusBanner() {
    this.sampleRegistry = ServiceRegistry.getInstance().getSampleRegistry();

    getStyleClass().add("gene-focus-banner");
    setStyle(ROOT_STYLE);
    setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
    setVisible(false);
    setMouseTransparent(false);
    setPickOnBounds(true);
    setCursor(Cursor.MOVE);

    titleLabel = new Label();
    titleLabel.setStyle(LABEL_STYLE);
    titleLabel.setWrapText(false);
    titleLabel.setMaxWidth(420);
    titleLabel.setMouseTransparent(true);

    detailLabel = new Label();
    detailLabel.setStyle(META_STYLE);
    detailLabel.setWrapText(false);
    detailLabel.setMaxWidth(420);
    detailLabel.setMouseTransparent(true);

    groupButton = new Button("Add to group…");
    groupButton.setStyle(ACTION_STYLE);
    groupButton.setFocusTraversable(false);
    groupButton.setCursor(Cursor.HAND);
    groupButton.setOnAction(e -> promptAddFocusedToGroup());

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    spacer.setMouseTransparent(true);

    HBox actionRow = new HBox(10, groupButton, spacer);
    actionRow.setAlignment(Pos.CENTER_LEFT);

    VBox body = new VBox(4, titleLabel, detailLabel, actionRow);
    body.setPadding(new Insets(10, 28, 10, 12));
    body.setAlignment(Pos.CENTER_LEFT);

    closeButton = new Button("\u00D7");
    closeButton.setFocusTraversable(false);
    closeButton.setStyle(CLOSE_NORMAL);
    closeButton.setCursor(Cursor.HAND);
    closeButton.setOnMouseEntered(e -> closeButton.setStyle(CLOSE_HOVER));
    closeButton.setOnMouseExited(e -> closeButton.setStyle(CLOSE_NORMAL));
    closeButton.setOnAction(e -> clearGeneFocus());

    getChildren().addAll(body, closeButton);
    StackPane.setAlignment(closeButton, Pos.TOP_RIGHT);
    StackPane.setMargin(closeButton, new Insets(2, 2, 0, 0));

    installDragHandlers();
  }

  /**
   * Host this banner in a viewport overlay. Positions itself; do not apply
   * StackPane alignment/margin from the outside.
   */
  public void attachTo(StackPane overlayPane) {
    if (overlayPane == null) {
      return;
    }
    if (!overlayPane.getChildren().contains(this)) {
      overlayPane.getChildren().add(this);
      StackPane.setAlignment(this, Pos.TOP_LEFT);
      StackPane.setMargin(this, Insets.EMPTY);
    }
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
      if (cur == closeButton || cur == groupButton) {
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
    // TOP_LEFT alignment → layout origin is (0,0); position is entirely translate.
    double maxX = Math.max(0, parentW - w);
    double maxY = Math.max(0, parentH - h);
    setTranslateX(Math.max(0, Math.min(getTranslateX(), maxX)));
    setTranslateY(Math.max(0, Math.min(getTranslateY(), maxY)));
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
    if (trackCount > 0) {
      sampleRegistry.setVisibleSamples(0, trackCount - 1,
          sampleRegistry.getSampleHeight() * Math.min(trackCount, 40));
    }
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
    if (!sampleRegistry.hasGeneFocusBanner()) {
      setVisible(false);
      return;
    }
    boolean wasHidden = !isVisible();
    String gene = sampleRegistry.getFocusedGeneName();
    int sampleCount = sampleRegistry.getDisplayedTrackCount();
    titleLabel.setText("Gene focus: " + (gene != null ? gene : ""));
    detailLabel.setText(
        "Showing " + sampleCount
            + (sampleCount == 1 ? " sample" : " samples")
            + " with mutation — save as a sample group?");
    groupButton.setDisable(sampleCount <= 0);
    setVisible(true);
    toFront();
    if (wasHidden && !userMoved) {
      Platform.runLater(this::placeDefault);
    } else {
      Platform.runLater(this::clampToParent);
    }
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
}

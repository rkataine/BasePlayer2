package org.baseplayer.features;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import org.baseplayer.components.AppDialog;
import org.baseplayer.ui.theme.AppTheme;
import org.baseplayer.utils.GeneColors;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Shared searchable multi-select dialog used by BED feature types and JASPAR
 * motifs (swatch rows, search filter, select-all matching).
 */
public final class FeatureSelectionDialog {

  /** One selectable row. {@code searchText} is matched case-insensitively. */
  public record Item(
      String id,
      String searchText,
      String label,
      Color color,
      String tooltip) {
    public Item {
      Objects.requireNonNull(id, "id");
      searchText = searchText != null ? searchText : id;
      label = label != null ? label : id;
    }

    public Item(String id, String searchText, String label, Color color) {
      this(id, searchText, label, color, null);
    }
  }

  public record Outcome(Set<String> selectedIds) {}

  /**
   * Optional header control beside “All matching filter”.
   * {@code action} receives the current selection set and visible matching ids.
   */
  public record HeaderAction(
      String label,
      String tooltip,
      Function<HeaderActionContext, Set<String>> action) {}

  public record HeaderActionContext(
      Set<String> selected, List<String> visibleIds, String searchQuery) {}

  public record Config(
      String windowTitle,
      String title,
      String hint,
      String searchPrompt,
      String confirmLabel,
      boolean selectAllWhenInitiallyNull,
      List<HeaderAction> headerActions) {

    public Config {
      windowTitle = windowTitle != null && !windowTitle.isBlank()
          ? windowTitle : "Select items";
      title = title != null ? title : "Select items";
      hint = hint != null ? hint : "";
      searchPrompt = searchPrompt != null && !searchPrompt.isBlank()
          ? searchPrompt : "Filter…";
      confirmLabel = confirmLabel != null && !confirmLabel.isBlank()
          ? confirmLabel : "Load";
      headerActions = headerActions != null ? List.copyOf(headerActions) : List.of();
    }
  }

  /**
   * Live updates while the dialog is open (call from any thread; applies on FX).
   */
  public static final class LiveSession {
    private final Stage dialog;
    private final Label hintLabel;
    private final VBox list;
    private final List<Row> rows;
    private final Map<String, Row> rowById;
    private final Set<String> selected;
    private final Runnable syncUi;
    private final String otherId;
    private volatile boolean closed;

    private LiveSession(
        Stage dialog,
        Label hintLabel,
        VBox list,
        List<Row> rows,
        Map<String, Row> rowById,
        Set<String> selected,
        Runnable syncUi,
        String otherId) {
      this.dialog = dialog;
      this.hintLabel = hintLabel;
      this.list = list;
      this.rows = rows;
      this.rowById = rowById;
      this.selected = selected;
      this.syncUi = syncUi;
      this.otherId = otherId;
    }

    public boolean isShowing() {
      return !closed && dialog.isShowing();
    }

    public void setHint(String hint) {
      runOnFx(() -> {
        if (closed || hint == null) {
          return;
        }
        hintLabel.setText(hint);
      });
    }

    /**
     * Merge newly discovered items into the list. New ids are auto-selected when
     * every previously listed non-other row is selected, or when {@code otherId}
     * is selected.
     */
    public void mergeItems(List<Item> nextItems) {
      if (nextItems == null) {
        return;
      }
      runOnFx(() -> applyMerge(nextItems));
    }

    private void applyMerge(List<Item> nextItems) {
      if (closed) {
        return;
      }
      boolean selectNew = shouldAutoSelectNew();
      Item otherItem = null;
      List<Item> regular = new ArrayList<>(nextItems.size());
      for (Item item : nextItems) {
        if (otherId != null && otherId.equals(item.id())) {
          otherItem = item;
        } else {
          regular.add(item);
        }
      }

      for (Item item : regular) {
        if (rowById.containsKey(item.id())) {
          continue;
        }
        Row row = addRow(item, /* beforeOther= */ true);
        if (selectNew) {
          selected.add(item.id());
        }
        rowById.put(item.id(), row);
      }

      if (otherItem != null) {
        Row existingOther = otherId != null ? rowById.get(otherId) : null;
        if (existingOther == null) {
          Row row = addRow(otherItem, /* beforeOther= */ false);
          if (selectNew || selected.contains(otherId)) {
            selected.add(otherItem.id());
          }
          rowById.put(otherItem.id(), row);
        }
      } else if (otherId != null) {
        Row existingOther = rowById.remove(otherId);
        if (existingOther != null) {
          rows.remove(existingOther);
          list.getChildren().remove(existingOther.row);
          selected.remove(otherId);
        }
      }
      syncUi.run();
    }

    private boolean shouldAutoSelectNew() {
      if (otherId != null && selected.contains(otherId)) {
        return true;
      }
      boolean anyRegular = false;
      for (Row row : rows) {
        if (otherId != null && otherId.equals(row.id)) {
          continue;
        }
        anyRegular = true;
        if (!selected.contains(row.id)) {
          return false;
        }
      }
      return anyRegular;
    }

    private Row addRow(Item item, boolean beforeOther) {
      Row row = buildRow(item);
      row.row.setOnMouseClicked(e -> {
        if (!selected.add(item.id())) {
          selected.remove(item.id());
        }
        syncUi.run();
        e.consume();
      });
      int otherIndex = -1;
      if (beforeOther && otherId != null) {
        for (int i = 0; i < rows.size(); i++) {
          if (otherId.equals(rows.get(i).id)) {
            otherIndex = i;
            break;
          }
        }
      }
      if (otherIndex >= 0) {
        rows.add(otherIndex, row);
        list.getChildren().add(otherIndex, row.row);
      } else {
        rows.add(row);
        list.getChildren().add(row.row);
      }
      return row;
    }

    private void runOnFx(Runnable action) {
      if (Platform.isFxApplicationThread()) {
        action.run();
      } else {
        Platform.runLater(action);
      }
    }

    private void markClosed() {
      closed = true;
    }
  }

  private FeatureSelectionDialog() {}

  public static Optional<Outcome> show(
      Window owner,
      Config config,
      List<Item> items,
      Set<String> initiallySelected) {
    return show(owner, config, items, initiallySelected, null, null);
  }

  /**
   * @param otherId sentinel id kept at list end for live merges (e.g. BED Other);
   *        {@code null} when unused
   * @param liveBinder optional; receives {@link LiveSession} once the dialog is up
   */
  public static Optional<Outcome> show(
      Window owner,
      Config config,
      List<Item> items,
      Set<String> initiallySelected,
      String otherId,
      Consumer<LiveSession> liveBinder) {
    if (config == null || items == null || items.isEmpty()) {
      return Optional.empty();
    }

    Stage dialog = new Stage(StageStyle.UTILITY);
    dialog.initModality(Modality.WINDOW_MODAL);
    if (owner != null) {
      dialog.initOwner(owner);
    }
    dialog.setTitle(config.windowTitle());
    dialog.setResizable(true);

    Label title = AppDialog.titleLabel(config.title());
    Label hint = AppDialog.hintLabel(config.hint());

    Set<String> selected = new LinkedHashSet<>();
    if (initiallySelected == null && config.selectAllWhenInitiallyNull()) {
      for (Item item : items) {
        selected.add(item.id());
      }
    } else if (initiallySelected != null) {
      for (Item item : items) {
        if (initiallySelected.contains(item.id())) {
          selected.add(item.id());
        }
      }
      if (selected.isEmpty() && config.selectAllWhenInitiallyNull()) {
        for (Item item : items) {
          selected.add(item.id());
        }
      }
    }

    TextField search = new TextField();
    search.setPromptText(config.searchPrompt());
    search.setStyle("-fx-font-size: 11px;");

    Region selectVisibleBox = new Region();
    Polyline selectVisibleCheck = new Polyline();
    StackPane selectVisiblePane = new StackPane();
    Label selectVisibleLabel = new Label("All matching filter");
    selectVisibleLabel.setStyle("-fx-font-size: 11px;");
    selectVisibleLabel.setMouseTransparent(true);

    Label selectedCount = new Label();
    selectedCount.setStyle("-fx-font-size: 11px;");

    VBox list = new VBox(3);
    list.setPadding(new Insets(0, 4, 4, 2));
    List<Row> rows = new ArrayList<>(items.size());
    Map<String, Row> rowById = new LinkedHashMap<>();

    Button cancel = AppDialog.secondaryButton("Cancel");
    cancel.setCancelButton(true);
    Button confirm = AppDialog.primaryButton(config.confirmLabel());
    confirm.setDefaultButton(true);

    Runnable syncUi = () -> {
      String q = normalizeQuery(search.getText());
      int visible = 0;
      int visibleSelected = 0;
      for (Row row : rows) {
        boolean match = matches(row, q);
        row.row.setVisible(match);
        row.row.setManaged(match);
        if (match) {
          visible++;
          if (selected.contains(row.id)) {
            visibleSelected++;
          }
        }
        applySwatchStyle(row, selected.contains(row.id));
      }
      boolean allVisibleOn = visible > 0 && visibleSelected == visible;
      applyCheckboxStyle(selectVisibleBox, selectVisibleCheck, allVisibleOn);
      selectVisibleLabel.setTextFill(
          allVisibleOn ? AppTheme.canvas().axisInk() : AppTheme.chrome().muted());
      selectedCount.setText(selected.size() + " selected");
      selectedCount.setTextFill(AppTheme.chrome().muted());
      confirm.setDisable(selected.isEmpty());
    };

    LiveSession liveSession = new LiveSession(
        dialog, hint, list, rows, rowById, selected, syncUi, otherId);

    buildCheckboxChrome(selectVisibleBox, selectVisibleCheck, selectVisiblePane);
    selectVisiblePane.setOnMouseClicked(e -> {
      List<String> visibleIds = visibleIds(rows, search.getText());
      boolean allOn = !visibleIds.isEmpty() && selected.containsAll(visibleIds);
      if (allOn) {
        selected.removeAll(visibleIds);
      } else {
        selected.addAll(visibleIds);
      }
      syncUi.run();
    });

    List<Node> headerNodes = new ArrayList<>();
    headerNodes.add(selectVisiblePane);
    headerNodes.add(selectVisibleLabel);
    Region headerSpacer = new Region();
    HBox.setHgrow(headerSpacer, Priority.ALWAYS);
    headerNodes.add(headerSpacer);
    for (HeaderAction headerAction : config.headerActions()) {
      Button actionButton = AppDialog.secondaryButton(headerAction.label());
      if (headerAction.tooltip() != null && !headerAction.tooltip().isBlank()) {
        Tooltip.install(actionButton, new Tooltip(headerAction.tooltip()));
      }
      actionButton.setOnAction(e -> {
        List<String> visibleIds = visibleIds(rows, search.getText());
        Set<String> next = headerAction.action().apply(
            new HeaderActionContext(
                Set.copyOf(selected),
                List.copyOf(visibleIds),
                normalizeQuery(search.getText())));
        if (next != null) {
          selected.clear();
          selected.addAll(next);
          syncUi.run();
        }
      });
      headerNodes.add(actionButton);
    }

    for (Item item : items) {
      Row row = buildRow(item);
      row.row.setOnMouseClicked(e -> {
        if (!selected.add(item.id())) {
          selected.remove(item.id());
        }
        syncUi.run();
        e.consume();
      });
      rows.add(row);
      rowById.put(item.id(), row);
      list.getChildren().add(row.row);
    }

    search.textProperty().addListener((obs, o, n) -> syncUi.run());
    syncUi.run();

    ScrollPane scroll = new ScrollPane(list);
    scroll.setFitToWidth(true);
    scroll.setPrefHeight(360);
    scroll.setStyle(
        "-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");
    VBox.setVgrow(scroll, Priority.ALWAYS);

    final Outcome[] chosen = new Outcome[1];
    cancel.setOnAction(e -> dialog.close());
    confirm.setOnAction(e -> {
      if (selected.isEmpty()) {
        return;
      }
      chosen[0] = new Outcome(Set.copyOf(selected));
      dialog.close();
    });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(8, selectedCount, spacer, cancel, confirm);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    HBox header = new HBox(6);
    header.getChildren().addAll(headerNodes);
    header.setAlignment(Pos.CENTER_LEFT);

    VBox root = new VBox(10, title, hint, search, header, scroll, buttons);
    root.setPadding(new Insets(16));
    root.setStyle(AppDialog.PANEL_STYLE);
    dialog.setScene(new Scene(root, 480, 560));
    dialog.setOnShown(e -> {
      if (liveBinder != null) {
        liveBinder.accept(liveSession);
      }
    });
    dialog.setOnHidden(e -> liveSession.markClosed());
    dialog.showAndWait();
    return Optional.ofNullable(chosen[0]);
  }

  private static String normalizeQuery(String text) {
    return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean matches(Row row, String q) {
    if (q.isEmpty()) {
      return true;
    }
    return row.searchText.toLowerCase(Locale.ROOT).contains(q);
  }

  private static List<String> visibleIds(List<Row> rows, String searchText) {
    String q = normalizeQuery(searchText);
    List<String> visibleIds = new ArrayList<>();
    for (Row row : rows) {
      if (matches(row, q)) {
        visibleIds.add(row.id);
      }
    }
    return visibleIds;
  }

  private static void buildCheckboxChrome(Region box, Polyline check, StackPane pane) {
    box.setMinSize(12, 10);
    box.setPrefSize(12, 10);
    box.setMaxSize(12, 10);
    check.getPoints().setAll(2.4, 5.5, 5.0, 7.8, 9.8, 2.2);
    check.setStrokeWidth(1.6);
    check.setFill(null);
    check.setMouseTransparent(true);
    pane.getChildren().setAll(box, check);
    pane.setMinSize(12, 10);
    pane.setPrefSize(12, 10);
    pane.setMaxSize(12, 10);
    pane.setCursor(Cursor.HAND);
    pane.setAlignment(Pos.CENTER);
    Tooltip.install(pane, new Tooltip("Select / deselect all matching the filter"));
  }

  private static Row buildRow(Item item) {
    Region swatch = new Region();
    swatch.setMinSize(12, 10);
    swatch.setPrefSize(12, 10);
    swatch.setMaxSize(12, 10);
    swatch.setMouseTransparent(true);

    Line strike = new Line(1, 9, 11, 1);
    strike.setStrokeWidth(1.2);
    strike.setMouseTransparent(true);
    strike.setVisible(false);

    StackPane swatchPane = new StackPane(swatch, strike);
    swatchPane.setMinSize(12, 10);
    swatchPane.setPrefSize(12, 10);
    swatchPane.setMaxSize(12, 10);
    swatchPane.setMouseTransparent(true);

    Label label = new Label(item.label());
    label.setStyle("-fx-font-size: 11px;");
    label.setMaxWidth(Double.MAX_VALUE);
    label.setMouseTransparent(true);
    if (item.tooltip() != null && !item.tooltip().isBlank()) {
      Tooltip.install(label, new Tooltip(item.tooltip()));
    }

    HBox row = new HBox(5, swatchPane, label);
    row.setAlignment(Pos.CENTER_LEFT);
    row.setMaxWidth(Double.MAX_VALUE);
    row.setPadding(new Insets(2, 4, 2, 2));
    row.setCursor(Cursor.HAND);
    row.setOnMouseEntered(e -> applyRowHover(row, true));
    row.setOnMouseExited(e -> applyRowHover(row, false));
    HBox.setHgrow(label, Priority.ALWAYS);

    return new Row(item.id(), item.searchText(), item.color(), row, swatch, strike, label);
  }

  private static void applyRowHover(HBox row, boolean hovered) {
    if (hovered) {
      Color hover = AppTheme.chrome().elevated();
      row.setStyle(
          "-fx-background-color: rgba("
              + (int) (hover.getRed() * 255) + ","
              + (int) (hover.getGreen() * 255) + ","
              + (int) (hover.getBlue() * 255) + ",0.85);"
              + "-fx-background-radius: 3;");
    } else {
      row.setStyle("");
    }
  }

  private static void applyCheckboxStyle(Region box, Polyline check, boolean checked) {
    var chrome = AppTheme.chrome();
    var canvas = AppTheme.canvas();
    Color stroke = checked ? canvas.axisInk() : chrome.muted();
    box.setStyle(
        "-fx-background-color: transparent;"
            + "-fx-border-color: " + GeneColors.toHexString(stroke) + ";"
            + "-fx-border-width: 1.2;"
            + "-fx-border-radius: 2;"
            + "-fx-background-radius: 2;");
    check.setStroke(canvas.axisInk());
    check.setVisible(checked);
  }

  private static void applySwatchStyle(Row row, boolean enabled) {
    var chrome = AppTheme.chrome();
    var canvas = AppTheme.canvas();
    Color color = row.color != null ? row.color : Color.GRAY;
    double alpha = enabled ? 0.95 : 0.28;
    String fill = String.format(
        "rgba(%d,%d,%d,%.2f)",
        (int) (color.getRed() * 255),
        (int) (color.getGreen() * 255),
        (int) (color.getBlue() * 255),
        alpha);
    row.swatch.setStyle(
        "-fx-background-color: " + fill + ";"
            + "-fx-background-radius: 2;");
    row.strike.setVisible(!enabled);
    row.strike.setStroke(chrome.muted());
    row.label.setTextFill(enabled ? canvas.axisInk() : chrome.muted());
  }

  private static final class Row {
    final String id;
    final String searchText;
    final Color color;
    final HBox row;
    final Region swatch;
    final Line strike;
    final Label label;

    Row(
        String id,
        String searchText,
        Color color,
        HBox row,
        Region swatch,
        Line strike,
        Label label) {
      this.id = id;
      this.searchText = searchText;
      this.color = color;
      this.row = row;
      this.swatch = swatch;
      this.strike = strike;
      this.label = label;
    }
  }
}

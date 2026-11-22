package org.baseplayer.io.table;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

import org.baseplayer.components.AppDialog;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Preview a table, resolve column mapping (memory → auto-detect → dialog), load rows.
 */
public final class MappedTableOpen {

  private static final int PREVIEW_LINES = 10;

  private MappedTableOpen() {}

  /**
   * Resolve mapping for {@code path} and load data.
   * Uses remembered mapping when headers match; otherwise auto-detect; prompts if still incomplete.
   *
   * @return loaded table, or empty if cancelled / failed validation
   */
  public static Optional<MappedTableData> openWithMapping(
      Window owner,
      Path path,
      String defaultSampleId) {
    if (path == null) {
      return Optional.empty();
    }
    try {
      MappedTableReader.Preview preview = MappedTableReader.preview(path, PREVIEW_LINES);
      String[] fingerprintHeaders = headersForFingerprint(preview, preview.firstLineLooksLikeHeader());
      Optional<MappedTableColumnMapping> remembered =
          MappedTableMappingMemory.recall(fingerprintHeaders, preview.firstLineLooksLikeHeader())
              .or(() -> MappedTableMappingMemory.recall(
                  headersForFingerprint(preview, !preview.firstLineLooksLikeHeader()),
                  !preview.firstLineLooksLikeHeader()));
      MappedTableColumnMapping mapping;
      if (remembered.isPresent()
          && remembered.get().hasRequired()
          && remembered.get().hasPayload()) {
        // Same header layout as a prior file — reuse without prompting.
        mapping = remembered.get();
      } else {
        // First time for this header layout: let the user confirm/adjust columns.
        boolean useHeader = preview.firstLineLooksLikeHeader();
        String[] detectHeaders = headersForFingerprint(preview, useHeader);
        MappedTableColumnMapping initial = MappedTableColumnMapping.autoDetect(detectHeaders);
        if (initial.headerRow() != useHeader) {
          initial = new MappedTableColumnMapping(
              initial.indexes(), initial.startZeroBased(), useHeader);
        }
        Optional<MappedTableColumnMapping> chosen = promptMapping(
            owner,
            path.getFileName() != null ? path.getFileName().toString() : path.toString(),
            preview,
            initial);
        if (chosen.isEmpty()) {
          return Optional.empty();
        }
        mapping = chosen.get();
      }

      if (!mapping.hasRequired() || !mapping.hasPayload()) {
        AppDialog.info(
            owner,
            "Incomplete mapping",
            "Need Chromosome, Start, End, and at least Value or Minor+Major CN.",
            null);
        return Optional.empty();
      }

      MappedTableMappingMemory.remember(headersForFingerprint(preview, mapping.headerRow()), mapping);
      MappedTableData data = MappedTableReader.read(path, mapping, defaultSampleId);
      if (data.getRows().isEmpty()) {
        AppDialog.info(
            owner,
            "No rows",
            "No genomic rows could be parsed from:\n" + path.getFileName(),
            null);
        return Optional.empty();
      }
      return Optional.of(data);
    } catch (Exception ex) {
      AppDialog.info(
          owner,
          "Failed to open table",
          path.getFileName() != null ? path.getFileName().toString() : path.toString(),
          ex.getMessage());
      return Optional.empty();
    }
  }

  private static String[] headersForFingerprint(
      MappedTableReader.Preview preview,
      boolean headerRow) {
    List<String[]> lines = preview.firstLines();
    if (lines == null || lines.isEmpty()) {
      return preview.headers() != null ? preview.headers() : new String[0];
    }
    if (headerRow) {
      return lines.get(0);
    }
    return MappedTableReader.syntheticHeaders(lines.get(0).length);
  }

  /**
   * Re-prompt column mapping using {@code template} as the dialog seed, then reload
   * every table in {@code samples} with the chosen mapping (same roles for all files).
   *
   * @return number of tables successfully remapped, or empty if cancelled / failed validation
   */
  public static Optional<Integer> editMappingForAll(
      Window owner,
      MappedTableData template,
      List<org.baseplayer.samples.Sample> samples) {
    if (template == null || template.getPath() == null || samples == null || samples.isEmpty()) {
      return Optional.empty();
    }
    Path path = template.getPath();
    try {
      MappedTableReader.Preview preview = MappedTableReader.preview(path, PREVIEW_LINES);
      MappedTableColumnMapping initial = template.getMapping() != null
          ? template.getMapping()
          : MappedTableColumnMapping.autoDetect(template.getHeaders());
      Optional<MappedTableColumnMapping> chosen = promptMapping(
          owner,
          "all open tables ("
              + (path.getFileName() != null ? path.getFileName() : path)
              + " as template)",
          preview,
          initial);
      if (chosen.isEmpty()) {
        return Optional.empty();
      }
      MappedTableColumnMapping mapping = chosen.get();
      if (!mapping.hasRequired() || !mapping.hasPayload()) {
        AppDialog.info(
            owner,
            "Incomplete mapping",
            "Need Chromosome, Start, End, and at least Value or Minor+Major CN.",
            null);
        return Optional.empty();
      }
      MappedTableMappingMemory.remember(headersForFingerprint(preview, mapping.headerRow()), mapping);

      int ok = 0;
      StringBuilder errors = new StringBuilder();
      for (org.baseplayer.samples.Sample sample : samples) {
        if (sample == null
            || sample.getDataType() != org.baseplayer.samples.Sample.DataType.TABLE
            || sample.getMappedTable() == null
            || sample.getMappedTable().getPath() == null) {
          continue;
        }
        try {
          MappedTableData data = MappedTableReader.read(
              sample.getMappedTable().getPath(), mapping, sample.getName());
          if (data.getRows().isEmpty()) {
            if (errors.length() > 0) {
              errors.append('\n');
            }
            errors.append(sample.getName()).append(": no rows");
            continue;
          }
          sample.replaceMappedTable(data);
          ok++;
        } catch (Exception ex) {
          if (errors.length() > 0) {
            errors.append('\n');
          }
          errors.append(sample.getName()).append(": ")
              .append(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        }
      }
      if (ok == 0) {
        AppDialog.info(
            owner,
            "Remap failed",
            "No tables could be reloaded with that mapping.",
            errors.length() > 0 ? errors.toString() : null);
        return Optional.empty();
      }
      if (errors.length() > 0) {
        AppDialog.info(
            owner,
            "Partially remapped",
            ok + " table(s) updated; some failed:",
            errors.toString());
      }
      return Optional.of(ok);
    } catch (Exception ex) {
      AppDialog.info(
          owner,
          "Failed to remap tables",
          path.getFileName() != null ? path.getFileName().toString() : path.toString(),
          ex.getMessage());
      return Optional.empty();
    }
  }

  private static Optional<MappedTableColumnMapping> promptMapping(
      Window owner,
      String fileName,
      MappedTableReader.Preview preview,
      MappedTableColumnMapping initial) {
    Dialog<MappedTableColumnMapping> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle("Map table columns");
    dialog.setHeaderText("Assign a role to each column for " + fileName
        + "\n(Chromosome, Start, End required; Value and/or Minor+Major CN for analysis)");
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

    boolean startAsHeader = initial != null
        ? initial.headerRow()
        : preview.firstLineLooksLikeHeader();

    CheckBox headerRowBox = new CheckBox("First row is header");
    headerRowBox.setSelected(startAsHeader);
    CheckBox zeroBased = new CheckBox("Start column is 0-based");
    zeroBased.setSelected(initial != null && initial.startZeroBased());
    HBox options = new HBox(16, headerRowBox, zeroBased);
    options.setAlignment(Pos.CENTER_LEFT);

    Label hint = new Label(
        "If the first preview row looks like data (numbers), uncheck \"First row is header\".");
    hint.setWrapText(true);
    hint.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.75;");

    HBox columnRoles = new HBox(10);
    columnRoles.setAlignment(Pos.TOP_LEFT);
    columnRoles.setPadding(new Insets(4, 0, 4, 0));
    ScrollPane roleScroll = new ScrollPane(columnRoles);
    roleScroll.setFitToHeight(true);
    roleScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    roleScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    roleScroll.setPrefViewportHeight(96);
    roleScroll.setMinHeight(96);

    TableView<String[]> previewTable = new TableView<>();
    previewTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
    previewTable.setPrefHeight(220);
    previewTable.setPlaceholder(new Label("No preview rows"));
    VBox.setVgrow(previewTable, Priority.ALWAYS);

    List<ComboBox<String>> roleCombos = new ArrayList<>();

    Runnable refresh = () -> rebuildDialogContent(
        preview,
        headerRowBox.isSelected(),
        initial,
        columnRoles,
        roleCombos,
        previewTable);

    headerRowBox.selectedProperty().addListener((obs, was, is) -> refresh.run());
    refresh.run();

    Label previewLabel = new Label("First " + PREVIEW_LINES + " lines from file:");
    VBox root = new VBox(10, options, hint, roleScroll, previewLabel, previewTable);
    root.setPadding(new Insets(12));
    root.setPrefWidth(860);
    root.setPrefHeight(480);
    dialog.getDialogPane().setContent(root);
    dialog.getDialogPane().setPrefWidth(900);
    dialog.getDialogPane().setPrefHeight(560);

    javafx.scene.Node okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
    if (okButton != null) {
      okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
        MappedTableColumnMapping draft = buildFromRoleCombos(
            roleCombos, zeroBased.isSelected(), headerRowBox.isSelected());
        if (!draft.hasRequired() || !draft.hasPayload()) {
          event.consume();
          AppDialog.info(
              owner,
              "Incomplete mapping",
              "Need Chromosome, Start, End, and at least Value or Minor+Major CN.",
              null);
        }
      });
    }

    dialog.setResultConverter(btn -> {
      if (btn != ButtonType.OK) {
        return null;
      }
      MappedTableColumnMapping mapping = buildFromRoleCombos(
          roleCombos, zeroBased.isSelected(), headerRowBox.isSelected());
      return mapping.hasRequired() && mapping.hasPayload() ? mapping : null;
    });
    return dialog.showAndWait();
  }

  private static void rebuildDialogContent(
      MappedTableReader.Preview preview,
      boolean headerRow,
      MappedTableColumnMapping initial,
      HBox columnRoles,
      List<ComboBox<String>> roleCombos,
      TableView<String[]> previewTable) {
    List<String[]> lines = preview.firstLines() != null ? preview.firstLines() : List.of();
    int colCount = 0;
    for (String[] line : lines) {
      if (line != null) {
        colCount = Math.max(colCount, line.length);
      }
    }
    if (colCount == 0 && preview.headers() != null) {
      colCount = preview.headers().length;
    }

    String[] headers;
    if (headerRow && !lines.isEmpty()) {
      headers = pad(lines.get(0), colCount);
    } else if (!lines.isEmpty()) {
      headers = MappedTableReader.syntheticHeaders(colCount);
    } else {
      headers = preview.headers() != null ? preview.headers() : new String[0];
    }

    // Preserve current role picks across header-toggle refreshes when possible.
    EnumMap<MappedTableColumnRole, Integer> previous = currentRoleIndexes(roleCombos);
    if (previous.isEmpty() && initial != null) {
      previous.putAll(initial.indexes());
    }

    MappedTableColumnMapping auto = MappedTableColumnMapping.autoDetect(headers);
    columnRoles.getChildren().clear();
    roleCombos.clear();

    List<String> roleItems = roleComboItems();
    for (int c = 0; c < colCount; c++) {
      String title = c < headers.length && headers[c] != null && !headers[c].isBlank()
          ? headers[c]
          : ("Col " + (c + 1));
      Label name = new Label(title);
      name.setWrapText(true);
      name.setMaxWidth(140);
      name.setStyle("-fx-font-weight: bold;");

      ComboBox<String> combo = new ComboBox<>();
      combo.getItems().setAll(roleItems);
      combo.setMaxWidth(140);
      combo.setPrefWidth(140);

      MappedTableColumnRole preselected = roleForColumn(previous, auto, c);
      if (preselected != null) {
        combo.getSelectionModel().select(roleItems.indexOf(preselected.label()));
      } else {
        combo.getSelectionModel().select(0);
      }

      // Keep roles unique: clearing another combo that already has this role.
      final int colIndex = c;
      combo.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
        if (newVal == null || "(none)".equals(newVal)) {
          return;
        }
        for (int i = 0; i < roleCombos.size(); i++) {
          if (i == colIndex) {
            continue;
          }
          ComboBox<String> other = roleCombos.get(i);
          if (newVal.equals(other.getSelectionModel().getSelectedItem())) {
            other.getSelectionModel().select(0);
          }
        }
      });

      VBox colBox = new VBox(4, name, combo);
      colBox.setAlignment(Pos.TOP_CENTER);
      colBox.setMinWidth(120);
      colBox.setPrefWidth(140);
      columnRoles.getChildren().add(colBox);
      roleCombos.add(combo);
    }

    previewTable.getColumns().clear();
    for (int c = 0; c < colCount; c++) {
      final int col = c;
      String title = c < headers.length && headers[c] != null && !headers[c].isBlank()
          ? headers[c]
          : ("Col " + (c + 1));
      TableColumn<String[], String> tc = new TableColumn<>(title);
      tc.setPrefWidth(110);
      tc.setCellValueFactory(cd -> {
        String[] row = cd.getValue();
        String v = row != null && col < row.length && row[col] != null ? row[col] : "";
        return new SimpleStringProperty(v);
      });
      previewTable.getColumns().add(tc);
    }
    // Always show the raw first lines so a missing/extra header is obvious.
    List<String[]> paddedRows = new ArrayList<>();
    for (String[] row : lines) {
      paddedRows.add(pad(row, colCount));
    }
    previewTable.setItems(FXCollections.observableArrayList(paddedRows));
  }

  private static EnumMap<MappedTableColumnRole, Integer> currentRoleIndexes(
      List<ComboBox<String>> roleCombos) {
    EnumMap<MappedTableColumnRole, Integer> map = new EnumMap<>(MappedTableColumnRole.class);
    for (int i = 0; i < roleCombos.size(); i++) {
      MappedTableColumnRole role = roleFromLabel(roleCombos.get(i).getSelectionModel().getSelectedItem());
      if (role != null && !map.containsKey(role)) {
        map.put(role, i);
      }
    }
    return map;
  }

  private static MappedTableColumnRole roleForColumn(
      EnumMap<MappedTableColumnRole, Integer> previous,
      MappedTableColumnMapping auto,
      int column) {
    for (var e : previous.entrySet()) {
      if (e.getValue() != null && e.getValue() == column) {
        return e.getKey();
      }
    }
    if (auto != null) {
      for (var e : auto.indexes().entrySet()) {
        if (e.getValue() != null && e.getValue() == column) {
          return e.getKey();
        }
      }
    }
    return null;
  }

  private static List<String> roleComboItems() {
    List<String> items = new ArrayList<>();
    items.add("(none)");
    for (MappedTableColumnRole role : MappedTableColumnRole.values()) {
      items.add(role.label() + (role.required() ? " *" : ""));
    }
    return items;
  }

  private static MappedTableColumnRole roleFromLabel(String selected) {
    if (selected == null || "(none)".equals(selected)) {
      return null;
    }
    String bare = selected.endsWith(" *") ? selected.substring(0, selected.length() - 2) : selected;
    for (MappedTableColumnRole role : MappedTableColumnRole.values()) {
      if (role.label().equals(bare)) {
        return role;
      }
    }
    return null;
  }

  private static MappedTableColumnMapping buildFromRoleCombos(
      List<ComboBox<String>> roleCombos,
      boolean zeroBased,
      boolean headerRow) {
    EnumMap<MappedTableColumnRole, Integer> map = new EnumMap<>(MappedTableColumnRole.class);
    for (int i = 0; i < roleCombos.size(); i++) {
      MappedTableColumnRole role = roleFromLabel(roleCombos.get(i).getSelectionModel().getSelectedItem());
      if (role != null && !map.containsKey(role)) {
        map.put(role, i);
      }
    }
    return new MappedTableColumnMapping(map, zeroBased, headerRow);
  }

  private static String[] pad(String[] row, int colCount) {
    String[] out = new String[colCount];
    for (int i = 0; i < colCount; i++) {
      out[i] = row != null && i < row.length && row[i] != null ? row[i] : "";
    }
    return out;
  }
}

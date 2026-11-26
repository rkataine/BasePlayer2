package org.baseplayer.io.table;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.AnnotationLoader;
import org.baseplayer.components.AppDialog;
import org.baseplayer.components.MatrixSampleMappingDialog;
import org.baseplayer.genome.gene.GeneLocation;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.ChromosomeNames;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import org.baseplayer.components.ColoredToggle;
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
 * Open a wide matrix file (gene/region keys + sample columns) as one
 * {@link MappedTableData} per sample column.
 */
public final class MatrixTableOpen {

  private static final int PREVIEW_LINES = 8;
  private static final int CONFIRM_SAMPLE_THRESHOLD = 200;
  private static final String ATTACH_MAP_EXISTING =
      "Map existing only — attach matched columns; skip unmatched";
  private static final String ATTACH_MERGE =
      "Merge — attach matched columns and add unmatched as new samples";

  /** {@code chr1:100-200}, {@code 1:100-200}, optional commas in coords. */
  private static final Pattern REGION_PATTERN = Pattern.compile(
      "^\\s*(?:chr)?([^:\\s]+)\\s*:\\s*([0-9,]+)\\s*[-–]\\s*([0-9,]+)\\s*$",
      Pattern.CASE_INSENSITIVE);

  private MatrixTableOpen() {}

  public record MatrixOpenSpec(
      MatrixKeyMode mode,
      boolean headerRow,
      boolean startZeroBased,
      int keyColumnStart,
      int firstSampleColumn,
      /** Null when no tracks were open (treated as {@link MatrixSampleMappingDialog.Policy#MERGE}). */
      MatrixSampleMappingDialog.Policy sampleMappingPolicy) {

    public MatrixOpenSpec {
      if (mode == null) {
        mode = MatrixKeyMode.GENE_ID;
      }
      if (keyColumnStart < 0) {
        keyColumnStart = 0;
      }
      if (firstSampleColumn < keyColumnStart + mode.keyColumnCount()) {
        firstSampleColumn = keyColumnStart + mode.keyColumnCount();
      }
    }

    public MatrixSampleMappingDialog.Policy mappingPolicyOrMerge() {
      return sampleMappingPolicy != null
          ? sampleMappingPolicy
          : MatrixSampleMappingDialog.Policy.MERGE;
    }
  }

  public record MatrixOpenResult(
      List<MappedTableData> tables,
      int rowCount,
      int sampleCount,
      int unresolvedKeys,
      String summary) {}

  /** Locus + per-sample values for one matrix row (package for tests). */
  public record MatrixRow(
      String unitId,
      String chromosome,
      long start1Based,
      long end1Based,
      float[] values) {}

  /**
   * FX-thread: preview + mapping dialog + confirm sample count.
   * Does not parse the full file (call {@link #load} on a worker thread).
   */
  public static Optional<MatrixOpenSpec> prompt(Window owner, Path path) {
    if (path == null) {
      return Optional.empty();
    }
    try {
      MappedTableReader.Preview preview = MappedTableReader.preview(path, PREVIEW_LINES);
      MatrixOpenSpec initial = autoHint(preview);
      Optional<MatrixOpenSpec> chosen = promptSpec(
          owner,
          path.getFileName() != null ? path.getFileName().toString() : path.toString(),
          preview,
          initial);
      if (chosen.isEmpty()) {
        return Optional.empty();
      }
      MatrixOpenSpec spec = chosen.get();
      if (spec.mode() == MatrixKeyMode.GENE_ID && !AnnotationData.isGenesLoaded()) {
        AppDialog.info(
            owner,
            "Gene annotation required",
            "Gene ID matrices need a loaded gene annotation (GFF) to resolve ENSG / symbols.",
            null);
        return Optional.empty();
      }
      int colCount = preview.headers() != null ? preview.headers().length : 0;
      int sampleCount = Math.max(0, colCount - spec.firstSampleColumn());
      if (sampleCount <= 0) {
        AppDialog.info(
            owner,
            "No sample columns",
            "Need at least one sample column after the key columns.",
            null);
        return Optional.empty();
      }
      if (sampleCount > CONFIRM_SAMPLE_THRESHOLD) {
        boolean ok = AppDialog.confirm(
            owner,
            "Open matrix file",
            "Open " + sampleCount + " sample tracks?",
            "Each sample column becomes its own track.\n"
                + sampleCount + " columns after key columns in:\n"
                + (path.getFileName() != null ? path.getFileName() : path));
        if (!ok) {
          return Optional.empty();
        }
      }
      return Optional.of(spec);
    } catch (Exception ex) {
      AppDialog.info(
          owner,
          "Failed to open matrix",
          path.getFileName() != null ? path.getFileName().toString() : path.toString(),
          ex.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Worker-thread safe: parse matrix and build compact per-sample tables
   * (shared loci + float[] values, interval-bar draw mode).
   */
  public static MatrixOpenResult load(
      Path path,
      MatrixOpenSpec spec,
      Function<String, GeneLocation> geneResolver) throws IOException {
    if (path == null || spec == null) {
      throw new IllegalArgumentException("path and spec required");
    }
    Function<String, GeneLocation> resolver = geneResolver != null
        ? geneResolver
        : AnnotationData::getGeneLocation;
    ParsedMatrix parsed = parse(path, spec, resolver);
    if (parsed.sampleHeaders().length == 0) {
      throw new IOException("No sample columns after key columns");
    }
    if (parsed.rows().isEmpty()) {
      throw new IOException(
          "No resolvable matrix rows"
              + (parsed.unresolvedKeys() > 0
                  ? " (" + parsed.unresolvedKeys() + " key(s) unresolved)"
                  : ""));
    }
    List<MappedTableData> tables = materializeTables(path, parsed);
    String summary = String.format(
        Locale.ROOT,
        "%d rows · %d samples · %d unresolved keys",
        parsed.rows().size(),
        tables.size(),
        parsed.unresolvedKeys());
    return new MatrixOpenResult(
        tables, parsed.rows().size(), tables.size(), parsed.unresolvedKeys(), summary);
  }

  /** Guess mode from preview headers. */
  static MatrixOpenSpec autoHint(MappedTableReader.Preview preview) {
    boolean headerRow = preview != null && preview.firstLineLooksLikeHeader();
    String[] headers = preview != null ? preview.headers() : null;
    if (headers == null || headers.length == 0) {
      return new MatrixOpenSpec(MatrixKeyMode.GENE_ID, true, false, 0, 1, null);
    }
    MatrixKeyMode mode = MatrixKeyMode.GENE_ID;
    int keyStart = 0;
    if (headers.length >= 3
        && looksLikeChrom(headers[0])
        && looksLikeStartEnd(headers[1])
        && looksLikeStartEnd(headers[2])) {
      mode = MatrixKeyMode.CHROM_START_END;
    } else if (headers.length >= 1 && looksLikeRegionHeader(headers[0])) {
      mode = MatrixKeyMode.REGION;
    } else if (headers.length >= 1 && looksLikeGeneHeader(headers[0])) {
      mode = MatrixKeyMode.GENE_ID;
    }
    int firstSample = keyStart + mode.keyColumnCount();
    return new MatrixOpenSpec(mode, headerRow, false, keyStart, firstSample, null);
  }

  private static boolean looksLikeChrom(String h) {
    String n = normalizeHeader(h);
    return n.equals("chrom") || n.equals("chr") || n.equals("chromosome") || n.equals("seqnames");
  }

  private static boolean looksLikeStartEnd(String h) {
    String n = normalizeHeader(h);
    return n.equals("start") || n.equals("end") || n.equals("stop")
        || n.equals("chromstart") || n.equals("chromend")
        || n.equals("txstart") || n.equals("txend");
  }

  private static boolean looksLikeRegionHeader(String h) {
    String n = normalizeHeader(h);
    return n.equals("region") || n.equals("locus") || n.equals("interval") || n.equals("pos");
  }

  private static boolean looksLikeGeneHeader(String h) {
    String n = normalizeHeader(h);
    return n.equals("gene") || n.equals("geneid") || n.equals("gene_id")
        || n.equals("ensembl") || n.equals("ensg") || n.equals("symbol")
        || n.equals("genename") || n.equals("hgnc")
        || n.startsWith("ensg");
  }

  private static String normalizeHeader(String h) {
    if (h == null) {
      return "";
    }
    return h.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "");
  }

  /** Roles available in the matrix column ComboBoxes. */
  private enum MatrixColumnRole {
    GENE_ID("Gene ID"),
    CHROM("Chromosome"),
    START("Start"),
    END("End"),
    REGION("Region (chr:start-end)"),
    SAMPLE("Sample");

    private final String label;

    MatrixColumnRole(String label) {
      this.label = label;
    }

    String label() {
      return label;
    }

    boolean unique() {
      return this != SAMPLE;
    }
  }

  private static Optional<MatrixOpenSpec> promptSpec(
      Window owner,
      String fileName,
      MappedTableReader.Preview preview,
      MatrixOpenSpec initial) {
    Dialog<MatrixOpenSpec> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle("Open matrix file");
    dialog.setHeaderText("Assign a role to each column for " + fileName
        + "\n(Key columns + Sample for each value column; matched to open tracks by name)");
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

    ColoredToggle headerRowBox = new ColoredToggle("First row is header");
    headerRowBox.setSelected(initial.headerRow());
    ColoredToggle zeroBased = new ColoredToggle("Start is 0-based");
    zeroBased.setSelected(initial.startZeroBased());
    HBox options = new HBox(16, headerRowBox, zeroBased);
    options.setAlignment(Pos.CENTER_LEFT);

    Label hint = new Label(
        "Mark key columns (Gene ID, Chrom/Start/End, or Region), then Sample on value columns.");
    hint.setWrapText(true);
    hint.setStyle("-fx-opacity: 0.75;");

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

    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    boolean hasTracks = registry != null && !registry.getSampleTracks().isEmpty();
    Label matchHint = new Label();
    matchHint.setWrapText(true);
    matchHint.setStyle("-fx-opacity: 0.85;");
    Label attachLabel = new Label("Attach to open samples");
    ComboBox<String> attachPolicyBox = new ComboBox<>();
    attachPolicyBox.getItems().setAll(
        ATTACH_MAP_EXISTING,
        ATTACH_MERGE);
    attachPolicyBox.getSelectionModel().select(ATTACH_MERGE);
    attachPolicyBox.setMaxWidth(Double.MAX_VALUE);
    HBox attachRow = new HBox(10, attachLabel, attachPolicyBox);
    attachRow.setAlignment(Pos.CENTER_LEFT);
    HBox.setHgrow(attachPolicyBox, Priority.ALWAYS);
    VBox mappingBox = new VBox(6, attachRow, matchHint);
    mappingBox.setVisible(hasTracks);
    mappingBox.setManaged(hasTracks);

    Runnable refreshMatchHint = () -> {
      if (!hasTracks) {
        return;
      }
      String[] headers = currentHeaders(preview, headerRowBox.isSelected(), roleCombos.size());
      int matched = 0;
      int unmatched = 0;
      List<String> examplesHit = new ArrayList<>();
      List<String> examplesNew = new ArrayList<>();
      for (int i = 0; i < roleCombos.size(); i++) {
        if (matrixRoleFromLabel(roleCombos.get(i).getSelectionModel().getSelectedItem())
            != MatrixColumnRole.SAMPLE) {
          continue;
        }
        String name = i < headers.length && headers[i] != null && !headers[i].isBlank()
            ? headers[i].trim()
            : ("Sample" + (i + 1));
        SampleTrack hit = registry.findTrackMatchingName(name);
        if (hit != null) {
          matched++;
          if (examplesHit.size() < 2) {
            examplesHit.add(name + " → " + hit.getDisplayName());
          }
        } else {
          unmatched++;
          if (examplesNew.size() < 2) {
            examplesNew.add(name);
          }
        }
      }
      StringBuilder mh = new StringBuilder();
      mh.append(matched).append(" match existing, ")
          .append(unmatched).append(" unmatched");
      if (!examplesHit.isEmpty()) {
        mh.append("\nMatched e.g. ").append(String.join("; ", examplesHit));
      }
      if (!examplesNew.isEmpty()) {
        mh.append("\nUnmatched e.g. ").append(String.join(", ", examplesNew));
      }
      if (matched == 0) {
        mh.append("\nMap existing only would attach nothing.");
      }
      matchHint.setText(mh.toString());
    };

    Runnable refresh = () -> {
      rebuildMatrixDialogContent(
          preview,
          headerRowBox.isSelected(),
          initial,
          columnRoles,
          roleCombos,
          previewTable,
          zeroBased,
          refreshMatchHint);
      refreshMatchHint.run();
    };
    headerRowBox.selectedProperty().addListener((obs, was, is) -> refresh.run());
    refresh.run();

    Label previewLabel = new Label("First " + PREVIEW_LINES + " lines from file:");
    VBox root = new VBox(10,
        options,
        hint,
        roleScroll,
        mappingBox,
        previewLabel,
        previewTable);
    root.setPadding(new Insets(12));
    root.setPrefWidth(860);
    root.setPrefHeight(520);
    dialog.getDialogPane().setContent(root);
    dialog.getDialogPane().setPrefWidth(900);
    dialog.getDialogPane().setPrefHeight(600);

    javafx.scene.Node okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
    if (okButton != null) {
      okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
        MatrixOpenSpec draft = buildSpecFromRoleCombos(
            roleCombos,
            headerRowBox.isSelected(),
            zeroBased.isSelected(),
            null);
        if (draft == null) {
          event.consume();
          AppDialog.info(
              owner,
              "Incomplete mapping",
              "Need a complete key (Gene ID, Region, or Chrom+Start+End) "
                  + "and at least one Sample column.",
              null);
        }
      });
    }

    dialog.setResultConverter(btn -> {
      if (btn != ButtonType.OK) {
        return null;
      }
      MatrixSampleMappingDialog.Policy policy = null;
      if (hasTracks) {
        policy = ATTACH_MAP_EXISTING.equals(attachPolicyBox.getValue())
            ? MatrixSampleMappingDialog.Policy.MAP_EXISTING
            : MatrixSampleMappingDialog.Policy.MERGE;
      }
      return buildSpecFromRoleCombos(
          roleCombos,
          headerRowBox.isSelected(),
          zeroBased.isSelected(),
          policy);
    });
    return dialog.showAndWait();
  }

  private static void rebuildMatrixDialogContent(
      MappedTableReader.Preview preview,
      boolean headerRow,
      MatrixOpenSpec initial,
      HBox columnRoles,
      List<ComboBox<String>> roleCombos,
      TableView<String[]> previewTable,
      ColoredToggle zeroBased,
      Runnable onRoleChanged) {
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

    String[] headers = currentHeaders(preview, headerRow, colCount);

    EnumMap<MatrixColumnRole, Integer> previous = currentMatrixRoleIndexes(roleCombos);
    if (previous.isEmpty() && initial != null) {
      previous.putAll(seedRolesFromSpec(initial, colCount));
    }

    columnRoles.getChildren().clear();
    roleCombos.clear();

    List<String> roleItems = matrixRoleComboItems();
    boolean hasChromStartEnd = previous.containsKey(MatrixColumnRole.CHROM)
        || previous.containsKey(MatrixColumnRole.START)
        || previous.containsKey(MatrixColumnRole.END);
    zeroBased.setDisable(!hasChromStartEnd
        && !previous.containsKey(MatrixColumnRole.REGION));

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

      MatrixColumnRole preselected = roleForMatrixColumn(previous, c);
      if (preselected != null) {
        combo.getSelectionModel().select(roleItems.indexOf(preselected.label()));
      } else {
        combo.getSelectionModel().select(0);
      }

      final int colIndex = c;
      combo.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
        MatrixColumnRole role = matrixRoleFromLabel(newVal);
        if (role != null && role.unique()) {
          for (int i = 0; i < roleCombos.size(); i++) {
            if (i == colIndex) {
              continue;
            }
            ComboBox<String> other = roleCombos.get(i);
            if (newVal != null && newVal.equals(other.getSelectionModel().getSelectedItem())) {
              other.getSelectionModel().select(0);
            }
          }
        }
        boolean needZero = false;
        for (ComboBox<String> box : roleCombos) {
          MatrixColumnRole r = matrixRoleFromLabel(box.getSelectionModel().getSelectedItem());
          if (r == MatrixColumnRole.CHROM || r == MatrixColumnRole.START
              || r == MatrixColumnRole.END || r == MatrixColumnRole.REGION) {
            needZero = true;
            break;
          }
        }
        zeroBased.setDisable(!needZero);
        if (onRoleChanged != null) {
          onRoleChanged.run();
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
    List<String[]> paddedRows = new ArrayList<>();
    for (String[] row : lines) {
      paddedRows.add(padRow(row, colCount));
    }
    previewTable.setItems(FXCollections.observableArrayList(paddedRows));
  }

  private static String[] currentHeaders(
      MappedTableReader.Preview preview,
      boolean headerRow,
      int colCount) {
    List<String[]> lines = preview != null && preview.firstLines() != null
        ? preview.firstLines()
        : List.of();
    int cols = colCount;
    if (cols <= 0) {
      for (String[] line : lines) {
        if (line != null) {
          cols = Math.max(cols, line.length);
        }
      }
      if (cols == 0 && preview != null && preview.headers() != null) {
        cols = preview.headers().length;
      }
    }
    if (headerRow && !lines.isEmpty()) {
      return padRow(lines.get(0), cols);
    }
    if (!lines.isEmpty()) {
      return MappedTableReader.syntheticHeaders(cols);
    }
    return preview != null && preview.headers() != null
        ? padRow(preview.headers(), cols)
        : new String[cols];
  }

  private static EnumMap<MatrixColumnRole, Integer> seedRolesFromSpec(
      MatrixOpenSpec initial,
      int colCount) {
    EnumMap<MatrixColumnRole, Integer> map = new EnumMap<>(MatrixColumnRole.class);
    if (initial == null || colCount <= 0) {
      return map;
    }
    int keyStart = Math.min(initial.keyColumnStart(), Math.max(0, colCount - 1));
    switch (initial.mode()) {
      case GENE_ID -> map.put(MatrixColumnRole.GENE_ID, keyStart);
      case REGION -> map.put(MatrixColumnRole.REGION, keyStart);
      case CHROM_START_END -> {
        if (keyStart + 2 < colCount) {
          map.put(MatrixColumnRole.CHROM, keyStart);
          map.put(MatrixColumnRole.START, keyStart + 1);
          map.put(MatrixColumnRole.END, keyStart + 2);
        }
      }
      default -> { }
    }
    int firstSample = Math.max(initial.firstSampleColumn(), keyStart + initial.mode().keyColumnCount());
    for (int i = firstSample; i < colCount; i++) {
      // SAMPLE is not unique; only store first index for seeding helper lookups —
      // actual Sample roles applied per-column below via roleForMatrixColumn.
      map.putIfAbsent(MatrixColumnRole.SAMPLE, i);
    }
    return map;
  }

  private static EnumMap<MatrixColumnRole, Integer> currentMatrixRoleIndexes(
      List<ComboBox<String>> roleCombos) {
    EnumMap<MatrixColumnRole, Integer> map = new EnumMap<>(MatrixColumnRole.class);
    for (int i = 0; i < roleCombos.size(); i++) {
      MatrixColumnRole role = matrixRoleFromLabel(
          roleCombos.get(i).getSelectionModel().getSelectedItem());
      if (role == null) {
        continue;
      }
      if (role.unique()) {
        map.putIfAbsent(role, i);
      } else {
        map.putIfAbsent(role, i);
      }
    }
    return map;
  }

  private static MatrixColumnRole roleForMatrixColumn(
      EnumMap<MatrixColumnRole, Integer> previous,
      int column) {
    for (var e : previous.entrySet()) {
      if (e.getKey() == MatrixColumnRole.SAMPLE) {
        continue;
      }
      if (e.getValue() != null && e.getValue() == column) {
        return e.getKey();
      }
    }
    Integer firstSample = previous.get(MatrixColumnRole.SAMPLE);
    if (firstSample != null && column >= firstSample) {
      return MatrixColumnRole.SAMPLE;
    }
    return null;
  }

  private static List<String> matrixRoleComboItems() {
    List<String> items = new ArrayList<>();
    items.add("(none)");
    for (MatrixColumnRole role : MatrixColumnRole.values()) {
      items.add(role.label());
    }
    return items;
  }

  private static MatrixColumnRole matrixRoleFromLabel(String selected) {
    if (selected == null || "(none)".equals(selected)) {
      return null;
    }
    for (MatrixColumnRole role : MatrixColumnRole.values()) {
      if (role.label().equals(selected)) {
        return role;
      }
    }
    return null;
  }

  /**
   * Build {@link MatrixOpenSpec} from ComboBox roles, or null if incomplete.
   */
  static MatrixOpenSpec buildSpecFromRoleCombos(
      List<ComboBox<String>> roleCombos,
      boolean headerRow,
      boolean startZeroBased,
      MatrixSampleMappingDialog.Policy policy) {
    EnumMap<MatrixColumnRole, Integer> unique = new EnumMap<>(MatrixColumnRole.class);
    List<Integer> sampleIndexes = new ArrayList<>();
    for (int i = 0; i < roleCombos.size(); i++) {
      MatrixColumnRole role = matrixRoleFromLabel(
          roleCombos.get(i).getSelectionModel().getSelectedItem());
      if (role == null) {
        continue;
      }
      if (role == MatrixColumnRole.SAMPLE) {
        sampleIndexes.add(i);
      } else if (!unique.containsKey(role)) {
        unique.put(role, i);
      }
    }
    if (sampleIndexes.isEmpty()) {
      return null;
    }

    MatrixKeyMode mode;
    int keyStart;
    if (unique.containsKey(MatrixColumnRole.GENE_ID)
        && !unique.containsKey(MatrixColumnRole.REGION)
        && !unique.containsKey(MatrixColumnRole.CHROM)) {
      mode = MatrixKeyMode.GENE_ID;
      keyStart = unique.get(MatrixColumnRole.GENE_ID);
    } else if (unique.containsKey(MatrixColumnRole.REGION)
        && !unique.containsKey(MatrixColumnRole.GENE_ID)
        && !unique.containsKey(MatrixColumnRole.CHROM)) {
      mode = MatrixKeyMode.REGION;
      keyStart = unique.get(MatrixColumnRole.REGION);
    } else if (unique.containsKey(MatrixColumnRole.CHROM)
        && unique.containsKey(MatrixColumnRole.START)
        && unique.containsKey(MatrixColumnRole.END)
        && !unique.containsKey(MatrixColumnRole.GENE_ID)
        && !unique.containsKey(MatrixColumnRole.REGION)) {
      int chrom = unique.get(MatrixColumnRole.CHROM);
      int start = unique.get(MatrixColumnRole.START);
      int end = unique.get(MatrixColumnRole.END);
      if (start != chrom + 1 || end != chrom + 2) {
        return null;
      }
      mode = MatrixKeyMode.CHROM_START_END;
      keyStart = chrom;
    } else {
      return null;
    }

    int firstSample = sampleIndexes.get(0);
    int keyEnd = keyStart + mode.keyColumnCount();
    if (firstSample < keyEnd) {
      return null;
    }
    // Contiguous samples from firstSample to end of file (parser contract).
    for (int i = firstSample; i < roleCombos.size(); i++) {
      if (matrixRoleFromLabel(roleCombos.get(i).getSelectionModel().getSelectedItem())
          != MatrixColumnRole.SAMPLE) {
        return null;
      }
    }
    return new MatrixOpenSpec(mode, headerRow, startZeroBased, keyStart, firstSample, policy);
  }

  private static String[] padRow(String[] row, int colCount) {
    String[] out = new String[colCount];
    for (int i = 0; i < colCount; i++) {
      out[i] = row != null && i < row.length && row[i] != null ? row[i] : "";
    }
    return out;
  }

  /** Parsed matrix body (testable without the dialog). */
  public record ParsedMatrix(
      String[] sampleHeaders,
      List<MatrixRow> rows,
      int unresolvedKeys) {}

  public static ParsedMatrix parse(
      Path path,
      MatrixOpenSpec spec,
      Function<String, GeneLocation> geneResolver) throws IOException {
    if (path == null || spec == null) {
      throw new IllegalArgumentException("path and spec required");
    }
    char delim = MappedTableReader.detectDelimiter(path);
    List<MatrixRow> rows = new ArrayList<>();
    int unresolved = 0;
    String[] sampleHeaders;
    try (BufferedReader reader = MappedTableReader.openTextReader(path)) {
      String first = nextNonComment(reader);
      if (first == null) {
        return new ParsedMatrix(new String[0], List.of(), 0);
      }
      String[] firstCols = split(first, delim);
      String[] headers;
      String dataFirst = null;
      if (spec.headerRow()) {
        headers = firstCols;
      } else {
        headers = MappedTableReader.syntheticHeaders(firstCols.length);
        dataFirst = first;
      }
      int firstSample = Math.min(spec.firstSampleColumn(), headers.length);
      if (firstSample >= headers.length) {
        return new ParsedMatrix(new String[0], List.of(), 0);
      }
      sampleHeaders = new String[headers.length - firstSample];
      for (int i = 0; i < sampleHeaders.length; i++) {
        String h = headers[firstSample + i];
        sampleHeaders[i] = h != null && !h.isBlank() ? h.trim() : ("Sample" + (i + 1));
      }

      if (dataFirst != null) {
        ParseOutcome one = parseDataLine(split(dataFirst, delim), spec, geneResolver, firstSample);
        if (one.row() != null) {
          rows.add(one.row());
        } else if (one.unresolved()) {
          unresolved++;
        }
      }
      String line;
      while ((line = nextNonComment(reader)) != null) {
        ParseOutcome one = parseDataLine(split(line, delim), spec, geneResolver, firstSample);
        if (one.row() != null) {
          rows.add(one.row());
        } else if (one.unresolved()) {
          unresolved++;
        }
      }
    }
    return new ParsedMatrix(sampleHeaders, rows, unresolved);
  }

  private record ParseOutcome(MatrixRow row, boolean unresolved) {}

  private static ParseOutcome parseDataLine(
      String[] cols,
      MatrixOpenSpec spec,
      Function<String, GeneLocation> geneResolver,
      int firstSample) {
    if (cols == null || cols.length <= firstSample) {
      return new ParseOutcome(null, false);
    }
    Optional<ResolvedLocus> locus = resolveLocus(cols, spec, geneResolver);
    if (locus.isEmpty()) {
      return new ParseOutcome(null, true);
    }
    ResolvedLocus loc = locus.get();
    int n = cols.length - firstSample;
    float[] values = new float[n];
    boolean any = false;
    for (int i = 0; i < n; i++) {
      float v = parseOptionalFloat(cols[firstSample + i]);
      values[i] = v;
      if (Float.isFinite(v)) {
        any = true;
      }
    }
    if (!any) {
      return new ParseOutcome(null, false);
    }
    return new ParseOutcome(
        new MatrixRow(loc.unitId(), loc.chrom(), loc.start1Based(), loc.end1Based(), values),
        false);
  }

  private record ResolvedLocus(String unitId, String chrom, long start1Based, long end1Based) {}

  static Optional<ResolvedLocus> resolveLocus(
      String[] cols,
      MatrixOpenSpec spec,
      Function<String, GeneLocation> geneResolver) {
    if (cols == null || spec == null) {
      return Optional.empty();
    }
    int keyStart = spec.keyColumnStart();
    return switch (spec.mode()) {
      case GENE_ID -> {
        String id = cell(cols, keyStart);
        if (id == null || id.isBlank() || geneResolver == null) {
          yield Optional.empty();
        }
        String trimmed = id.trim();
        GeneLocation loc = geneResolver.apply(trimmed);
        if (loc == null || loc.chrom() == null) {
          // Matrix files often use versioned Ensembl ids (ENSG….15).
          String versionless = AnnotationLoader.stripEnsemblVersion(trimmed);
          if (!versionless.equalsIgnoreCase(trimmed)) {
            loc = geneResolver.apply(versionless);
          }
        }
        if (loc == null || loc.chrom() == null) {
          yield Optional.empty();
        }
        yield Optional.of(new ResolvedLocus(
            trimmed,
            ChromosomeNames.strip(loc.chrom()),
            loc.start(),
            loc.end()));
      }
      case CHROM_START_END -> {
        String chrom = cell(cols, keyStart);
        String startRaw = cell(cols, keyStart + 1);
        String endRaw = cell(cols, keyStart + 2);
        yield parseChromStartEnd(chrom, startRaw, endRaw, spec.startZeroBased());
      }
      case REGION -> {
        String raw = cell(cols, keyStart);
        yield parseRegion(raw, spec.startZeroBased())
            .map(l -> new ResolvedLocus(
                raw != null ? raw.trim() : (l.chrom() + ":" + l.start1Based() + "-" + l.end1Based()),
                l.chrom(),
                l.start1Based(),
                l.end1Based()));
      }
    };
  }

  /** Parse {@code chr:start-end} (public for tests). */
  public static Optional<LocusCoords> parseRegionString(String raw, boolean startZeroBased) {
    return parseRegion(raw, startZeroBased).map(l ->
        new LocusCoords(l.chrom(), l.start1Based(), l.end1Based()));
  }

  public record LocusCoords(String chromosome, long start1Based, long end1Based) {}

  private record Locus(String chrom, long start1Based, long end1Based) {}

  private static Optional<Locus> parseRegion(String raw, boolean startZeroBased) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    Matcher m = REGION_PATTERN.matcher(raw.trim());
    if (!m.matches()) {
      return Optional.empty();
    }
    try {
      long start = Long.parseLong(m.group(2).replace(",", ""));
      long end = Long.parseLong(m.group(3).replace(",", ""));
      if (startZeroBased) {
        start = start + 1;
      }
      if (end < start) {
        return Optional.empty();
      }
      return Optional.of(new Locus(ChromosomeNames.strip(m.group(1)), start, end));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private static Optional<ResolvedLocus> parseChromStartEnd(
      String chrom, String startRaw, String endRaw, boolean startZeroBased) {
    if (chrom == null || chrom.isBlank() || startRaw == null || endRaw == null) {
      return Optional.empty();
    }
    try {
      long start = Math.round(Double.parseDouble(startRaw.trim().replace(",", "")));
      long end = Math.round(Double.parseDouble(endRaw.trim().replace(",", "")));
      if (startZeroBased) {
        start = start + 1;
      }
      if (end < start) {
        return Optional.empty();
      }
      String c = ChromosomeNames.strip(chrom.trim());
      return Optional.of(new ResolvedLocus(
          c + ":" + start + "-" + end, c, start, end));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  /** Build one compact {@link MappedTableData} per sample (shared loci + float[]). */
  public static List<MappedTableData> materializeTables(Path path, ParsedMatrix parsed) {
    if (parsed == null || parsed.sampleHeaders() == null || parsed.rows().isEmpty()) {
      return List.of();
    }
    String[] headers = parsed.sampleHeaders();
    int nLoci = parsed.rows().size();
    String[] chrom = new String[nLoci];
    long[] starts = new long[nLoci];
    long[] ends = new long[nLoci];
    String[] unitIds = new String[nLoci];
    for (int i = 0; i < nLoci; i++) {
      MatrixRow mr = parsed.rows().get(i);
      chrom[i] = mr.chromosome();
      starts[i] = mr.start1Based();
      ends[i] = mr.end1Based();
      String rawId = mr.unitId() != null && !mr.unitId().isBlank()
          ? mr.unitId()
          : (mr.chromosome() + ":" + mr.start1Based() + "-" + mr.end1Based());
      // Prefer gene symbol for Signal table / biotype visibility when id resolves.
      org.baseplayer.genome.gene.Gene gene =
          org.baseplayer.analysis.GeneOverlapIndex.resolveByName(rawId);
      if (gene != null && gene.name() != null && !gene.name().isBlank()) {
        unitIds[i] = gene.name();
        chrom[i] = gene.chrom();
        starts[i] = gene.start();
        ends[i] = gene.end();
      } else {
        unitIds[i] = rawId;
      }
    }
    SharedMatrixLoci loci = new SharedMatrixLoci(chrom, starts, ends, unitIds);
    MappedTableColumnMapping mapping = syntheticValueMapping();
    String[] syntheticHeaders = { "chrom", "start", "end", "value" };
    List<MappedTableData> out = new ArrayList<>(headers.length);
    for (int s = 0; s < headers.length; s++) {
      String sampleName = headers[s];
      float[] values = new float[nLoci];
      for (int i = 0; i < nLoci; i++) {
        float[] rowVals = parsed.rows().get(i).values();
        values[i] = rowVals != null && s < rowVals.length ? rowVals[s] : Float.NaN;
      }
      Path samplePath = path != null
          ? path.resolveSibling(
              (path.getFileName() != null ? path.getFileName().toString() : "matrix")
                  + "#" + sanitizeFilePart(sampleName))
          : Path.of(sampleName);
      out.add(MappedTableData.geneMatrixSample(
          samplePath, sampleName, mapping, syntheticHeaders, loci, values));
    }
    return out;
  }

  private static MappedTableColumnMapping syntheticValueMapping() {
    Map<MappedTableColumnRole, Integer> indexes = new EnumMap<>(MappedTableColumnRole.class);
    indexes.put(MappedTableColumnRole.CHROM, 0);
    indexes.put(MappedTableColumnRole.START, 1);
    indexes.put(MappedTableColumnRole.END, 2);
    indexes.put(MappedTableColumnRole.VALUE, 3);
    return new MappedTableColumnMapping(indexes, false, true);
  }

  private static String sanitizeFilePart(String name) {
    if (name == null || name.isBlank()) {
      return "sample";
    }
    return name.replaceAll("[\\\\/\\s]+", "_");
  }

  private static String nextNonComment(BufferedReader reader) throws IOException {
    String line;
    while ((line = reader.readLine()) != null) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
        continue;
      }
      return line;
    }
    return null;
  }

  private static String[] split(String line, char delim) {
    if (delim == '\t') {
      return line.split("\t", -1);
    }
    if (delim == ';') {
      return line.split(";", -1);
    }
    return line.split(",", -1);
  }

  private static String cell(String[] cols, int index) {
    if (cols == null || index < 0 || index >= cols.length) {
      return null;
    }
    return cols[index];
  }

  private static float parseOptionalFloat(String raw) {
    if (raw == null || raw.isBlank() || ".".equals(raw.trim()) || "NA".equalsIgnoreCase(raw.trim())) {
      return Float.NaN;
    }
    try {
      return Float.parseFloat(raw.trim());
    } catch (NumberFormatException ex) {
      return Float.NaN;
    }
  }
}

package org.baseplayer.analysis.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.ComparisonUnit;
import org.baseplayer.analysis.grid.FeatureValueBundle;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.groupstat.GroupComparisonDiffFormat;
import org.baseplayer.analysis.groupstat.GroupStatResult;
import org.baseplayer.analysis.signal.SignalAnnotator;
import org.baseplayer.analysis.signal.SignalChannel;
import org.baseplayer.analysis.signal.SignalEventDirection;
import org.baseplayer.analysis.signal.SignalValueType;
import org.baseplayer.analysis.ThresholdFilter;
import org.baseplayer.controllers.commands.NavigationCommands;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.ChromosomeNames;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MultipleSelectionModel;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Nested group-comparison results: same chrome as variant gene tables
 * ({@code # / Gene / Chr / Pos …}), expandable to per-group values.
 * Headers are sortable (Gene, Chr, Pos, q, Δ).
 */
public final class GroupStatResultTable extends VBox {

  private enum SortCol {
    NAME, CHR, POS, P, Q, DELTA, DIR, N, CHANNEL
  }

  private static final double TOGGLE_SIZE = 18;
  private static final double GUTTER_WIDTH = 16;
  private static final double COL_NUM = 36;
  private static final double COL_NAME = 120;
  private static final double COL_CHR = 48;
  private static final double COL_POS = 130;
  private static final double COL_N = 36;
  private static final double COL_DIR = 64;
  private static final double COL_P = 78;
  private static final double COL_Q = 78;
  private static final double COL_DELTA = 72;
  private static final double COL_HITS = 140;
  private static final double COL_DIFF = 240;
  private String meanTooltip = "Mean over all group samples in the test vector";

  private final ListView<Entry> list = new ListView<>();
  private final ObservableList<Entry> items = FXCollections.observableArrayList();
  private final List<Entry> allEntries = new ArrayList<>();
  private final Label emptyLabel = new Label("Open signal data to annotate");
  private final HBox sortNameHeader;
  private final HBox sortChrHeader;
  private final HBox sortPosHeader;
  private final HBox sortPHeader;
  private final HBox sortQHeader;
  private final HBox sortDeltaHeader;
  private final HBox sortDirHeader;
  private final HBox sortNHeader;
  private final Label sortNameArrow;
  private final Label sortChrArrow;
  private final Label sortPosArrow;
  private final Label sortPArrow;
  private final Label sortQArrow;
  private final Label sortDeltaArrow;
  private final Label sortDirArrow;
  private final Label sortNArrow;
  private final Label unitHeaderLabel = new Label("Gene");
  private final Label pHeaderLabel = new Label("p");
  private final Label qHeaderLabel = new Label("q");
  private final Label nHeaderLabel = new Label("n");
  private final Label dirHeaderLabel = new Label("Dir");
  private final Label hitsHeaderLabel = new Label("Hits");
  private final Label diffHeaderLabel = new Label("Diff");
  private final HBox headerRow;
  private Consumer<GroupStatResult.Row> onGoto;
  private ComparisonUnit unitMode = ComparisonUnit.GENE;
  private AnalysisMode analysisMode = AnalysisMode.SIGNAL;
  private boolean annotationMode;
  private boolean lohAnnotationMode;
  private boolean alleleCnAnnotationMode;
  private GroupStatResult currentResult;
  private final HBox channelHeaderBox = new HBox(0);
  private final List<HBox> sortChannelHeaders = new ArrayList<>();
  private final List<Label> sortChannelArrows = new ArrayList<>();
  private final List<String> sortChannelIds = new ArrayList<>();
  private static final double COL_CHANNEL = 72;
  private double valueColorMin = 0;
  private double valueColorMax = 1;
  /** Per-chromosome color scale for continuous annotation (chrom → [min,max]). */
  private final Map<String, double[]> chromValueRange = new HashMap<>();
  private SortCol sortCol = SortCol.DELTA;
  private boolean sortAscending = false;
  /** Channel id when {@link SortCol#CHANNEL}. */
  private String sortChannelId;
  private String searchQuery = "";

  public GroupStatResultTable() {
    getStyleClass().add("variant-nested-container");
    setSpacing(0);
    VBox.setVgrow(this, Priority.ALWAYS);

    sortNameArrow = sortArrowLabel();
    sortChrArrow = sortArrowLabel();
    sortPosArrow = sortArrowLabel();
    sortPArrow = sortArrowLabel();
    sortQArrow = sortArrowLabel();
    sortDeltaArrow = sortArrowLabel();
    sortDirArrow = sortArrowLabel();
    sortNArrow = sortArrowLabel();
    sortNameHeader = sortableHeader(unitHeaderLabel, COL_NAME, SortCol.NAME, sortNameArrow);
    sortChrHeader = sortableHeader(new Label("Chr"), COL_CHR, SortCol.CHR, sortChrArrow);
    sortPosHeader = sortableHeader(new Label("Pos"), COL_POS, SortCol.POS, sortPosArrow);
    sortPHeader = sortableHeader(pHeaderLabel, COL_P, SortCol.P, sortPArrow);
    Tooltip.install(pHeaderLabel, new Tooltip(P_VALUE_TOOLTIP));
    sortQHeader = sortableHeader(qHeaderLabel, COL_Q, SortCol.Q, sortQArrow);
    Tooltip.install(qHeaderLabel, new Tooltip(Q_VALUE_TOOLTIP));
    Label deltaHeaderLabel = new Label("Δ");
    Tooltip.install(deltaHeaderLabel, new Tooltip(GroupComparisonDiffFormat.DELTA_TOOLTIP));
    sortDeltaHeader = sortableHeader(deltaHeaderLabel, COL_DELTA, SortCol.DELTA, sortDeltaArrow);
    Tooltip.install(dirHeaderLabel, new Tooltip("CNV direction: Gain, Loss, or LOH"));
    sortDirHeader = sortableHeader(dirHeaderLabel, COL_DIR, SortCol.DIR, sortDirArrow);
    Tooltip.install(nHeaderLabel, new Tooltip("Samples with an event on this gene"));
    sortNHeader = sortableHeader(nHeaderLabel, COL_N, SortCol.N, sortNArrow);

    headerRow = buildHeader();
    headerRow.getStyleClass().add("vn-sticky-headers");
    refreshSortHeaderLabels();

    emptyLabel.getStyleClass().add("vn-empty-label");
    emptyLabel.setWrapText(true);

    list.getStyleClass().addAll("vn-thread-list", "variant-nested-scroll");
    list.setItems(items);
    list.setFocusTraversable(false);
    list.setSelectionModel(new NoSelectionModel<>());
    list.setCellFactory(lv -> new GeneCell());
    list.setPlaceholder(emptyLabel);
    VBox.setVgrow(list, Priority.ALWAYS);

    getChildren().addAll(headerRow, list);
  }

  public void setUnitColumnTitle(ComparisonUnit unit) {
    this.unitMode = unit != null ? unit : ComparisonUnit.GENE;
    unitHeaderLabel.setText(switch (unitMode) {
      case GENE -> "Gene";
      case WINDOW -> "Window";
      case NATIVE_FEATURE -> "Feature";
    });
  }

  public void setOnGoto(Consumer<GroupStatResult.Row> onGoto) {
    this.onGoto = onGoto;
  }

  /** Tooltip shown on Mean in expanded group rows (mode-specific score wording). */
  public void setMeanTooltip(String meanTooltip) {
    this.meanTooltip = meanTooltip != null && !meanTooltip.isBlank()
        ? meanTooltip
        : "Mean over all group samples in the test vector";
  }

  public void setResult(GroupStatResult result) {
    currentResult = result;
    analysisMode = result != null && result.mode() != null ? result.mode() : AnalysisMode.SIGNAL;
    boolean wasAnnotation = annotationMode;
    annotationMode = SignalAnnotator.isAnnotationResult(result);
    lohAnnotationMode = annotationMode && SignalAnnotator.isLohAnnotation(result);
    alleleCnAnnotationMode = annotationMode && SignalEventDirection.resultHasAlleleCn(result);
    updateHeaderForMode();
    if (annotationMode && !wasAnnotation) {
      sortCol = SortCol.POS;
      sortAscending = true;
      refreshSortHeaderLabels();
    } else if (!annotationMode && wasAnnotation) {
      sortCol = SortCol.DELTA;
      sortAscending = false;
      refreshSortHeaderLabels();
    }
    chromValueRange.clear();
    if (result == null || result.rows().isEmpty()) {
      allEntries.clear();
      items.clear();
      valueColorMin = 0;
      valueColorMax = 1;
      emptyLabel.setText(
          result != null && result.summary() != null && !result.summary().isBlank()
              ? result.summary()
              : (annotationMode ? "No units pass value thresholds" : "No comparable units"));
      list.setPlaceholder(emptyLabel);
      return;
    }
    List<Entry> next = new ArrayList<>(result.rows().size());
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    Map<String, List<Double>> byChrom = annotationMode ? new HashMap<>() : null;
    for (GroupStatResult.Row row : result.rows()) {
      next.add(new Entry(row));
      if (annotationMode) {
        Double v = SignalAnnotator.annotationValue(row);
        if (v != null && Double.isFinite(v)) {
          min = Math.min(min, v);
          max = Math.max(max, v);
          String chromKey = chromSortKey(row);
          if (!chromKey.isEmpty()) {
            byChrom.computeIfAbsent(chromKey, k -> new ArrayList<>()).add(v);
          }
        }
      }
    }
    if (annotationMode && min <= max && Double.isFinite(min) && Double.isFinite(max)) {
      valueColorMin = min;
      valueColorMax = max == min ? min + 1e-9 : max;
    } else {
      valueColorMin = 0;
      valueColorMax = 1;
    }
    if (byChrom != null) {
      for (Map.Entry<String, List<Double>> e : byChrom.entrySet()) {
        double[] range = SignalEventDirection.chromValueScale(e.getValue());
        if (range != null) {
          chromValueRange.put(e.getKey(), range);
        }
      }
    }
    allEntries.clear();
    allEntries.addAll(next);
    list.setTooltip(new Tooltip(result.summary()));
    applySearchAndSort();
  }

  private void updateHeaderForMode() {
    boolean multiChannel = annotationMode
        && currentResult != null
        && currentResult.hasSignalChannels()
        && currentResult.signalChannels().size() > 1;
    if (!alleleCnAnnotationMode && sortCol == SortCol.DIR) {
      sortCol = SortCol.POS;
      sortAscending = true;
    }
    if ((!annotationMode || !multiChannel) && sortCol == SortCol.CHANNEL) {
      sortCol = annotationMode ? SortCol.POS : SortCol.DELTA;
      sortAscending = annotationMode;
      sortChannelId = null;
    }
    if (!annotationMode && sortCol == SortCol.DIR) {
      sortCol = SortCol.DELTA;
      sortAscending = false;
    }
    rebuildChannelHeaders(multiChannel);
    rebuildHeaderChildren(multiChannel);
    if (annotationMode) {
      qHeaderLabel.setText(multiChannel ? "" : "Value");
      Tooltip.install(qHeaderLabel, new Tooltip("Annotation value (not an FDR q-value)"));
      nHeaderLabel.setVisible(true);
      nHeaderLabel.setManaged(true);
      sortDirHeader.setVisible(alleleCnAnnotationMode);
      sortDirHeader.setManaged(alleleCnAnnotationMode);
    } else {
      qHeaderLabel.setText("q");
      Tooltip.install(qHeaderLabel, new Tooltip(Q_VALUE_TOOLTIP));
    }
    refreshSortHeaderLabels();
  }

  /** Rebuild header children so annotation mode omits Δ/Hits/Diff nodes entirely. */
  private void rebuildHeaderChildren(boolean multiChannel) {
    Region toggleSpacer = new Region();
    toggleSpacer.setMinSize(TOGGLE_SIZE, TOGGLE_SIZE);
    toggleSpacer.setPrefSize(TOGGLE_SIZE, TOGGLE_SIZE);
    toggleSpacer.setMaxSize(TOGGLE_SIZE, TOGGLE_SIZE);

    Label num = headerLabel("#", COL_NUM);
    List<Node> kids = new ArrayList<>();
    kids.add(toggleSpacer);
    kids.add(colSep());
    kids.add(num);
    kids.add(colSep());
    kids.add(sortNameHeader);
    kids.add(colSep());
    kids.add(sortChrHeader);
    kids.add(colSep());
    kids.add(sortPosHeader);
    if (annotationMode) {
      kids.add(colSep());
      kids.add(sortNHeader);
      if (alleleCnAnnotationMode) {
        kids.add(colSep());
        kids.add(sortDirHeader);
      }
      if (!multiChannel) {
        kids.add(colSep());
        kids.add(sortQHeader);
      }
      if (multiChannel) {
        kids.add(channelHeaderBox);
      }
    } else {
      kids.add(colSep());
      kids.add(sortPHeader);
      kids.add(colSep());
      kids.add(sortQHeader);
      kids.add(colSep());
      kids.add(sortDeltaHeader);
      kids.add(colSep());
      kids.add(hitsHeaderLabel);
      kids.add(colSep());
      kids.add(diffHeaderLabel);
    }
    Region trail = new Region();
    trail.getStyleClass().add("vn-col");
    HBox.setHgrow(trail, Priority.ALWAYS);
    kids.add(trail);
    headerRow.getChildren().setAll(kids);
  }

  private void rebuildChannelHeaders(boolean multiChannel) {
    channelHeaderBox.getChildren().clear();
    sortChannelHeaders.clear();
    sortChannelArrows.clear();
    sortChannelIds.clear();
    channelHeaderBox.setAlignment(Pos.CENTER_LEFT);
    channelHeaderBox.setVisible(multiChannel);
    channelHeaderBox.setManaged(multiChannel);
    if (!multiChannel || currentResult == null) {
      return;
    }
    for (SignalChannel ch : currentResult.signalChannels()) {
      channelHeaderBox.getChildren().addAll(colSep(), sortableChannelHeader(ch));
    }
  }

  private HBox sortableChannelHeader(SignalChannel ch) {
    Label textLabel = new Label(ch.label());
    textLabel.getStyleClass().add("vn-header-label");
    Label arrow = sortArrowLabel();
    HBox box = new HBox(2, textLabel, arrow);
    box.getStyleClass().add("vn-header-sortable");
    box.setAlignment(Pos.CENTER_LEFT);
    box.setPrefWidth(COL_CHANNEL);
    box.setMinWidth(COL_CHANNEL);
    box.setCursor(Cursor.HAND);
    String channelId = ch.id();
    box.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY) {
        setChannelSort(channelId);
        e.consume();
      }
    });
    sortChannelHeaders.add(box);
    sortChannelArrows.add(arrow);
    sortChannelIds.add(channelId);
    return box;
  }

  private void setChannelSort(String channelId) {
    if (channelId == null || channelId.isBlank()) {
      return;
    }
    if (sortCol == SortCol.CHANNEL && channelId.equals(sortChannelId)) {
      sortAscending = !sortAscending;
    } else {
      sortCol = SortCol.CHANNEL;
      sortChannelId = channelId;
      sortAscending = false;
    }
    applySearchAndSort();
    refreshSortHeaderLabels();
  }

  /** Update stored search text without rebuilding (while debouncing). */
  public void rememberSearchQuery(String query) {
    searchQuery = query != null ? query.trim() : "";
  }

  /**
   * Filter visible gene/feature rows by substring match (gene, chrom, groups, Diff).
   * Queries shorter than {@link org.baseplayer.variant.ui.components.AbstractNestedVariantTable#MIN_SEARCH_CHARS}
   * show the full threshold-filtered list.
   */
  public void setSearchQuery(String query) {
    rememberSearchQuery(query);
    applySearchAndSort();
  }

  public String getSearchQuery() {
    return searchQuery;
  }

  /** Rows currently shown after threshold + search filters. */
  public int shownRowCount() {
    return items.size();
  }

  public void gotoRow(GroupStatResult.Row row) {
    if (row == null) {
      return;
    }
    // Gene units: navigateToGene (padded view + core-body cache reuse). Prefer this
    // over raw locus coords so a prior Load-region / gene load is not discarded.
    if (unitMode == ComparisonUnit.GENE
        && row.geneName() != null
        && !row.geneName().isBlank()
        && !row.geneName().contains(":")) {
      NavigationCommands.navigateToGene(row.geneName(), true);
      return;
    }
    if (row.chromosome() != null && row.end1Based() > 0) {
      NavigationCommands.navigateToPosition(
          row.chromosome(), (int) row.start1Based(), (int) Math.max(row.start1Based(), row.end1Based()));
    }
  }

  /** Plain-text Diff label for export / tooltips (mode-aware). */
  public static String formatDiffPlain(AnalysisMode mode, GroupStatResult.Row row) {
    return GroupComparisonDiffFormat.formatDiffPlain(mode, row);
  }

  public static String formatDiffPlain(GroupStatResult.Row row) {
    return GroupComparisonDiffFormat.formatDiffPlain(row);
  }

  private HBox buildHeader() {
    HBox row = new HBox(0);
    row.getStyleClass().addAll("vn-header", "vn-header-gene", "vn-header-row");
    row.setAlignment(Pos.CENTER_LEFT);
    row.setPadding(new Insets(4, 8, 4, 8));

    hitsHeaderLabel.getStyleClass().add("vn-header-label");
    hitsHeaderLabel.setPrefWidth(COL_HITS);
    hitsHeaderLabel.setMinWidth(COL_HITS);
    diffHeaderLabel.getStyleClass().add("vn-header-label");
    diffHeaderLabel.setPrefWidth(COL_DIFF);
    diffHeaderLabel.setMinWidth(COL_DIFF);

    channelHeaderBox.setVisible(false);
    channelHeaderBox.setManaged(false);
    // Initial group-comparison layout; annotation mode rebuilds via updateHeaderForMode.
    Region toggleSpacer = new Region();
    toggleSpacer.setMinSize(TOGGLE_SIZE, TOGGLE_SIZE);
    toggleSpacer.setPrefSize(TOGGLE_SIZE, TOGGLE_SIZE);
    toggleSpacer.setMaxSize(TOGGLE_SIZE, TOGGLE_SIZE);
    Label num = headerLabel("#", COL_NUM);
    Region trail = new Region();
    trail.getStyleClass().add("vn-col");
    HBox.setHgrow(trail, Priority.ALWAYS);
    row.getChildren().addAll(
        toggleSpacer, colSep(), num, colSep(), sortNameHeader, colSep(), sortChrHeader,
        colSep(), sortPosHeader, colSep(), sortPHeader, colSep(), sortQHeader,
        colSep(), sortDeltaHeader, colSep(), hitsHeaderLabel, colSep(), diffHeaderLabel, trail);
    return row;
  }

  private HBox sortableHeader(Label textLabel, double width, SortCol col, Label arrow) {
    textLabel.getStyleClass().add("vn-header-label");
    HBox box = new HBox(2, textLabel, arrow);
    box.getStyleClass().add("vn-header-sortable");
    box.setAlignment(Pos.CENTER_LEFT);
    box.setPrefWidth(width);
    box.setMinWidth(width);
    box.setCursor(Cursor.HAND);
    box.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY) {
        setSort(col);
        e.consume();
      }
    });
    return box;
  }

  private void setSort(SortCol col) {
    if (col == null) {
      return;
    }
    if (sortCol == col) {
      sortAscending = !sortAscending;
    } else {
      sortCol = col;
      sortChannelId = null;
      // Dir / Value / n: descending first; name/chr/pos ascending.
      sortAscending = col == SortCol.NAME || col == SortCol.CHR || col == SortCol.POS;
    }
    applySearchAndSort();
    refreshSortHeaderLabels();
  }

  private void applySearchAndSort() {
    String q = normalizeSearchQuery(searchQuery);
    List<Entry> visible;
    if (q.isEmpty()) {
      visible = new ArrayList<>(allEntries);
    } else {
      visible = new ArrayList<>(Math.min(256, allEntries.size()));
      for (Entry entry : allEntries) {
        if (matchesSearch(entry.row, q)) {
          visible.add(entry);
        }
      }
    }
    Comparator<Entry> cmp = switch (sortCol) {
      case NAME -> Comparator.comparing(
          e -> e.row.geneName() != null ? e.row.geneName() : "",
          String.CASE_INSENSITIVE_ORDER);
      case CHR -> Comparator.comparing(
          e -> chromSortKey(e.row),
          String.CASE_INSENSITIVE_ORDER);
      case POS -> Comparator
          .comparing((Entry e) -> chromSortKey(e.row), String.CASE_INSENSITIVE_ORDER)
          .thenComparingLong(e -> Math.max(0L, e.row.start1Based()));
      case P -> Comparator.comparingDouble(e -> {
        Double pp = e.row.pValue();
        return pp != null && Double.isFinite(pp) ? pp : Double.POSITIVE_INFINITY;
      });
      case Q -> Comparator.comparingDouble(e -> {
        if (annotationMode) {
          Double v = SignalAnnotator.annotationValue(e.row);
          return v != null && Double.isFinite(v) ? v : Double.NEGATIVE_INFINITY;
        }
        Double qq = e.row.qValue() != null ? e.row.qValue() : e.row.pValue();
        return qq != null && Double.isFinite(qq) ? qq : Double.POSITIVE_INFINITY;
      });
      case DELTA -> Comparator.comparingDouble(
          e -> annotationMode
              ? (SignalAnnotator.annotationValue(e.row) != null
                  ? Math.abs(SignalAnnotator.annotationValue(e.row))
                  : 0.0)
              : ThresholdFilter.differenceMagnitude(e.row));
      case DIR -> Comparator.comparingInt(e -> dirSortKey(
          SignalEventDirection.fromResultRow(currentResult, e.row)));
      case N -> Comparator.comparingInt(e ->
          e.row.groups() == null ? 0 : e.row.groups().size());
      case CHANNEL -> Comparator.comparingDouble(e -> channelSortValue(e.row));
    };
    if (!sortAscending) {
      cmp = cmp.reversed();
    }
    cmp = cmp.thenComparing(
        e -> e.row.geneName() != null ? e.row.geneName() : "",
        String.CASE_INSENSITIVE_ORDER);
    visible.sort(cmp);
    items.setAll(visible);
    if (visible.isEmpty() && !allEntries.isEmpty() && !q.isEmpty()) {
      emptyLabel.setText("No genes match search");
      list.setPlaceholder(emptyLabel);
    }
    list.refresh();
  }

  private static String chromSortKey(GroupStatResult.Row row) {
    if (row == null || row.chromosome() == null) {
      return "";
    }
    return ChromosomeNames.forDisplay(row.chromosome());
  }

  /** Gain → Loss → LOH → none so related event types cluster when sorting Dir. */
  private static int dirSortKey(SignalEventDirection dir) {
    if (dir == null) {
      return 3;
    }
    return switch (dir) {
      case GAIN -> 0;
      case LOSS -> 1;
      case LOH -> 2;
      case NONE -> 3;
    };
  }

  private double channelSortValue(GroupStatResult.Row row) {
    if (row == null || currentResult == null || sortChannelId == null) {
      return Double.NEGATIVE_INFINITY;
    }
    Double v = currentResult.channelValue(row.unitId(), sortChannelId);
    return v != null && Double.isFinite(v) ? v : Double.NEGATIVE_INFINITY;
  }

  private static String normalizeSearchQuery(String query) {
    if (query == null) {
      return "";
    }
    String trimmed = query.trim();
    if (trimmed.length() < org.baseplayer.variant.ui.components.AbstractNestedVariantTable.MIN_SEARCH_CHARS) {
      return "";
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }

  private boolean matchesSearch(GroupStatResult.Row row, String queryLower) {
    if (row == null || queryLower == null || queryLower.isEmpty()) {
      return true;
    }
    if (containsIgnoreCase(row.geneName(), queryLower)) {
      return true;
    }
    if (containsIgnoreCase(row.chromosome(), queryLower)) {
      return true;
    }
    if (containsIgnoreCase(row.direction(), queryLower)) {
      return true;
    }
    if (annotationMode && containsIgnoreCase(
        SignalAnnotator.formatAnnotationValue(row, lohAnnotationMode), queryLower)) {
      return true;
    }
    if (containsIgnoreCase(GroupComparisonDiffFormat.formatDiffPlain(analysisMode, row), queryLower)) {
      return true;
    }
    if (containsIgnoreCase(GroupComparisonDiffFormat.formatHitsPlain(analysisMode, row), queryLower)) {
      return true;
    }
    if (row.groups() != null) {
      for (GroupStatResult.GroupSummary g : row.groups()) {
        if (g != null && containsIgnoreCase(g.groupName(), queryLower)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean containsIgnoreCase(String haystack, String queryLower) {
    return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(queryLower);
  }

  private void refreshSortHeaderLabels() {
    applySortArrow(sortNameHeader, sortNameArrow, SortCol.NAME);
    applySortArrow(sortChrHeader, sortChrArrow, SortCol.CHR);
    applySortArrow(sortPosHeader, sortPosArrow, SortCol.POS);
    applySortArrow(sortPHeader, sortPArrow, SortCol.P);
    applySortArrow(sortQHeader, sortQArrow, SortCol.Q);
    applySortArrow(sortDeltaHeader, sortDeltaArrow, SortCol.DELTA);
    applySortArrow(sortDirHeader, sortDirArrow, SortCol.DIR);
    applySortArrow(sortNHeader, sortNArrow, SortCol.N);
    for (int i = 0; i < sortChannelHeaders.size(); i++) {
      boolean active = sortCol == SortCol.CHANNEL
          && sortChannelId != null
          && sortChannelId.equals(sortChannelIds.get(i));
      applySortArrowState(sortChannelHeaders.get(i), sortChannelArrows.get(i), active);
    }
  }

  private void applySortArrow(HBox header, Label arrow, SortCol col) {
    applySortArrowState(header, arrow, sortCol == col);
  }

  private void applySortArrowState(HBox header, Label arrow, boolean active) {
    if (active) {
      arrow.setText(sortAscending ? "▲" : "▼");
      arrow.setVisible(true);
      arrow.setManaged(true);
      if (!header.getStyleClass().contains("vn-header-sortable-active")) {
        header.getStyleClass().add("vn-header-sortable-active");
      }
    } else {
      arrow.setText("");
      arrow.setVisible(false);
      arrow.setManaged(false);
      header.getStyleClass().remove("vn-header-sortable-active");
    }
  }

  private static Label sortArrowLabel() {
    Label arrow = new Label();
    arrow.getStyleClass().add("vn-header-sort-arrow");
    arrow.setMinWidth(Region.USE_PREF_SIZE);
    arrow.setVisible(false);
    arrow.setManaged(false);
    return arrow;
  }

  private static Label headerLabel(String text, double width) {
    Label label = new Label(text);
    label.getStyleClass().add("vn-header-label");
    label.setPrefWidth(width);
    label.setMinWidth(width);
    return label;
  }

  private static Region colSep() {
    Region sep = new Region();
    sep.getStyleClass().add("vn-col-sep");
    sep.setMinWidth(4);
    sep.setPrefWidth(4);
    sep.setMaxWidth(4);
    return sep;
  }

  private Node buildGeneNode(Entry entry, int rowNumber) {
    HBox content = buildGeneContent(entry, rowNumber);
    if (!entry.expanded) {
      return content;
    }
    List<Node> children = new ArrayList<>();
    if (annotationMode) {
      children.add(buildSampleHeader());
      if (entry.row.groups() != null) {
        for (GroupStatResult.GroupSummary g : entry.row.groups()) {
          children.add(buildSampleValueRow(entry.row.unitId(), g));
        }
      }
    } else {
      children.add(buildGroupHeader());
      if (entry.row.groups() != null) {
        for (GroupStatResult.GroupSummary g : entry.row.groups()) {
          children.add(buildGroupValueRow(g));
        }
      }
    }
    return wrapBranch(content, children, () -> {
      entry.expanded = false;
      list.refresh();
    });
  }

  /** Annotation expand: Sample + channel/value columns (variant-table style). */
  private HBox buildSampleHeader() {
    HBox row = new HBox(0);
    row.getStyleClass().addAll("vn-header", "vn-header-sample", "vn-header-inline");
    row.setAlignment(Pos.CENTER_LEFT);
    row.setPadding(new Insets(2, 8, 2, 8));
    row.setMinHeight(24);
    row.setPrefHeight(24);
    row.setMaxHeight(24);

    Region gutter = new Region();
    gutter.setMinWidth(GUTTER_WIDTH);
    gutter.setPrefWidth(GUTTER_WIDTH);
    gutter.setMaxWidth(GUTTER_WIDTH);

    row.getChildren().addAll(gutter, colSep(), headerLabel("Sample", 140));
    if (currentResult != null && currentResult.hasSignalChannels()) {
      for (SignalChannel ch : currentResult.signalChannels()) {
        row.getChildren().addAll(colSep(), headerLabel(ch.label(), COL_CHANNEL));
      }
    } else {
      row.getChildren().addAll(colSep(), headerLabel("Value", COL_CHANNEL));
    }
    return row;
  }

  private Node buildSampleValueRow(String unitId, GroupStatResult.GroupSummary group) {
    HBox row = new HBox(0);
    row.getStyleClass().addAll("vn-node", "vn-node-sample");
    row.setAlignment(Pos.CENTER_LEFT);
    row.setPadding(new Insets(2, 8, 2, 8));
    row.setMinHeight(26);
    row.setPrefHeight(26);
    row.setMaxHeight(26);

    Region gutter = new Region();
    gutter.setMinWidth(GUTTER_WIDTH);
    gutter.setPrefWidth(GUTTER_WIDTH);
    gutter.setMaxWidth(GUTTER_WIDTH);

    Label name = new Label(group != null ? group.groupName() : "—");
    name.setStyle("-fx-font-weight: bold;");
    name.setPrefWidth(140);
    name.setMinWidth(140);
    name.setEllipsisString("…");

    row.getChildren().addAll(gutter, colSep(), name);

    FeatureValueBundle bundle = AnalysisSession.get().getSignalBundle();
    SampleTrack track = resolveSampleTrack(group != null ? group.groupName() : null, bundle);
    if (currentResult != null && currentResult.hasSignalChannels()) {
      for (SignalChannel ch : currentResult.signalChannels()) {
        Double v = sampleChannelValue(bundle, unitId, track, ch.id(), group);
        row.getChildren().addAll(colSep(), metaLabel(
            SignalValueType.format(ch.type(), v),
            COL_CHANNEL));
      }
    } else {
      double mean = group != null ? group.mean() : Double.NaN;
      row.getChildren().addAll(colSep(), metaLabel(
          SignalValueType.RAW.format(mean),
          COL_CHANNEL));
    }
    return row;
  }

  private static SampleTrack resolveSampleTrack(String displayName, FeatureValueBundle bundle) {
    if (displayName == null || bundle == null) {
      return null;
    }
    for (SampleTrack track : bundle.tracks()) {
      if (track != null && displayName.equals(track.getDisplayName())) {
        return track;
      }
    }
    return null;
  }

  private static Double sampleChannelValue(
      FeatureValueBundle bundle,
      String unitId,
      SampleTrack track,
      String channelId,
      GroupStatResult.GroupSummary fallback) {
    if (bundle != null && track != null && unitId != null && channelId != null) {
      FeatureValueMatrix matrix = bundle.matrix(channelId);
      if (matrix != null) {
        Double v = matrix.get(unitId, track);
        if (v != null && Double.isFinite(v)) {
          return v;
        }
      }
    }
    if (fallback != null && Double.isFinite(fallback.mean())) {
      return fallback.mean();
    }
    return null;
  }

  private HBox buildGeneContent(Entry entry, int rowNumber) {
    GroupStatResult.Row row = entry.row;
    HBox box = new HBox(0);
    box.getStyleClass().addAll("vn-node", "vn-node-gene");
    box.setAlignment(Pos.CENTER_LEFT);
    box.setPadding(new Insets(2, 8, 2, 8));
    box.setMinHeight(28);
    box.setPrefHeight(28);
    box.setMaxHeight(28);
    if (annotationMode) {
      applyAnnotationRowColor(box, row);
    }

    Label toggle = buildToggle(entry.expanded);

    Label num = metaLabel(String.valueOf(Math.max(1, rowNumber)), COL_NUM);
    num.getStyleClass().add("vn-meta");

    Label name = new Label(nullToDash(row.geneName()));
    name.getStyleClass().addAll("vn-title", "vn-col");
    name.setPrefWidth(COL_NAME);
    name.setMinWidth(COL_NAME);
    name.setMaxWidth(COL_NAME);
    name.setEllipsisString("…");

    Label chr = metaLabel(formatChrom(row), COL_CHR);
    Label pos = metaLabel(formatPos(row), COL_POS);
    boolean multiChannel = annotationMode
        && currentResult != null
        && currentResult.hasSignalChannels()
        && currentResult.signalChannels().size() > 1;

    List<Node> kids = new ArrayList<>();
    kids.add(toggle);
    kids.add(colSep());
    kids.add(num);
    kids.add(colSep());
    kids.add(name);
    kids.add(colSep());
    kids.add(chr);
    kids.add(colSep());
    kids.add(pos);

    if (annotationMode) {
      int nSamples = row.groups() == null ? 0 : row.groups().size();
      kids.add(colSep());
      kids.add(metaLabel(String.valueOf(nSamples), COL_N));
      if (alleleCnAnnotationMode) {
        SignalEventDirection dir = SignalEventDirection.fromResultRow(currentResult, row);
        kids.add(colSep());
        kids.add(directionLabel(dir));
      }
      if (!multiChannel) {
        kids.add(colSep());
        kids.add(metaLabel(SignalAnnotator.formatAnnotationValue(row, lohAnnotationMode), COL_Q));
      } else {
        for (SignalChannel ch : currentResult.signalChannels()) {
          Double cv = currentResult.channelValue(row.unitId(), ch.id());
          kids.add(colSep());
          kids.add(metaLabel(SignalValueType.format(ch.type(), cv), COL_CHANNEL));
        }
      }
    } else {
      kids.add(colSep());
      kids.add(metaLabel(formatP(row.pValue()), COL_P));
      kids.add(colSep());
      kids.add(metaLabel(formatP(row.qValue()), COL_Q));
      Label delta = metaLabel(formatDelta(row), COL_DELTA);
      Tooltip.install(delta, new Tooltip(GroupComparisonDiffFormat.DELTA_TOOLTIP));
      Node hits = buildHitsSummary(row);
      Node diff = buildDiffSummary(row);
      HBox.setHgrow(diff, Priority.ALWAYS);
      kids.add(colSep());
      kids.add(delta);
      kids.add(colSep());
      kids.add(hits);
      kids.add(colSep());
      kids.add(diff);
    }
    box.getChildren().setAll(kids);

    Runnable toggleAction = () -> {
      entry.expanded = !entry.expanded;
      list.refresh();
    };
    toggle.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
      if (e.getButton() == MouseButton.PRIMARY) {
        toggleAction.run();
        e.consume();
      }
    });
    box.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
      if (e.getButton() != MouseButton.PRIMARY || e.isConsumed() || e.getClickCount() >= 2) {
        return;
      }
      toggleAction.run();
      e.consume();
    });
    box.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
      if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() < 2) {
        return;
      }
      gotoRow(row);
      if (onGoto != null) {
        onGoto.accept(row);
      }
      e.consume();
    });
    return box;
  }

  private static Label directionLabel(SignalEventDirection dir) {
    SignalEventDirection d = dir != null ? dir : SignalEventDirection.NONE;
    Label lab = metaLabel(d.label(), COL_DIR);
    switch (d) {
      case GAIN -> lab.setStyle("-fx-text-fill: rgb(45, 95, 210); -fx-font-weight: bold;");
      case LOSS -> lab.setStyle("-fx-text-fill: rgb(180, 90, 40); -fx-font-weight: bold;");
      case LOH -> lab.setStyle("-fx-text-fill: rgb(210, 55, 55); -fx-font-weight: bold;");
      case NONE -> { /* muted via vn-meta */ }
    }
    return lab;
  }

  private void applyAnnotationRowColor(HBox box, GroupStatResult.Row row) {
    if (alleleCnAnnotationMode) {
      SignalEventDirection dir = SignalEventDirection.fromResultRow(currentResult, row);
      applyDirectionRowTint(box, dir);
      return;
    }
    Double v = SignalAnnotator.annotationValue(row);
    if (v == null || !Double.isFinite(v)) {
      return;
    }
    double lo = valueColorMin;
    double hi = valueColorMax;
    double[] chromRange = chromValueRange.get(chromSortKey(row));
    if (chromRange != null && chromRange.length >= 2) {
      lo = chromRange[0];
      hi = chromRange[1];
    }
    double span = hi - lo;
    if (!(span > 0)) {
      span = 1e-9;
    }
    double t = (v - lo) / span;
    t = Math.max(0, Math.min(1, t));
    // Cool muted → warm accent with value intensity (vs chrom baseline).
    double r = 0.18 + 0.55 * t;
    double g = 0.28 + 0.15 * (1.0 - t);
    double b = 0.42 - 0.25 * t;
    double a = 0.12 + 0.38 * t;
    box.setStyle(String.format(Locale.ROOT,
        "-fx-background-color: rgba(%.0f,%.0f,%.0f,%.2f); -fx-background-radius: 3;",
        r * 255, g * 255, b * 255, a));
  }

  private static void applyDirectionRowTint(HBox box, SignalEventDirection dir) {
    if (dir == null || dir == SignalEventDirection.NONE) {
      return;
    }
    // Muted tints matching aggregate Gain (blue) / LOH (red) / Loss (amber).
    String rgba = switch (dir) {
      case GAIN -> "rgba(45,95,210,0.18)";
      case LOSS -> "rgba(180,90,40,0.18)";
      case LOH -> "rgba(210,55,55,0.20)";
      case NONE -> null;
    };
    if (rgba != null) {
      box.setStyle("-fx-background-color: " + rgba + "; -fx-background-radius: 3;");
    }
  }

  private static String formatChrom(GroupStatResult.Row row) {
    if (row == null || row.chromosome() == null || row.chromosome().isBlank()) {
      return "—";
    }
    return ChromosomeNames.forDisplay(row.chromosome());
  }

  private static String formatPos(GroupStatResult.Row row) {
    if (row == null || row.start1Based() <= 0) {
      return "—";
    }
    long start = row.start1Based();
    long end = row.end1Based();
    if (end > start) {
      return String.format(Locale.ROOT, "%,d–%,d", start, end);
    }
    return String.format(Locale.ROOT, "%,d", start);
  }

  private Node buildHitsSummary(GroupStatResult.Row row) {
    HBox box = new HBox(4);
    box.setAlignment(Pos.CENTER_LEFT);
    box.setMinWidth(COL_HITS);
    box.setPrefWidth(COL_HITS);
    box.setMaxWidth(COL_HITS);
    if (row == null || row.groups() == null || row.groups().isEmpty()) {
      Label empty = new Label("—");
      empty.getStyleClass().add("vn-summary");
      box.getChildren().add(empty);
      return box;
    }
    boolean first = true;
    StringBuilder tip = new StringBuilder();
    boolean polarityMode = GroupStatResult.usesPolarityRateDiff(analysisMode);
    for (GroupStatResult.GroupSummary g : row.groups()) {
      if (g == null) {
        continue;
      }
      if (!first) {
        Label sep = new Label("·");
        sep.getStyleClass().add("vn-summary");
        sep.setOpacity(0.5);
        box.getChildren().add(sep);
        tip.append(" · ");
      }
      first = false;
      String text = GroupComparisonDiffFormat.formatHitsChip(g, polarityMode);
      double rate = g.pctSignal();
      double opacity = Double.isNaN(rate) ? 0.55 : 0.55 + 0.45 * Math.min(1.0, rate / 100.0);
      Label chip = new Label(text);
      chip.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-opacity: "
          + String.format(Locale.ROOT, "%.2f", opacity)
          + "; -fx-text-fill: " + cssHex(groupColor(g.groupId())) + ";");
      box.getChildren().add(chip);
      tip.append(GroupComparisonDiffFormat.formatHitsTooltip(g));
    }
    Tooltip.install(box, new Tooltip(tip.toString()));
    return box;
  }

  private Node buildDiffSummary(GroupStatResult.Row row) {
    if (row == null || row.groups() == null || row.groups().isEmpty()
        || GroupComparisonDiffFormat.isSimilar(row)) {
      Label similar = new Label("similar");
      similar.getStyleClass().add("vn-summary");
      similar.setOpacity(0.75);
      similar.setPrefWidth(COL_DIFF);
      similar.setMinWidth(COL_DIFF);
      return similar;
    }
    HBox diff = new HBox(6);
    diff.setAlignment(Pos.CENTER_LEFT);
    diff.setMinWidth(COL_DIFF);
    diff.setPrefWidth(Region.USE_COMPUTED_SIZE);
    diff.setMaxHeight(24);

    if (GroupStatResult.usesPolarityRateDiff(analysisMode)) {
      buildPolarityRateDiffChips(diff, row);
    } else {
      buildMeanDiffChips(diff, row);
    }

    if (diff.getChildren().isEmpty()) {
      Label similar = new Label("similar");
      similar.getStyleClass().add("vn-summary");
      similar.setOpacity(0.75);
      return similar;
    }
    Tooltip.install(diff, new Tooltip(
        GroupComparisonDiffFormat.formatDiffPlain(analysisMode, row)
            + "  (Δ " + formatDelta(row) + ")"));
    return diff;
  }

  private void buildPolarityRateDiffChips(HBox diff, GroupStatResult.Row row) {
    boolean split = GroupStatResult.hasPolaritySplit(row.groups());
    boolean first = true;

    if (split) {
      for (GroupStatResult.GroupSummary g : row.groups()) {
        if (g == null) {
          continue;
        }
        GroupStatResult.Polarity pol = g.dominantPolarity();
        if (pol != GroupStatResult.Polarity.GAIN && pol != GroupStatResult.Polarity.LOSS) {
          continue;
        }
        if (!first) {
          addDiffSep(diff);
        }
        first = false;
        diff.getChildren().add(coloredChip(pol.word() + " ", g));
      }
    } else {
      GroupStatResult.HitRateExtremes rates = GroupStatResult.hitRateExtremes(row.groups());
      if (rates != null && rates.distinct()) {
        diff.getChildren().add(rateExtremeChip(rates.most()));
        addDiffSep(diff);
        diff.getChildren().add(rateExtremeChip(rates.least()));
      } else {
        for (GroupStatResult.GroupSummary g : row.groups()) {
          if (g == null) {
            continue;
          }
          if (!first) {
            addDiffSep(diff);
          }
          first = false;
          GroupStatResult.Polarity pol = g.dominantPolarity();
          String prefix = pol != GroupStatResult.Polarity.NONE ? pol.glyph() + " " : "";
          diff.getChildren().add(coloredChip(prefix, g));
        }
      }
    }

    String mixed = GroupComparisonDiffFormat.formatMixedFlag(row.groups());
    if (!mixed.isEmpty()) {
      Label flag = new Label(mixed);
      flag.getStyleClass().add("vn-summary");
      flag.setOpacity(0.75);
      flag.setStyle("-fx-font-size: 11px;");
      diff.getChildren().add(flag);
    }
  }

  private Label rateExtremeChip(GroupStatResult.GroupSummary g) {
    String text = GroupComparisonDiffFormat.formatExtremeChip(g);
    Label chip = new Label(text);
    chip.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: "
        + cssHex(groupColor(g.groupId())) + ";");
    chip.setMaxHeight(22);
    return chip;
  }

  private static void addDiffSep(HBox diff) {
    Label sep = new Label("·");
    sep.getStyleClass().add("vn-summary");
    sep.setOpacity(0.5);
    diff.getChildren().add(sep);
  }

  private void buildMeanDiffChips(HBox diff, GroupStatResult.Row row) {
    GroupStatResult.MeanExtremes extremes = GroupStatResult.meanExtremes(row.groups());
    boolean first = true;
    for (GroupStatResult.GroupSummary g : row.groups()) {
      if (g == null) {
        continue;
      }
      if (!first) {
        Label sep = new Label("·");
        sep.getStyleClass().add("vn-summary");
        sep.setOpacity(0.5);
        diff.getChildren().add(sep);
      }
      first = false;
      String prefix = "";
      if (extremes != null && extremes.distinct()) {
        if (g.groupId() == extremes.highest().groupId()) {
          prefix = "↑ ";
        } else if (g.groupId() == extremes.lowest().groupId()) {
          prefix = "↓ ";
        }
      }
      diff.getChildren().add(coloredChip(prefix, g));
    }
  }

  private HBox buildGroupHeader() {
    HBox row = new HBox(0);
    row.getStyleClass().addAll("vn-header", "vn-header-sample", "vn-header-inline");
    row.setAlignment(Pos.CENTER_LEFT);
    row.setPadding(new Insets(2, 8, 2, 8));
    row.setMinHeight(24);
    row.setPrefHeight(24);
    row.setMaxHeight(24);

    Region gutter = new Region();
    gutter.setMinWidth(GUTTER_WIDTH);
    gutter.setPrefWidth(GUTTER_WIDTH);
    gutter.setMaxWidth(GUTTER_WIDTH);

    Label g = headerLabel("Group", 140);
    Label mean = headerLabel("Mean", 80);
    Label median = headerLabel("Median", 80);
    Label hits = headerLabel("Hits", 72);
    Label pct = headerLabel("%", 48);
    Label signs = headerLabel("+ / −", 64);
    signs.setVisible(!annotationMode);
    signs.setManaged(!annotationMode);
    row.getChildren().addAll(
        gutter, colSep(), g, colSep(), mean, colSep(), median, colSep(), hits, colSep(), pct, colSep(), signs);
    return row;
  }

  private Node buildGroupValueRow(GroupStatResult.GroupSummary group) {
    HBox row = new HBox(0);
    row.getStyleClass().addAll("vn-node", "vn-node-sample");
    row.setAlignment(Pos.CENTER_LEFT);
    row.setPadding(new Insets(2, 8, 2, 8));
    row.setMinHeight(26);
    row.setPrefHeight(26);
    row.setMaxHeight(26);

    Region gutter = new Region();
    gutter.setMinWidth(GUTTER_WIDTH);
    gutter.setPrefWidth(GUTTER_WIDTH);
    gutter.setMaxWidth(GUTTER_WIDTH);

    Region swatch = new Region();
    swatch.setMinSize(4, 16);
    swatch.setPrefSize(4, 16);
    swatch.setMaxSize(4, 16);
    swatch.setStyle("-fx-background-radius: 2; -fx-background-color: "
        + cssHex(group != null ? groupColor(group.groupId()) : null) + ";");

    Label name = new Label(group != null ? group.groupName() : "—");
    name.setStyle("-fx-font-weight: bold; -fx-text-fill: "
        + cssHex(group != null ? groupColor(group.groupId()) : null) + ";");
    name.setPrefWidth(136);
    name.setMinWidth(136);

    HBox nameBox = new HBox(4, swatch, name);
    nameBox.setAlignment(Pos.CENTER_LEFT);
    nameBox.setPrefWidth(140);
    nameBox.setMinWidth(140);

    Label mean = metaLabel(
        group == null || Double.isNaN(group.mean())
            ? "—"
            : (lohAnnotationMode && annotationMode
                ? SignalAnnotator.formatLohSampleValue(group.mean())
                : String.format(Locale.ROOT, "%.4g", group.mean())),
        80);
    Tooltip.install(mean, new Tooltip(
        lohAnnotationMode && annotationMode ? "LOH in this sample" : meanTooltip));
    Label median = metaLabel(
        group == null || Double.isNaN(group.median())
            ? "—"
            : (lohAnnotationMode && annotationMode
                ? SignalAnnotator.formatLohSampleValue(group.median())
                : String.format(Locale.ROOT, "%.4g", group.median())),
        80);
    Label hits = metaLabel(
        group != null ? group.nSignal() + "/" + group.nTotal() : "—",
        72);
    Label pct = metaLabel(
        group == null || Double.isNaN(group.pctSignal())
            ? "—"
            : String.format(Locale.ROOT, "%.0f", group.pctSignal()),
        48);
    Label signs = metaLabel(
        group != null ? group.nPos() + " / " + group.nNeg() : "—",
        64);
    // Polarity +/− is for mutation/SV group comparison, not Signal raw channels.
    boolean showSigns = !annotationMode;
    signs.setVisible(showSigns);
    signs.setManaged(showSigns);

    row.getChildren().addAll(
        gutter, colSep(), nameBox, colSep(), mean, colSep(), median,
        colSep(), hits, colSep(), pct, colSep(), signs);
    return row;
  }

  private static Node wrapBranch(Node parentContent, List<Node> children, Runnable onCollapse) {
    VBox root = new VBox(0);
    root.getStyleClass().add("vn-branch-root");
    root.getChildren().add(parentContent);

    Region gutter = new Region();
    gutter.getStyleClass().addAll("vn-gutter", "vn-gutter-gene");
    gutter.setMinWidth(GUTTER_WIDTH);
    gutter.setPrefWidth(GUTTER_WIDTH);
    gutter.setMaxWidth(GUTTER_WIDTH);
    gutter.setMaxHeight(Double.MAX_VALUE);
    gutter.setCursor(Cursor.HAND);
    gutter.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY) {
        onCollapse.run();
        e.consume();
      }
    });

    VBox childrenBox = new VBox(2);
    childrenBox.getStyleClass().add("vn-branch-children");
    childrenBox.getChildren().addAll(children);
    HBox.setHgrow(childrenBox, Priority.ALWAYS);

    HBox branch = new HBox(0);
    branch.getStyleClass().add("vn-branch");
    branch.getChildren().addAll(gutter, childrenBox);
    root.getChildren().add(branch);
    return root;
  }

  private static Label buildToggle(boolean expanded) {
    Label toggle = new Label(expanded ? "−" : "+");
    toggle.getStyleClass().addAll("vn-toggle", expanded ? "vn-toggle-open" : "vn-toggle-closed");
    toggle.setMinSize(TOGGLE_SIZE, TOGGLE_SIZE);
    toggle.setPrefSize(TOGGLE_SIZE, TOGGLE_SIZE);
    toggle.setMaxSize(TOGGLE_SIZE, TOGGLE_SIZE);
    toggle.setAlignment(Pos.CENTER);
    return toggle;
  }

  private static Label coloredChip(String prefix, GroupStatResult.GroupSummary group) {
    Label label = new Label(prefix + (group != null ? group.groupName() : "—"));
    label.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: "
        + cssHex(group != null ? groupColor(group.groupId()) : null) + ";");
    label.setMaxHeight(22);
    return label;
  }

  private static Label metaLabel(String text, double width) {
    Label label = new Label(text);
    label.getStyleClass().add("vn-summary");
    label.setPrefWidth(width);
    label.setMinWidth(width);
    label.setMaxWidth(width);
    return label;
  }

  private static String formatDelta(GroupStatResult.Row row) {
    if (row != null && row.effectVsBaseline() != null && Double.isFinite(row.effectVsBaseline())) {
      return String.format(Locale.ROOT, "%.3g", row.effectVsBaseline());
    }
    GroupStatResult.MeanExtremes extremes = GroupStatResult.meanExtremes(row != null ? row.groups() : null);
    if (extremes == null || !extremes.distinct()) {
      return "—";
    }
    double delta = extremes.highest().mean() - extremes.lowest().mean();
    if (Double.isNaN(delta)) {
      return "—";
    }
    return String.format(Locale.ROOT, "%.3g", delta);
  }

  static final String P_VALUE_TOOLTIP =
      "Raw test p-value in [0, 1]. Tiny values use 1.0×10^-5 style (positive).";

  /** Tooltip for the q column: FDR in [0,1]; scientific notation is not a negative value. */
  static final String Q_VALUE_TOOLTIP =
      "Benjamini–Hochberg FDR q-value in [0, 1]. "
          + "Tiny values use 1.0×10^-5 style (positive); Δ is the signed effect.";

  /**
   * Format a probability / FDR. Null/NaN → —. Finite values are clamped to {@code [0, 1]}
   * for display (numerical CDF noise). Tiny positives use {@code ×10^} so the exponent
   * sign is not mistaken for a negative probability.
   */
  static String formatP(Double v) {
    if (v == null || Double.isNaN(v) || !Double.isFinite(v)) {
      return "—";
    }
    double x = v;
    if (x < 0.0) {
      x = 0.0;
    } else if (x > 1.0) {
      x = 1.0;
    }
    if (x == 0.0) {
      return "0";
    }
    if (x < 1e-4) {
      int exp = (int) Math.floor(Math.log10(x));
      double coeff = x / Math.pow(10.0, exp);
      return String.format(Locale.ROOT, "%.1f×10^%d", coeff, exp);
    }
    return String.format(Locale.ROOT, "%.4f", x);
  }

  private static String nullToDash(String s) {
    return s == null || s.isBlank() ? "—" : s;
  }

  private static SampleRegistry registry() {
    try {
      return ServiceRegistry.getInstance().getSampleRegistry();
    } catch (Exception ex) {
      return null;
    }
  }

  private static Color groupColor(int groupId) {
    return ComparisonContext.groupColor(registry(), groupId);
  }

  private static String cssHex(Color color) {
    if (color == null) {
      return "#888888";
    }
    int r = (int) Math.round(color.getRed() * 255);
    int g = (int) Math.round(color.getGreen() * 255);
    int b = (int) Math.round(color.getBlue() * 255);
    return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
  }

  private final class GeneCell extends ListCell<Entry> {
    @Override
    protected void updateItem(Entry entry, boolean empty) {
      super.updateItem(entry, empty);
      if (empty || entry == null) {
        setText(null);
        setGraphic(null);
        return;
      }
      setText(null);
      setGraphic(buildGeneNode(entry, Math.max(1, getIndex() + 1)));
    }
  }

  private static final class Entry {
    final GroupStatResult.Row row;
    boolean expanded;

    Entry(GroupStatResult.Row row) {
      this.row = row;
    }
  }

  private static final class NoSelectionModel<T> extends MultipleSelectionModel<T> {
    @Override
    public ObservableList<Integer> getSelectedIndices() {
      return FXCollections.observableArrayList();
    }

    @Override
    public ObservableList<T> getSelectedItems() {
      return FXCollections.observableArrayList();
    }

    @Override
    public void selectIndices(int index, int... indices) {}

    @Override
    public void selectAll() {}

    @Override
    public void selectFirst() {}

    @Override
    public void selectLast() {}

    @Override
    public void clearAndSelect(int index) {}

    @Override
    public void select(int index) {}

    @Override
    public void select(T obj) {}

    @Override
    public void clearSelection(int index) {}

    @Override
    public void clearSelection() {}

    @Override
    public boolean isSelected(int index) {
      return false;
    }

    @Override
    public boolean isEmpty() {
      return true;
    }

    @Override
    public void selectPrevious() {}

    @Override
    public void selectNext() {}
  }
}

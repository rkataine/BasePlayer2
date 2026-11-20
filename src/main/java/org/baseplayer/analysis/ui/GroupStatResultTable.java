package org.baseplayer.analysis.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.ComparisonUnit;
import org.baseplayer.analysis.GroupStatResult;
import org.baseplayer.analysis.ThresholdFilter;
import org.baseplayer.controllers.commands.NavigationCommands;
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
    NAME, CHR, POS, Q, DELTA
  }

  private static final double TOGGLE_SIZE = 18;
  private static final double GUTTER_WIDTH = 16;
  private static final double COL_NUM = 36;
  private static final double COL_NAME = 120;
  private static final double COL_CHR = 48;
  private static final double COL_POS = 130;
  private static final double COL_Q = 72;
  private static final double COL_DELTA = 72;
  private static final double COL_SUPPORT = 160;
  private static final double COL_DIFF = 200;
  private String meanTooltip = "Mean over all group samples in the test vector";

  private final ListView<Entry> list = new ListView<>();
  private final ObservableList<Entry> items = FXCollections.observableArrayList();
  private final List<Entry> allEntries = new ArrayList<>();
  private final Label emptyLabel = new Label("Run analysis to see group differences");
  private final HBox sortNameHeader;
  private final HBox sortChrHeader;
  private final HBox sortPosHeader;
  private final HBox sortQHeader;
  private final HBox sortDeltaHeader;
  private final Label sortNameArrow;
  private final Label sortChrArrow;
  private final Label sortPosArrow;
  private final Label sortQArrow;
  private final Label sortDeltaArrow;
  private final Label unitHeaderLabel = new Label("Gene");
  private Consumer<GroupStatResult.Row> onGoto;
  private ComparisonUnit unitMode = ComparisonUnit.GENE;
  private SortCol sortCol = SortCol.DELTA;
  private boolean sortAscending = false;
  private String searchQuery = "";

  public GroupStatResultTable() {
    getStyleClass().add("variant-nested-container");
    setSpacing(0);
    VBox.setVgrow(this, Priority.ALWAYS);

    sortNameArrow = sortArrowLabel();
    sortChrArrow = sortArrowLabel();
    sortPosArrow = sortArrowLabel();
    sortQArrow = sortArrowLabel();
    sortDeltaArrow = sortArrowLabel();
    sortNameHeader = sortableHeader(unitHeaderLabel, COL_NAME, SortCol.NAME, sortNameArrow);
    sortChrHeader = sortableHeader(new Label("Chr"), COL_CHR, SortCol.CHR, sortChrArrow);
    sortPosHeader = sortableHeader(new Label("Pos"), COL_POS, SortCol.POS, sortPosArrow);
    sortQHeader = sortableHeader(new Label("q"), COL_Q, SortCol.Q, sortQArrow);
    sortDeltaHeader = sortableHeader(new Label("Δ"), COL_DELTA, SortCol.DELTA, sortDeltaArrow);

    HBox header = buildHeader();
    header.getStyleClass().add("vn-sticky-headers");
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

    getChildren().addAll(header, list);
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
    if (result == null || result.rows().isEmpty()) {
      allEntries.clear();
      items.clear();
      emptyLabel.setText(
          result != null && result.summary() != null && !result.summary().isBlank()
              ? result.summary()
              : "No comparable units");
      list.setPlaceholder(emptyLabel);
      return;
    }
    List<Entry> next = new ArrayList<>(result.rows().size());
    for (GroupStatResult.Row row : result.rows()) {
      next.add(new Entry(row));
    }
    allEntries.clear();
    allEntries.addAll(next);
    list.setTooltip(new Tooltip(result.summary()));
    applySearchAndSort();
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
    if (row.chromosome() != null && row.end1Based() > 0) {
      NavigationCommands.navigateToPosition(
          row.chromosome(), (int) row.start1Based(), (int) Math.max(row.start1Based(), row.end1Based()));
      return;
    }
    if (unitMode == ComparisonUnit.GENE
        && row.geneName() != null
        && !row.geneName().isBlank()
        && !row.geneName().contains(":")) {
      NavigationCommands.navigateToGene(row.geneName(), true);
    }
  }

  /** Plain-text Diff label for export / tooltips (all groups, extremes marked). */
  public static String formatDiffPlain(GroupStatResult.Row row) {
    if (row == null || row.groups() == null || row.groups().isEmpty()) {
      return "similar";
    }
    if (isSimilar(row)) {
      return "similar";
    }
    GroupStatResult.MeanExtremes extremes = GroupStatResult.meanExtremes(row.groups());
    StringBuilder sb = new StringBuilder();
    for (GroupStatResult.GroupSummary g : row.groups()) {
      if (g == null) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append(" · ");
      }
      if (extremes != null && extremes.distinct()) {
        if (g.groupId() == extremes.highest().groupId()) {
          sb.append('↑').append(' ');
        } else if (g.groupId() == extremes.lowest().groupId()) {
          sb.append('↓').append(' ');
        }
      }
      sb.append(g.groupName());
    }
    return sb.length() == 0 ? "similar" : sb.toString();
  }

  private HBox buildHeader() {
    HBox row = new HBox(0);
    row.getStyleClass().addAll("vn-header", "vn-header-gene", "vn-header-row");
    row.setAlignment(Pos.CENTER_LEFT);
    row.setPadding(new Insets(4, 8, 4, 8));

    Region toggleSpacer = new Region();
    toggleSpacer.setMinSize(TOGGLE_SIZE, TOGGLE_SIZE);
    toggleSpacer.setPrefSize(TOGGLE_SIZE, TOGGLE_SIZE);
    toggleSpacer.setMaxSize(TOGGLE_SIZE, TOGGLE_SIZE);

    Label num = headerLabel("#", COL_NUM);
    Label support = headerLabel("Support", COL_SUPPORT);
    Label diff = headerLabel("Diff", COL_DIFF);

    row.getChildren().addAll(
        toggleSpacer,
        colSep(),
        num,
        colSep(),
        sortNameHeader,
        colSep(),
        sortChrHeader,
        colSep(),
        sortPosHeader,
        colSep(),
        sortQHeader,
        colSep(),
        sortDeltaHeader,
        colSep(),
        support,
        colSep(),
        diff);
    Region trail = new Region();
    trail.getStyleClass().add("vn-col");
    HBox.setHgrow(trail, Priority.ALWAYS);
    row.getChildren().add(trail);
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
      case Q -> Comparator.comparingDouble(e -> {
        Double qq = e.row.qValue() != null ? e.row.qValue() : e.row.pValue();
        return qq != null && !Double.isNaN(qq) ? qq : Double.POSITIVE_INFINITY;
      });
      case DELTA -> Comparator.comparingDouble(
          e -> ThresholdFilter.differenceMagnitude(e.row));
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

  private static boolean matchesSearch(GroupStatResult.Row row, String queryLower) {
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
    if (containsIgnoreCase(formatDiffPlain(row), queryLower)) {
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
    applySortArrow(sortQHeader, sortQArrow, SortCol.Q);
    applySortArrow(sortDeltaHeader, sortDeltaArrow, SortCol.DELTA);
  }

  private void applySortArrow(HBox header, Label arrow, SortCol col) {
    boolean active = sortCol == col;
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
    children.add(buildGroupHeader());
    if (entry.row.groups() != null) {
      for (GroupStatResult.GroupSummary g : entry.row.groups()) {
        children.add(buildGroupValueRow(g));
      }
    }
    return wrapBranch(content, children, () -> {
      entry.expanded = false;
      list.refresh();
    });
  }

  private HBox buildGeneContent(Entry entry, int rowNumber) {
    GroupStatResult.Row row = entry.row;
    HBox box = new HBox(0);
    box.getStyleClass().addAll("vn-node", "vn-node-gene");
    box.setAlignment(Pos.CENTER_LEFT);
    box.setPadding(new Insets(2, 8, 2, 4));
    box.setMinHeight(28);
    box.setPrefHeight(28);
    box.setMaxHeight(28);

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
    Label q = metaLabel(formatP(row.qValue() != null ? row.qValue() : row.pValue()), COL_Q);
    Label delta = metaLabel(formatDelta(row), COL_DELTA);
    Node support = buildSupportSummary(row);
    Node diff = buildDiffSummary(row);
    HBox.setHgrow(diff, Priority.ALWAYS);

    box.getChildren().addAll(
        toggle,
        colSep(),
        num,
        colSep(),
        name,
        colSep(),
        chr,
        colSep(),
        pos,
        colSep(),
        q,
        colSep(),
        delta,
        colSep(),
        support,
        colSep(),
        diff);

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

  private Node buildSupportSummary(GroupStatResult.Row row) {
    HBox box = new HBox(4);
    box.setAlignment(Pos.CENTER_LEFT);
    box.setMinWidth(COL_SUPPORT);
    box.setPrefWidth(COL_SUPPORT);
    box.setMaxWidth(COL_SUPPORT);
    if (row == null || row.groups() == null || row.groups().isEmpty()) {
      Label empty = new Label("—");
      empty.getStyleClass().add("vn-summary");
      box.getChildren().add(empty);
      return box;
    }
    boolean first = true;
    StringBuilder tip = new StringBuilder();
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
      String text = g.nSignal() + "/" + g.nTotal();
      Label chip = new Label(text);
      chip.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: "
          + cssHex(groupColor(g.groupId())) + ";");
      box.getChildren().add(chip);
      tip.append(g.groupName()).append(' ').append(text)
          .append(" hits (").append(String.format(Locale.ROOT, "%.0f%%", g.pctSignal())).append(')');
    }
    Tooltip.install(box, new Tooltip(tip.toString()));
    return box;
  }

  private static Node buildDiffSummary(GroupStatResult.Row row) {
    if (row == null || row.groups() == null || row.groups().isEmpty() || isSimilar(row)) {
      Label similar = new Label("similar");
      similar.getStyleClass().add("vn-summary");
      similar.setOpacity(0.75);
      similar.setPrefWidth(COL_DIFF);
      similar.setMinWidth(COL_DIFF);
      return similar;
    }
    GroupStatResult.MeanExtremes extremes = GroupStatResult.meanExtremes(row.groups());
    HBox diff = new HBox(6);
    diff.setAlignment(Pos.CENTER_LEFT);
    diff.setMinWidth(COL_DIFF);
    diff.setPrefWidth(Region.USE_COMPUTED_SIZE);
    diff.setMaxHeight(24);
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
    if (diff.getChildren().isEmpty()) {
      Label similar = new Label("similar");
      similar.getStyleClass().add("vn-summary");
      similar.setOpacity(0.75);
      return similar;
    }
    Tooltip.install(diff, new Tooltip(formatDiffPlain(row) + "  (Δ " + formatDelta(row) + ")"));
    return diff;
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
            : String.format(Locale.ROOT, "%.4g", group.mean()),
        80);
    Tooltip.install(mean, new Tooltip(meanTooltip));
    Label median = metaLabel(
        group == null || Double.isNaN(group.median())
            ? "—"
            : String.format(Locale.ROOT, "%.4g", group.median()),
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
    GroupStatResult.MeanExtremes extremes = GroupStatResult.meanExtremes(row != null ? row.groups() : null);
    if (extremes == null || !extremes.distinct()) {
      return "—";
    }
    double delta = Math.abs(extremes.highest().mean() - extremes.lowest().mean());
    if (Double.isNaN(delta)) {
      return "—";
    }
    return String.format(Locale.ROOT, "%.3g", delta);
  }

  private static String formatP(Double v) {
    if (v == null || Double.isNaN(v)) {
      return "—";
    }
    if (v < 1e-4) {
      return String.format(Locale.ROOT, "%.1e", v);
    }
    return String.format(Locale.ROOT, "%.4f", v);
  }

  private static boolean isSimilar(GroupStatResult.Row row) {
    if (row == null) {
      return true;
    }
    String dir = row.direction() != null ? row.direction() : "";
    return "similar".equals(dir) || dir.isBlank();
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

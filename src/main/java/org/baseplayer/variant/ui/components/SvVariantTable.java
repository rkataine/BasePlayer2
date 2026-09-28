package org.baseplayer.variant.ui.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;
import org.baseplayer.variant.ui.components.VariantTable.TableRow;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

/**
 * Structural-variant result tables: All / Gene-overlapping / Other.
 */
public class SvVariantTable {

  private final Tab allTab;
  private final Tab geneTab;
  private final Tab otherTab;
  private final TableView<TableRow> allTable;
  private final TableView<TableRow> geneTable;
  private final TableView<TableRow> otherTable;

  private VariantFilter displayFilter = new VariantFilter();
  private ObservableList<TableRow> allItems = FXCollections.observableArrayList();
  private ObservableList<TableRow> geneItems = FXCollections.observableArrayList();
  private ObservableList<TableRow> otherItems = FXCollections.observableArrayList();
  private String searchQuery = "";

  private final Consumer<TableRow> onPositionClick;

  @SuppressWarnings("unchecked")
  public SvVariantTable(
      TableView<?> allTable,
      TableView<?> geneTable,
      TableView<?> otherTable,
      Tab allTab,
      Tab geneTab,
      Tab otherTab,
      Consumer<TableRow> onPositionClick) {
    this.allTab = allTab;
    this.geneTab = geneTab;
    this.otherTab = otherTab;
    this.allTable = (TableView<TableRow>) allTable;
    this.geneTable = (TableView<TableRow>) geneTable;
    this.otherTable = (TableView<TableRow>) otherTable;
    this.onPositionClick = onPositionClick != null ? onPositionClick : r -> {};
  }

  public void initializeColumns() {
    setupTable(allTable);
    setupTable(geneTable);
    setupTable(otherTable);
  }

  private void setupTable(TableView<TableRow> table) {
    table.getColumns().clear();
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

    table.getColumns().add(col("Type", 70, row -> VariantTypeVisuals.shortLabel(row.node().type)));
    table.getColumns().add(col("Position", 180, this::formatPosition));
    table.getColumns().add(col("Length", 90, this::formatLength));
    table.getColumns().add(col("Mate", 140, this::formatMate));
    table.getColumns().add(col("Gene(s)", 160, this::formatGenes));
    table.getColumns().add(col("Samples", 70, row ->
        String.valueOf(displayFilter.countPassingSamples(row.node()))));
    table.getColumns().add(col("QUAL", 70, row -> {
      double q = row.node().siteQuality;
      return q >= 0 ? String.format(Locale.ROOT, "%.0f", q) : "—";
    }));

    table.setRowFactory(tv -> {
      javafx.scene.control.TableRow<TableRow> row = new javafx.scene.control.TableRow<>();
      row.setOnMouseClicked(e -> {
        if (e.getClickCount() == 1 && !row.isEmpty()) {
          onPositionClick.accept(row.getItem());
        }
      });
      return row;
    });
  }

  private TableColumn<TableRow, String> col(String title, double width, Function<TableRow, String> value) {
    TableColumn<TableRow, String> column = new TableColumn<>(title);
    column.setPrefWidth(width);
    column.setCellValueFactory(cd ->
        new SimpleStringProperty(cd.getValue() == null ? "" : value.apply(cd.getValue())));
    return column;
  }

  private String formatPosition(TableRow row) {
    VariantNode node = row.node();
    String chrom = row.chromosome();
    if (node.svEnd > node.position) {
      return chrom + ":" + node.position + "–" + node.svEnd;
    }
    return chrom + ":" + node.position;
  }

  private String formatLength(TableRow row) {
    long len = VariantTypeVisuals.svSpanLengthBp(row.node());
    if (len < 0) {
      return "—";
    }
    if (len >= 1_000_000) {
      return String.format(Locale.ROOT, "%.1f Mb", len / 1_000_000.0);
    }
    if (len >= 1_000) {
      return String.format(Locale.ROOT, "%.1f kb", len / 1_000.0);
    }
    return len + " bp";
  }

  private String formatMate(TableRow row) {
    VariantNode node = row.node();
    VcfVariantType type = node.type;
    if (type != VcfVariantType.SV_TRANSLOCATION && type != VcfVariantType.SV_BREAKEND) {
      return "—";
    }
    if (node.svChr2 == null || node.svChr2.isBlank()) {
      return "—";
    }
    if (node.svEnd2 >= 0) {
      return node.svChr2 + ":" + node.svEnd2;
    }
    return node.svChr2;
  }

  private String formatGenes(TableRow row) {
    VariantAnnotation ann = row.node().annotation;
    if (ann == null || ann.geneName() == null || ann.geneName().isBlank()) {
      return "—";
    }
    return ann.geneName();
  }

  public void setDisplayContext(VariantFilter filter) {
    this.displayFilter = filter != null ? filter.copy() : new VariantFilter();
  }

  public void setItems(
      ObservableList<TableRow> all,
      ObservableList<TableRow> gene,
      ObservableList<TableRow> other) {
    allItems = all != null ? all : FXCollections.observableArrayList();
    geneItems = gene != null ? gene : FXCollections.observableArrayList();
    otherItems = other != null ? other : FXCollections.observableArrayList();
    applySearch();
  }

  public void setSearchQuery(String query) {
    searchQuery = query != null ? query.trim() : "";
    applySearch();
  }

  public void setPlaceholders(String allText, String geneText, String otherText) {
    allTable.setPlaceholder(new Label(allText != null ? allText : ""));
    geneTable.setPlaceholder(new Label(geneText != null ? geneText : ""));
    otherTable.setPlaceholder(new Label(otherText != null ? otherText : ""));
  }

  public void setBaseTabTitles() {
    setTitle(allTab, "All", 0, false);
    setTitle(geneTab, "Gene-overlapping", 0, false);
    setTitle(otherTab, "Other", 0, false);
  }

  private void applySearch() {
    ObservableList<TableRow> all = filter(allItems);
    ObservableList<TableRow> gene = filter(geneItems);
    ObservableList<TableRow> other = filter(otherItems);
    allTable.setItems(all);
    geneTable.setItems(gene);
    otherTable.setItems(other);
    setTitle(allTab, "All", all.size(), true);
    setTitle(geneTab, "Gene-overlapping", gene.size(), true);
    setTitle(otherTab, "Other", other.size(), true);
  }

  private ObservableList<TableRow> filter(ObservableList<TableRow> source) {
    if (source == null || source.isEmpty()) {
      return FXCollections.observableArrayList();
    }
    if (searchQuery.isEmpty()) {
      return source;
    }
    String q = searchQuery.toLowerCase(Locale.ROOT);
    List<TableRow> matched = new ArrayList<>();
    for (TableRow row : source) {
      if (rowMatches(row, q)) {
        matched.add(row);
      }
    }
    return FXCollections.observableArrayList(matched);
  }

  private boolean rowMatches(TableRow row, String q) {
    if (row == null || row.node() == null) {
      return false;
    }
    if (formatPosition(row).toLowerCase(Locale.ROOT).contains(q)) return true;
    if (formatGenes(row).toLowerCase(Locale.ROOT).contains(q)) return true;
    if (formatMate(row).toLowerCase(Locale.ROOT).contains(q)) return true;
    if (VariantTypeVisuals.shortLabel(row.node().type).toLowerCase(Locale.ROOT).contains(q)) return true;
    VariantEffect effect = row.node().annotation != null ? row.node().annotation.effect() : null;
    if (effect != null && effect.displayName().toLowerCase(Locale.ROOT).contains(q)) return true;
    return false;
  }

  private static void setTitle(Tab tab, String base, int count, boolean withCount) {
    if (tab == null) {
      return;
    }
    tab.setText(withCount ? base + " (" + count + ")" : base);
  }

  /** True when annotation indicates a gene-overlapping (non-intergenic) SV. */
  public static boolean isGeneOverlapping(VariantNode node) {
    if (node == null) {
      return false;
    }
    VariantAnnotation ann = node.annotation;
    if (ann == null) {
      return false;
    }
    VariantEffect effect = ann.effect();
    return effect != null && effect != VariantEffect.INTERGENIC;
  }
}

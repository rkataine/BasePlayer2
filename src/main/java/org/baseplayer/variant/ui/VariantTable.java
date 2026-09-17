package org.baseplayer.variant.ui;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.baseplayer.annotation.CosmicCensusEntry;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;

import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Encapsulates variant result table rendering and interactions.
 * Keeps controller logic focused on orchestration instead of table wiring details.
 */
public class VariantTable {
    private static final String TEXT = "white";
    private static final String CANCER_COLOR = "#d16624";
    private static final String COLOR_SYNONYMOUS = "#4caf50";
    private static final String COLOR_MISSENSE = "#ff9800";
    private static final String COLOR_TRUNCATING = "#f44336";
    private static final String COLOR_NONCODING = "#9e9e9e";

    private static final String TAB_GENE = "Gene";
    private static final String TAB_INTRONIC = "Intronic";
    private static final String TAB_INTERGENIC = "Intergenic";
    private static final double GENE_COLUMN_WIDTH = 150;

    private final Tab codingTab;
    private final Tab intronicTab;
    private final Tab intergenicTab;
    private final VariantTableBackend backend;
    private VariantFilter displayFilter = new VariantFilter();

    public static record TableRow(String chromosome, VariantNode node) {
        public TableRow {
            if (chromosome == null) {
                chromosome = "";
            }
        }
    }

    public VariantTable(
        TableView<VariantNode> codingTable,
        TableView<VariantNode> intronicTable,
        TableView<VariantNode> intergenicTable,
        Tab codingTab,
        Tab intronicTab,
        Tab intergenicTab,
        Consumer<TableRow> onGeneDoubleClick,
        Consumer<TableRow> onPositionClick) {

        this.codingTab = codingTab;
        this.intronicTab = intronicTab;
        this.intergenicTab = intergenicTab;

        this.backend = new ListViewVariantTableBackend(
            codingTable,
            intronicTable,
            intergenicTable,
            onGeneDoubleClick,
            onPositionClick,
            () -> this.displayFilter);
    }

		public void initializeColumns() {
        backend.initializeColumns();
    }

    public void setItems(
        ObservableList<TableRow> codingItems,
        ObservableList<TableRow> intronicItems,
        ObservableList<TableRow> intergenicItems) {

        backend.setItems(codingItems, intronicItems, intergenicItems);
        setTabCounts(
            codingItems != null ? codingItems.size() : 0,
            intronicItems != null ? intronicItems.size() : 0,
            intergenicItems != null ? intergenicItems.size() : 0);
    }

    public void setPlaceholders(Node codingPlaceholder, Node intronicPlaceholder, Node intergenicPlaceholder) {
        backend.setPlaceholders(codingPlaceholder, intronicPlaceholder, intergenicPlaceholder);
    }

    public void setDisplayContext(VariantFilter filter) {
        this.displayFilter = filter != null ? filter.copy() : new VariantFilter();
    }

    public boolean scrollToFirstVariantForChromosome(String chromosome) {
        return backend.scrollToFirstVariantForChromosome(chromosome);
    }

    public void setTabCounts(int codingCount, int intronicCount, int intergenicCount) {
        setTabTitle(codingTab, TAB_GENE, codingCount, true);
        setTabTitle(intronicTab, TAB_INTRONIC, intronicCount, true);
        setTabTitle(intergenicTab, TAB_INTERGENIC, intergenicCount, true);
    }

    public void setBaseTabTitles() {
        setTabTitle(codingTab, TAB_GENE, 0, false);
        setTabTitle(intronicTab, TAB_INTRONIC, 0, false);
        setTabTitle(intergenicTab, TAB_INTERGENIC, 0, false);
    }

    public void setZeroTabCounts() {
        setTabCounts(0, 0, 0);
    }

    private static void setTabTitle(Tab tab, String baseTitle, int count, boolean withCount) {
        if (tab == null) {
            return;
        }
        tab.setText(withCount ? (baseTitle + " (" + count + ")") : baseTitle);
    }

    private interface VariantTableBackend {
        void initializeColumns();

        void setItems(
            ObservableList<TableRow> codingItems,
            ObservableList<TableRow> intronicItems,
            ObservableList<TableRow> intergenicItems);

        void setPlaceholders(Node codingPlaceholder, Node intronicPlaceholder, Node intergenicPlaceholder);

        boolean scrollToFirstVariantForChromosome(String chromosome);
    }

    private static final class ListViewVariantTableBackend implements VariantTableBackend {
        private final TableView<VariantNode> codingTable;
        private final TableView<VariantNode> intronicTable;
        private final TableView<VariantNode> intergenicTable;
        private final Consumer<TableRow> onGeneDoubleClick;
        private final Consumer<TableRow> onPositionClick;
        private final Supplier<VariantFilter> filterSupplier;

        private ListView<TableRow> codingList;
        private ListView<TableRow> intronicList;
        private ListView<TableRow> intergenicList;

        private ListViewVariantTableBackend(
            TableView<VariantNode> codingTable,
            TableView<VariantNode> intronicTable,
            TableView<VariantNode> intergenicTable,
            Consumer<TableRow> onGeneDoubleClick,
            Consumer<TableRow> onPositionClick,
            Supplier<VariantFilter> filterSupplier) {

            this.codingTable = codingTable;
            this.intronicTable = intronicTable;
            this.intergenicTable = intergenicTable;
            this.onGeneDoubleClick = onGeneDoubleClick;
            this.onPositionClick = onPositionClick;
            this.filterSupplier = filterSupplier;
        }

        @Override
        public void initializeColumns() {
            codingList = mountListView(codingTable, true);
            intronicList = mountListView(intronicTable, false);
            intergenicList = mountListView(intergenicTable, false);
        }

        @Override
        public void setItems(
            ObservableList<TableRow> codingItems,
            ObservableList<TableRow> intronicItems,
            ObservableList<TableRow> intergenicItems) {

            if (codingList == null || intronicList == null || intergenicList == null) {
                initializeColumns();
            }
            if (codingList != null) {
                codingList.setItems(codingItems);
            }
            if (intronicList != null) {
                intronicList.setItems(intronicItems);
            }
            if (intergenicList != null) {
                intergenicList.setItems(intergenicItems);
            }
        }

        @Override
        public void setPlaceholders(Node codingPlaceholder, Node intronicPlaceholder, Node intergenicPlaceholder) {
            if (codingList == null || intronicList == null || intergenicList == null) {
                initializeColumns();
            }
            if (codingList != null) {
                codingList.setPlaceholder(codingPlaceholder);
            }
            if (intronicList != null) {
                intronicList.setPlaceholder(intronicPlaceholder);
            }
            if (intergenicList != null) {
                intergenicList.setPlaceholder(intergenicPlaceholder);
            }
        }

        @Override
        public boolean scrollToFirstVariantForChromosome(String chromosome) {
            if (chromosome == null || chromosome.isBlank()) {
                return false;
            }
            boolean scrolled = false;
            scrolled |= scrollListToFirstChromosomeRow(codingList, chromosome);
            scrolled |= scrollListToFirstChromosomeRow(intronicList, chromosome);
            scrolled |= scrollListToFirstChromosomeRow(intergenicList, chromosome);
            return scrolled;
        }

        private static boolean scrollListToFirstChromosomeRow(ListView<TableRow> listView, String chromosome) {
            if (listView == null) {
                return false;
            }
            ObservableList<TableRow> items = listView.getItems();
            if (items == null || items.isEmpty()) {
                return false;
            }
            for (int index = 0; index < items.size(); index++) {
                TableRow row = items.get(index);
                if (row != null && chromosome.equals(row.chromosome())) {
                    listView.scrollTo(index);
                    return true;
                }
            }
            return false;
        }

        private ListView<TableRow> mountListView(
            TableView<VariantNode> table,
            boolean includeCodingColumns) {

            if (table == null || !(table.getParent() instanceof StackPane parent)) {
                return null;
            }

            ListView<TableRow> listView = new ListView<>();
            listView.getStyleClass().addAll(table.getStyleClass());
            listView.getStyleClass().add("variant-list");
            listView.setCellFactory(ignored -> new VariantListCell(
                includeCodingColumns,
                onGeneDoubleClick,
                onPositionClick,
                filterSupplier));
            listView.setPlaceholder(table.getPlaceholder());

            HBox header = createHeaderRow(includeCodingColumns);
            VBox container = new VBox();
            container.getStyleClass().add("variant-list-container");
            VBox.setVgrow(listView, Priority.ALWAYS);
            container.getChildren().addAll(header, listView);

            parent.getChildren().add(container);
            table.setVisible(false);
            table.setManaged(false);
            return listView;
        }

        private static HBox createHeaderRow(boolean includeCodingColumns) {
            HBox header = new HBox(8);
            header.getStyleClass().add("variant-list-header");

            header.getChildren().addAll(
                createHeaderLabel("Gene", GENE_COLUMN_WIDTH),
                createHeaderLabel("Position", 150),
                createHeaderLabel("Ref/Alt", 90),
                createHeaderLabel("Type", 65)
            );

            if (includeCodingColumns) {
                header.getChildren().addAll(
                    createHeaderLabel("Effect", 120),
                    createHeaderLabel("AA", 120),
                    createHeaderLabel("Codon", 110)
                );
            }

            header.getChildren().addAll(
                createHeaderLabel("Samples", 70),
                createHeaderLabel("Max GQ", 70)
            );

            return header;
        }

        private static Label createHeaderLabel(String text, double width) {
            Label label = new Label(text);
            label.setAlignment(Pos.CENTER_LEFT);
            label.setMinWidth(width);
            label.setPrefWidth(width);
            label.setMaxWidth(width);
            label.setStyle("-fx-text-fill: " + TEXT + "; -fx-font-weight: bold; -fx-opacity: 0.85;");
            return label;
        }
    }

    private static final class VariantListCell extends ListCell<TableRow> {
        private final boolean includeCodingColumns;
        private final Consumer<TableRow> onGeneDoubleClick;
        private final Consumer<TableRow> onPositionClick;
        private final Supplier<VariantFilter> filterSupplier;

        private final HBox root = new HBox(8);
        private final HBox geneBox = new HBox(4);
        private final Label gene = createGeneNameLabel();
        private final Label tier = createTierLabel();
        private final Label position = createCellLabel(150);
        private final Label refAlt = createCellLabel(90);
        private final Label type = createCellLabel(65);
        private final Label effect = createCellLabel(120);
        private final Label aa = createCellLabel(120);
        private final Label codon = createCellLabel(110);
        private final Label samples = createCellLabel(70);
        private final Label maxGq = createCellLabel(70);

        private VariantListCell(
            boolean includeCodingColumns,
            Consumer<TableRow> onGeneDoubleClickCallback,
            Consumer<TableRow> onPositionClickCallback,
            Supplier<VariantFilter> filterSupplier) {

            this.includeCodingColumns = includeCodingColumns;
            this.onGeneDoubleClick = onGeneDoubleClickCallback;
            this.onPositionClick = onPositionClickCallback;
            this.filterSupplier = filterSupplier;

            root.setAlignment(Pos.CENTER_LEFT);
            geneBox.setAlignment(Pos.CENTER_LEFT);
            geneBox.setMinWidth(GENE_COLUMN_WIDTH);
            geneBox.setPrefWidth(GENE_COLUMN_WIDTH);
            geneBox.setMaxWidth(GENE_COLUMN_WIDTH);
            geneBox.getChildren().addAll(gene, tier);
            root.getChildren().addAll(geneBox, position, refAlt, type);
            if (includeCodingColumns) {
                root.getChildren().addAll(effect, aa, codon);
            }
            root.getChildren().addAll(samples, maxGq);

            setOnMouseClicked(event -> {
                TableRow row = getItem();
                String geneName = rowGeneName(row);
                if (event.getClickCount() == 2 && row != null && geneName != null && !geneName.isBlank() && this.onGeneDoubleClick != null) {
                    this.onGeneDoubleClick.accept(row);
                    event.consume();
                }
            });
        }

        @Override
        protected void updateItem(TableRow row, boolean empty) {
            super.updateItem(row, empty);
            VariantNode node = row != null ? row.node() : null;
            if (empty || row == null || node == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            VariantFilter filter = filterSupplier.get();
            String defaultColor = rowTextColor(row);
            String geneName = rowGeneName(row);
            String geneColor = rowIsCancerGene(row) ? CANCER_COLOR : defaultColor;
            String tierText = rowCosmicTier(row);

            setLabel(gene, geneName == null ? "" : geneName, geneColor);
            if (rowIsCancerGene(row) && tierText != null) {
                tier.setText("T" + tierText);
                tier.setVisible(true);
                tier.setManaged(true);
            } else {
                tier.setVisible(false);
                tier.setManaged(false);
                tier.setText("");
            }

            setLabel(position, resolveTableColumnValue(row, "position", filter), defaultColor);
            position.setCursor(javafx.scene.Cursor.HAND);
            position.setOnMouseClicked(event -> {
                if (row != null && onPositionClick != null) {
                    onPositionClick.accept(row);
                    event.consume();
                }
            });
            setLabel(refAlt, resolveTableColumnValue(row, "refAlt", filter), defaultColor);
            setLabel(type, resolveTableColumnValue(row, "variantType", filter), defaultColor);
            if (includeCodingColumns) {
                setLabel(effect, resolveTableColumnValue(row, "effectDisplay", filter), defaultColor);
                setLabel(aa, resolveTableColumnValue(row, "aaChange", filter), defaultColor);
                setLabel(codon, resolveTableColumnValue(row, "codonChange", filter), defaultColor);
            }
            setLabel(samples, resolveTableColumnValue(row, "sampleCount", filter), defaultColor);
            setLabel(maxGq, resolveTableColumnValue(row, "maxQuality", filter), defaultColor);

            setText(null);
            setGraphic(root);
        }

        private static void setLabel(Label label, String text, String color) {
            label.setText(text == null ? "" : text);
            label.setStyle("-fx-text-fill: " + color + ";");
        }

        private static Label createCellLabel(double width) {
            Label label = new Label();
            label.setAlignment(Pos.CENTER_LEFT);
            label.setMinWidth(width);
            label.setPrefWidth(width);
            label.setMaxWidth(width);
            return label;
        }

        /** Gene name sizes to text so the census tier badge sits immediately after it. */
        private static Label createGeneNameLabel() {
            Label label = new Label();
            label.setAlignment(Pos.CENTER_LEFT);
            label.setMinWidth(Region.USE_PREF_SIZE);
            label.setPrefWidth(Region.USE_COMPUTED_SIZE);
            label.setMaxWidth(GENE_COLUMN_WIDTH - 28);
            return label;
        }

        private static Label createTierLabel() {
            Label label = new Label();
            label.setStyle("-fx-background-color: " + CANCER_COLOR + "; -fx-text-fill: white;"
                + " -fx-padding: 0 3 0 3; -fx-font-size: 9; -fx-background-radius: 3;");
            label.setMinWidth(Region.USE_PREF_SIZE);
            label.setPrefWidth(Region.USE_COMPUTED_SIZE);
            label.setMaxWidth(Region.USE_PREF_SIZE);
            label.setManaged(false);
            label.setVisible(false);
            return label;
        }
    }

    public static VariantAnnotation rowAnnotation(VariantNode row) {
        return row != null ? row.annotation : null;
    }

    public static VariantAnnotation rowAnnotation(TableRow row) {
        return rowAnnotation(row != null ? row.node() : null);
    }

    public static VariantEffect rowEffect(VariantNode row) {
        VariantAnnotation ann = rowAnnotation(row);
        return ann != null ? ann.effect() : VariantEffect.INTERGENIC;
    }

    public static VariantEffect rowEffect(TableRow row) {
        return rowEffect(row != null ? row.node() : null);
    }

    public static String rowGeneName(VariantNode row) {
        VariantAnnotation ann = rowAnnotation(row);
        return ann != null ? ann.geneName() : null;
    }

    public static String rowGeneName(TableRow row) {
        return rowGeneName(row != null ? row.node() : null);
    }

    public static boolean rowIsCancerGene(VariantNode row) {
        VariantAnnotation ann = rowAnnotation(row);
        return ann != null && ann.isCancerGene();
    }

    public static boolean rowIsCancerGene(TableRow row) {
        return rowIsCancerGene(row != null ? row.node() : null);
    }

    public static String rowCosmicTier(VariantNode row) {
        VariantAnnotation ann = rowAnnotation(row);
        CosmicCensusEntry cosmic = ann != null ? ann.cosmicEntry() : null;
        return cosmic != null ? cosmic.tier() : null;
    }

    public static String rowCosmicTier(TableRow row) {
        return rowCosmicTier(row != null ? row.node() : null);
    }

    public static String rowTextColor(VariantNode row) {
        return switch (rowEffect(row)) {
            case CODING_SYNONYMOUS -> COLOR_SYNONYMOUS;
            case CODING_MISSENSE, CODING_INFRAME -> COLOR_MISSENSE;
            case CODING_STOP_GAIN, CODING_STOP_LOSS, CODING_FRAMESHIFT, SPLICE_SITE -> COLOR_TRUNCATING;
            case CODING_OTHER, UTR5, UTR3, INTRONIC, NONCODING_GENE -> COLOR_NONCODING;
            default -> TEXT;
        };
    }

    public static String rowTextColor(TableRow row) {
        return rowTextColor(row != null ? row.node() : null);
    }

    private static String resolveTableColumnValue(TableRow row, String property, VariantFilter filter) {
        VariantNode node = row != null ? row.node() : null;
        if (node == null) {
            return "";
        }

        String chromosome = row.chromosome();
        VariantAnnotation ann = rowAnnotation(node);
        return switch (property) {
            case "position" -> (chromosome == null || chromosome.isBlank())
                ? String.valueOf(node.position)
                : chromosome + ":" + node.position;
            case "refAlt" -> node.ref + " → " + (node.alt.isEmpty() ? "." : node.alt);
            case "variantType" -> typeLabel(node.type);
            case "effectDisplay" -> rowEffect(node).displayName();
            case "aaChange" -> ann != null && ann.aaChange() != null ? ann.aaChange() : "";
            case "codonChange" -> ann != null && ann.codonChange() != null ? ann.codonChange() : "";
            case "sampleCount" -> String.valueOf(computeSampleCount(node, filter));
            case "maxQuality" -> {
                double maxQuality = computeMaxQuality(node, filter);
                yield maxQuality >= 0 ? String.format("%.0f", maxQuality) : "-";
            }
            default -> "";
        };
    }

    private static int computeSampleCount(VariantNode node, VariantFilter filter) {
        if (node == null) {
            return 0;
        }
        int passSamples = 0;
        if (filter == null) {
            return node.getSamples().size();
        }
        for (VariantNode.SampleCall call : node.getSamples()) {
            if (filter.passesSampleThresholds(node, call)) {
                passSamples++;
            }
        }
        return passSamples;
    }

    private static double computeMaxQuality(VariantNode node, VariantFilter filter) {
        if (node == null) {
            return -1;
        }
        double maxQuality = -1;
        if (filter == null) {
            for (VariantNode.SampleCall call : node.getSamples()) {
                if (call.quality > maxQuality) {
                    maxQuality = call.quality;
                }
            }
            return maxQuality;
        }
        for (VariantNode.SampleCall call : node.getSamples()) {
            if (filter.passesSampleThresholds(node, call) && call.quality > maxQuality) {
                maxQuality = call.quality;
            }
        }
        return maxQuality;
    }

    private static String typeLabel(VcfVariantType type) {
        return switch (type) {
            case SNV -> "SNV";
            case INSERTION -> "Ins";
            case DELETION -> "Del";
            case MNV -> "MNV";
            default -> "Other";
        };
    }
}

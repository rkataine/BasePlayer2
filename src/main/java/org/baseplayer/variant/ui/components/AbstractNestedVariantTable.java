package org.baseplayer.variant.ui.components;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.CosmicCensusEntry;
import org.baseplayer.genome.gene.GeneLocation;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.utils.ChromosomeNames;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MultipleSelectionModel;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Shared Gene → Variant → Sample nested table look.
 * Point and SV tables extend this so styling and interaction live in one place.
 */
public abstract class AbstractNestedVariantTable {
    private static final String TEXT = "#1e1e1e";
    private static final String COLOR_SYNONYMOUS = "#107c10";
    private static final String COLOR_MISSENSE = "#c9a000";
    private static final String COLOR_TRUNCATING = org.baseplayer.ui.theme.AppTheme.CHROME_DARK.dangerHex();
    private static final String COLOR_NONCODING = "#8a8886";


    /** Variant-row column set for nested detail lines. */
    public enum VariantDetailLayout {
        POINT_CODING,
        POINT_SIMPLE,
        STRUCTURAL
    }

    private static final String TAB_GENE = "Gene";
    private static final String TAB_INTRONIC = "Intronic";
    private static final String TAB_INTERGENIC = "Intergenic";

    private final Tab codingTab;
    private final Tab intronicTab;
    private final Tab intergenicTab;
    private final NestedVariantTableBackend backend;
    protected VariantFilter displayFilter = new VariantFilter();

    private ObservableList<TableRow> allCodingItems = FXCollections.observableArrayList();
    private ObservableList<TableRow> allIntronicItems = FXCollections.observableArrayList();
    private ObservableList<TableRow> allIntergenicItems = FXCollections.observableArrayList();
    private String tableSearchQuery = "";

    public static record TableRow(String chromosome, VariantNode node, List<String> displayGenes) {
        public TableRow {
            if (chromosome == null) {
                chromosome = "";
            }
            displayGenes = displayGenes == null ? null : List.copyOf(displayGenes);
        }

        public TableRow(String chromosome, VariantNode node) {
            this(chromosome, node, null);
        }
    }

    /** Gene/chrom group with nested variants and sample calls. */
    static final class GeneGroup {
        final String name;
        final boolean cancer;
        final String tier;
        final List<VariantEntry> variants;
        boolean expanded;
        /** Null until first click/expand; then cached from in-memory annotation. */
        String description;
        boolean descriptionLoaded;
        /**
         * Temporary effect-type filters for this gene's expanded variant list.
         * Empty = show all. Cleared when the gene is collapsed.
         */
        final EnumSet<EffectBucket> typeFilters = EnumSet.noneOf(EffectBucket.class);

        GeneGroup(
                String name,
                boolean cancer,
                String tier,
                List<VariantEntry> variants,
                boolean expanded) {
            this.name = name;
            this.cancer = cancer;
            this.tier = tier;
            this.variants = variants;
            this.expanded = expanded;
            this.description = null;
            this.descriptionLoaded = false;
        }

        String sortChromosome() {
            GeneLocation loc = AnnotationData.getGeneLocation(name);
            if (loc != null && loc.chrom() != null && !loc.chrom().isBlank()) {
                return loc.chrom();
            }
            if (!variants.isEmpty() && variants.get(0).row != null) {
                String chrom = variants.get(0).row.chromosome();
                return chrom != null ? chrom : "";
            }
            return "";
        }

        long sortStart() {
            GeneLocation loc = AnnotationData.getGeneLocation(name);
            if (loc != null && loc.start() > 0) {
                return loc.start();
            }
            long min = Long.MAX_VALUE;
            for (VariantEntry entry : variants) {
                if (entry.row == null || entry.row.node() == null) {
                    continue;
                }
                min = Math.min(min, entry.row.node().position);
            }
            return min == Long.MAX_VALUE ? Long.MAX_VALUE : min;
        }
    }

    static final class VariantEntry {
        final TableRow row;
        final List<VariantNode.SampleCall> calls;
        boolean expanded;

        VariantEntry(TableRow row, List<VariantNode.SampleCall> calls) {
            this.row = row;
            this.calls = calls;
            this.expanded = false;
        }
    }

    /** Colored effect buckets shown on gene rows (and used for per-gene temp filters). */
    enum EffectBucket {
        TRUNCATING(COLOR_TRUNCATING, "Truncating"),
        MISSENSE(COLOR_MISSENSE, "Missense / inframe"),
        SYNONYMOUS(COLOR_SYNONYMOUS, "Synonymous"),
        NONCODING(COLOR_NONCODING, "Other / noncoding");

        final String color;
        final String tooltip;

        EffectBucket(String color, String tooltip) {
            this.color = color;
            this.tooltip = tooltip;
        }

        static EffectBucket of(VariantEffect effect) {
            if (effect == null) {
                return NONCODING;
            }
            return switch (effect) {
                case CODING_STOP_GAIN, CODING_STOP_LOSS, CODING_FRAMESHIFT, SPLICE_SITE
                    -> TRUNCATING;
                case CODING_MISSENSE, CODING_INFRAME -> MISSENSE;
                case CODING_SYNONYMOUS -> SYNONYMOUS;
                default -> NONCODING;
            };
        }
    }

    protected AbstractNestedVariantTable(
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

        this.backend = new NestedVariantTableBackend(
            codingTable,
            intronicTable,
            intergenicTable,
            onGeneDoubleClick,
            onPositionClick,
            () -> this.displayFilter);
    }

    /** Minimal constructor for SV / other subclasses that mount their own panels. */
    protected AbstractNestedVariantTable() {
        this.codingTab = null;
        this.intronicTab = null;
        this.intergenicTab = null;
        this.backend = null;
    }

    public void initializeColumns() {
        if (backend != null) {
            backend.initializeColumns();
        }
    }

    public void setItems(
        ObservableList<TableRow> codingItems,
        ObservableList<TableRow> intronicItems,
        ObservableList<TableRow> intergenicItems) {

        allCodingItems = codingItems != null ? codingItems : FXCollections.observableArrayList();
        allIntronicItems = intronicItems != null ? intronicItems : FXCollections.observableArrayList();
        allIntergenicItems = intergenicItems != null ? intergenicItems : FXCollections.observableArrayList();
        applyTableSearch();
    }

    public void setTableSearchQuery(String query) {
        tableSearchQuery = query != null ? query.trim() : "";
        applyTableSearch();
    }

    public String getTableSearchQuery() {
        return tableSearchQuery;
    }

    /** Post-search coding rows currently shown in the Gene tab. */
    public ObservableList<TableRow> getDisplayedCodingRows() {
        return filterRows(allCodingItems);
    }

    /** Post-search rows currently shown in the Intronic tab. */
    public ObservableList<TableRow> getDisplayedIntronicRows() {
        return filterRows(allIntronicItems);
    }

    /** Post-search rows currently shown in the Intergenic tab. */
    public ObservableList<TableRow> getDisplayedIntergenicRows() {
        return filterRows(allIntergenicItems);
    }

    public VariantFilter getDisplayFilter() {
        return displayFilter;
    }

    private void applyTableSearch() {
        ObservableList<TableRow> coding = filterRows(allCodingItems);
        ObservableList<TableRow> intronic = filterRows(allIntronicItems);
        ObservableList<TableRow> intergenic = filterRows(allIntergenicItems);
        boolean expand = !tableSearchQuery.isEmpty();
        if (backend != null) {
            backend.setItems(coding, intronic, intergenic, expand);
        }
        setTabCounts(coding.size(), intronic.size(), intergenic.size());
    }

    private ObservableList<TableRow> filterRows(ObservableList<TableRow> source) {
        if (source == null || source.isEmpty()) {
            return FXCollections.observableArrayList();
        }
        if (tableSearchQuery.isEmpty()) {
            return source;
        }
        String q = tableSearchQuery.toLowerCase(Locale.ROOT);
        List<TableRow> matched = new ArrayList<>(source.size());
        for (TableRow row : source) {
            if (matchesSearch(row, q, displayFilter)) {
                matched.add(row);
            }
        }
        return FXCollections.observableArrayList(matched);
    }

    static boolean matchesSearch(TableRow row, String queryLower, VariantFilter filter) {
        if (row == null || row.node() == null) {
            return false;
        }
        if (queryLower == null || queryLower.isEmpty()) {
            return true;
        }

        String gene = rowGeneName(row);
        if (gene != null && gene.toLowerCase(Locale.ROOT).contains(queryLower)) {
            return true;
        }

        VariantEffect effect = rowEffect(row);
        if (effect != null) {
            if (effect.displayName().toLowerCase(Locale.ROOT).contains(queryLower)) {
                return true;
            }
            if (effect.name().toLowerCase(Locale.ROOT).contains(queryLower)) {
                return true;
            }
        }

        for (VariantNode.SampleCall call : row.node().getSamples()) {
            if (call == null) {
                continue;
            }
            if (filter != null && !filter.passesSampleDisplay(row.node(), call)) {
                continue;
            }
            String sampleName = sampleDisplayName(call);
            if (sampleName != null && sampleName.toLowerCase(Locale.ROOT).contains(queryLower)) {
                return true;
            }
            if (call.gt != null && call.gt.toLowerCase(Locale.ROOT).contains(queryLower)) {
                return true;
            }
        }

        for (String property : List.of(
            "position", "refAlt", "variantType", "effectDisplay", "aaChange", "codonChange",
            "alleleFraction")) {
            String value = resolveTableColumnValue(row, property, filter);
            if (value != null && value.toLowerCase(Locale.ROOT).contains(queryLower)) {
                return true;
            }
        }
        return false;
    }

    public void setPlaceholders(Node codingPlaceholder, Node intronicPlaceholder, Node intergenicPlaceholder) {
        if (backend != null) {
            backend.setPlaceholders(codingPlaceholder, intronicPlaceholder, intergenicPlaceholder);
        }
    }

    public void setDisplayContext(VariantFilter filter) {
        this.displayFilter = filter != null ? filter.copy() : new VariantFilter();
    }

    public boolean scrollToFirstVariantForChromosome(String chromosome) {
        return backend != null && backend.scrollToFirstVariantForChromosome(chromosome);
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

    // ── Group model ───────────────────────────────────────────────────────────

    static List<GeneGroup> buildGeneGroups(
            List<TableRow> rows,
            boolean groupByChromosome,
            VariantFilter filter,
            boolean expandGroups) {
        List<GeneGroup> out = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return out;
        }

        Map<String, List<TableRow>> groups = new LinkedHashMap<>();
        for (TableRow row : rows) {
            if (row == null || row.node() == null) {
                continue;
            }
            String key = groupByChromosome ? groupKeyChromosome(row) : groupKeyGene(row);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<String> keys = new ArrayList<>(groups.keySet());
        // Stable iteration order only; final gene-list sort is applied after groups are built.

        for (String key : keys) {
            List<TableRow> variantRows = groups.get(key);
            variantRows.sort(Comparator
                .comparing((TableRow r) -> r.chromosome(), String.CASE_INSENSITIVE_ORDER)
                .thenComparingLong(r -> r.node().position));

            List<VariantEntry> entries = new ArrayList<>();
            for (TableRow row : variantRows) {
                List<VariantNode.SampleCall> calls = displayCalls(row, filter);
                if (calls.isEmpty()) {
                    continue;
                }
                entries.add(new VariantEntry(row, calls));
            }
            if (entries.isEmpty()) {
                continue;
            }

            boolean cancer = false;
            String tier = null;
            if (!groupByChromosome) {
                cancer = rowIsCancerGene(entries.get(0).row);
                tier = rowCosmicTier(entries.get(0).row);
            }

            out.add(new GeneGroup(
                key,
                cancer,
                tier,
                entries,
                expandGroups));
        }
        // Default list order is positional; NestedPanel may re-sort on header clicks.
        out.sort(geneComparator(GeneSort.POSITION, true));
        return out;
    }

    enum GeneSort {
        POSITION,
        NAME,
        VARIANT_COUNT
    }

    static Comparator<GeneGroup> geneComparator(GeneSort sort, boolean ascending) {
        Comparator<GeneGroup> comparator = switch (sort != null ? sort : GeneSort.POSITION) {
            case NAME -> Comparator.comparing(
                (GeneGroup g) -> g.name != null ? g.name : "",
                String.CASE_INSENSITIVE_ORDER);
            case VARIANT_COUNT -> Comparator.comparingInt((GeneGroup g) -> g.variants.size());
            case POSITION -> Comparator
                .comparingInt((GeneGroup g) -> chromosomeSortKey(g.sortChromosome()))
                .thenComparing((GeneGroup g) -> g.sortChromosome(), String.CASE_INSENSITIVE_ORDER)
                .thenComparingLong(GeneGroup::sortStart);
        };
        return ascending ? comparator : comparator.reversed();
    }

    /** Numeric-ish chromosome order: 1..22, X, Y, MT, then others alphabetically via secondary key. */
    static int chromosomeSortKey(String chrom) {
        if (chrom == null || chrom.isBlank()) {
            return Integer.MAX_VALUE - 1;
        }
        String c = ChromosomeNames.forDisplay(chrom).toUpperCase(Locale.ROOT);
        if (c.startsWith("CHR")) {
            c = c.substring(3);
        }
        return switch (c) {
            case "X" -> 23;
            case "Y" -> 24;
            case "M", "MT", "MITO" -> 25;
            default -> {
                try {
                    yield Integer.parseInt(c);
                } catch (NumberFormatException ex) {
                    yield 1000 + Math.abs(c.hashCode() % 10000);
                }
            }
        };
    }

    private static List<VariantNode.SampleCall> displayCalls(TableRow row, VariantFilter filter) {
        List<VariantNode.SampleCall> calls = new ArrayList<>();
        for (VariantNode.SampleCall call : row.node().getSamples()) {
            if (call == null) {
                continue;
            }
            if (filter != null && !filter.passesSampleDisplay(row.node(), call)) {
                continue;
            }
            calls.add(call);
        }
        calls.sort(Comparator.comparing(AbstractNestedVariantTable::sampleDisplayName, String.CASE_INSENSITIVE_ORDER));
        return calls;
    }

    private static String groupKeyGene(TableRow row) {
        String gene = rowGeneName(row);
        if (gene != null && !gene.isBlank()) {
            return gene;
        }
        String chrom = row.chromosome();
        return (chrom != null && !chrom.isBlank()) ? chrom : "(unknown)";
    }

    private static String groupKeyChromosome(TableRow row) {
        String chrom = row.chromosome();
        return (chrom != null && !chrom.isBlank()) ? chrom : "(unknown)";
    }


    protected static NestedPanel mountNestedPanel(
            TableView<?> table,
            VariantDetailLayout layout,
            boolean groupByChromosome,
            Consumer<TableRow> onGeneDoubleClick,
            Consumer<TableRow> onPositionClick,
            Supplier<VariantFilter> filterSupplier) {
        if (table == null || !(table.getParent() instanceof StackPane parent)) {
            return null;
        }
        NestedPanel panel = new NestedPanel(
            layout,
            groupByChromosome,
            onGeneDoubleClick,
            onPositionClick,
            filterSupplier);
        parent.getChildren().add(panel.root);
        table.setVisible(false);
        table.setManaged(false);
        return panel;
    }

    // ── Nested UI backend (virtualized ListView) ───────────────────────────────

    protected static final class NestedVariantTableBackend {
        private final TableView<VariantNode> codingTable;
        private final TableView<VariantNode> intronicTable;
        private final TableView<VariantNode> intergenicTable;
        private final Consumer<TableRow> onGeneDoubleClick;
        private final Consumer<TableRow> onPositionClick;
        private final Supplier<VariantFilter> filterSupplier;

        private NestedPanel codingPanel;
        private NestedPanel intronicPanel;
        private NestedPanel intergenicPanel;

        private NestedVariantTableBackend(
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

        void initializeColumns() {
            codingPanel = mount(codingTable, VariantDetailLayout.POINT_CODING, false);
            intronicPanel = mount(intronicTable, VariantDetailLayout.POINT_SIMPLE, false);
            intergenicPanel = mount(intergenicTable, VariantDetailLayout.POINT_SIMPLE, true);
        }

        void setItems(
                ObservableList<TableRow> codingItems,
                ObservableList<TableRow> intronicItems,
                ObservableList<TableRow> intergenicItems,
                boolean expandGroups) {
            if (codingPanel == null || intronicPanel == null || intergenicPanel == null) {
                initializeColumns();
            }
            VariantFilter filter = filterSupplier.get();
            codingPanel.setGroups(buildGeneGroups(
                codingItems != null ? codingItems : List.of(), false, filter, expandGroups));
            intronicPanel.setGroups(buildGeneGroups(
                intronicItems != null ? intronicItems : List.of(), false, filter, expandGroups));
            intergenicPanel.setGroups(buildGeneGroups(
                intergenicItems != null ? intergenicItems : List.of(), true, filter, expandGroups));
        }

        void setPlaceholders(Node codingPlaceholder, Node intronicPlaceholder, Node intergenicPlaceholder) {
            if (codingPanel == null || intronicPanel == null || intergenicPanel == null) {
                initializeColumns();
            }
            codingPanel.setPlaceholder(codingPlaceholder);
            intronicPanel.setPlaceholder(intronicPlaceholder);
            intergenicPanel.setPlaceholder(intergenicPlaceholder);
        }

        boolean scrollToFirstVariantForChromosome(String chromosome) {
            if (chromosome == null || chromosome.isBlank()) {
                return false;
            }
            boolean scrolled = false;
            scrolled |= codingPanel != null && codingPanel.expandFirstMatchingChromosome(chromosome);
            scrolled |= intronicPanel != null && intronicPanel.expandFirstMatchingChromosome(chromosome);
            scrolled |= intergenicPanel != null && intergenicPanel.expandFirstMatchingChromosome(chromosome);
            return scrolled;
        }

        private NestedPanel mount(
                TableView<?> table,
                VariantDetailLayout layout,
                boolean groupByChromosome) {
            return AbstractNestedVariantTable.mountNestedPanel(
                table, layout, groupByChromosome, onGeneDoubleClick, onPositionClick, filterSupplier);
        }
    }

    protected static final class NestedPanel {
        private static final double GUTTER_WIDTH = 16;
        /** Shared gap: row spacing and column separators. */
        private static final double COL_SEP_WIDTH = 4;
        private static final double COL_NUM = 36;
        private static final double COL_NAME = 120;
        private static final double COL_CHR = 48;
        private static final double COL_POS = 110;
        private static final double COL_TYPES = 200;
        private static final double COL_COUNT = 64;
        private static final double COL_SAMPLES = 72;
        private static final double COL_AF = 70;
        private static final double COL_REFALT = 88;
        private static final double COL_TYPE = 52;
        private static final double COL_EFFECT = 100;
        private static final double COL_AA = 88;
        private static final double COL_GQ = 52;
        private static final double COL_GT = 64;
        private static final double COL_SAMPLE = 150;
        private static final double COL_DP = 56;

        private static final double TOGGLE_SIZE = 18;
        /** Above this many filtered variants, only the viewport window is materialized. */
        private static final int VIRTUALIZE_AFTER = 48;
        private static final int VIRTUAL_OVERSCAN = 10;
        private static final double EST_VARIANT_ROW = 30;
        private static final double EST_SAMPLE_ROW = 28;
        private static final double EST_INLINE_HEADER = 28;
        private static final double BRANCH_CHILD_GAP = 4;

        private final VariantDetailLayout layout;
        private final boolean groupByChromosome;
        private final Consumer<TableRow> onGeneDoubleClick;
        private final Consumer<TableRow> onPositionClick;
        private final Supplier<VariantFilter> filterSupplier;

        private final ListView<GeneGroup> list = new ListView<>();
        private final ObservableList<GeneGroup> items = FXCollections.observableArrayList();
        private final VBox root = new VBox();
        private final StackPane listStack = new StackPane();
        private final VBox pinBar = new VBox(0);
        private final HBox geneHeader;
        private final Label emptyLabel = new Label("No variants");
        private final HBox sortNameHeader;
        private final HBox sortPosHeader;
        private final HBox sortVarsHeader;
        private final Label sortNameArrow;
        private final Label sortPosArrow;
        private final Label sortVarsArrow;

        private GeneSort sortMode = GeneSort.POSITION;
        private boolean sortAscending = true;
        private GeneGroup pinnedGroup;
        private VirtualFlow<?> pinnedFlow;
        private boolean pinScrollBarsWired;
        /** Prefer keeping this variant inside the virtual window across a rebuild. */
        private VariantEntry pendingWindowAnchor;
        private boolean virtualWindowRefreshQueued;
        private final ColumnWidths widths;

        private NestedPanel(
                VariantDetailLayout layout,
                boolean groupByChromosome,
                Consumer<TableRow> onGeneDoubleClick,
                Consumer<TableRow> onPositionClick,
                Supplier<VariantFilter> filterSupplier) {
            this.layout = layout != null ? layout : VariantDetailLayout.POINT_SIMPLE;
            this.groupByChromosome = groupByChromosome;
            this.onGeneDoubleClick = onGeneDoubleClick;
            this.onPositionClick = onPositionClick;
            this.filterSupplier = filterSupplier;
            this.widths = new ColumnWidths(this.layout);

            emptyLabel.getStyleClass().add("vn-empty-label");
            emptyLabel.setWrapText(true);

            sortNameArrow = sortArrowLabel();
            sortPosArrow = sortArrowLabel();
            sortVarsArrow = sortArrowLabel();
            sortNameHeader = sortableHeader("Gene / Chrom", widths.geneName, GeneSort.NAME, sortNameArrow);
            sortPosHeader = sortableHeader("Chr", widths.chr, GeneSort.POSITION, sortPosArrow);
            sortVarsHeader = sortableHeader("Vars", widths.vars, GeneSort.VARIANT_COUNT, sortVarsArrow);

            geneHeader = buildGeneHeader();
            refreshSortHeaderLabels();

            VBox stickyHeaders = new VBox(0);
            stickyHeaders.getStyleClass().add("vn-sticky-headers");
            stickyHeaders.getChildren().add(geneHeader);

            pinBar.getStyleClass().add("vn-pin-bar");
            pinBar.setVisible(false);
            pinBar.setManaged(false);
            pinBar.setMaxWidth(Double.MAX_VALUE);
            pinBar.setMaxHeight(Region.USE_PREF_SIZE);
            StackPane.setAlignment(pinBar, Pos.TOP_CENTER);
            // Keep wheel scrolling working while the pointer is over the pinned gene row.
            pinBar.addEventFilter(ScrollEvent.SCROLL, e -> {
                list.fireEvent(e.copyFor(e.getSource(), list));
                e.consume();
            });

            list.getStyleClass().addAll("vn-thread-list", "variant-nested-scroll");
            list.setItems(items);
            list.setFocusTraversable(false);
            // Selection is unused; disabling it avoids JavaFX IndexOutOfBoundsException in
            // ListViewBehavior.clearAndSelect when clicks race with clearSelection listeners.
            list.setSelectionModel(new NoSelectionModel<>());
            list.setCellFactory(lv -> new GeneThreadCell());
            list.setPlaceholder(emptyLabel);
            wirePinScrollTracking();

            listStack.getChildren().addAll(list, pinBar);
            VBox.setVgrow(listStack, Priority.ALWAYS);

            root.getStyleClass().add("variant-nested-container");
            root.getChildren().addAll(stickyHeaders, listStack);
        }

        void setGroups(List<GeneGroup> next) {
            items.setAll(next != null ? next : List.of());
            applySort();
            list.refresh();
            Platform.runLater(this::updatePinnedHeader);
        }

        private void setSort(GeneSort mode) {
            if (mode == null) {
                return;
            }
            if (sortMode == mode) {
                sortAscending = !sortAscending;
            } else {
                sortMode = mode;
                // Position and name feel natural ascending; counts often start high→low.
                sortAscending = mode != GeneSort.VARIANT_COUNT;
            }
            applySort();
            refreshSortHeaderLabels();
            list.refresh();
        }

        private void applySort() {
            items.sort(geneComparator(sortMode, sortAscending));
        }

        private void refreshSortHeaderLabels() {
            applySortArrow(sortNameHeader, sortNameArrow, GeneSort.NAME);
            applySortArrow(sortPosHeader, sortPosArrow, GeneSort.POSITION);
            applySortArrow(sortVarsHeader, sortVarsArrow, GeneSort.VARIANT_COUNT);
        }

        private void applySortArrow(HBox header, Label arrow, GeneSort mode) {
            boolean active = sortMode == mode;
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
            arrow.setPrefWidth(Region.USE_COMPUTED_SIZE);
            arrow.setMaxWidth(Region.USE_PREF_SIZE);
            arrow.setAlignment(Pos.CENTER);
            arrow.setVisible(false);
            arrow.setManaged(false);
            return arrow;
        }

        private HBox sortableHeader(String text, DoubleProperty width, GeneSort mode, Label arrow) {
            Label textLabel = new Label(text);
            textLabel.getStyleClass().add("vn-header-label");
            textLabel.setAlignment(Pos.CENTER_LEFT);
            textLabel.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(textLabel, Priority.ALWAYS);

            HBox box = new HBox(6);
            box.getStyleClass().addAll("vn-header-sortable", "vn-col");
            box.setAlignment(Pos.CENTER_LEFT);
            box.setMaxHeight(Double.MAX_VALUE);
            bindWidth(box, width);
            box.getChildren().addAll(textLabel, arrow);
            box.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    setSort(mode);
                    e.consume();
                }
            });
            return box;
        }

        void setPlaceholder(Node node) {
            if (node != null) {
                emptyLabel.setText("");
                emptyLabel.setGraphic(node);
            } else {
                emptyLabel.setGraphic(null);
                emptyLabel.setText("No variants");
            }
            list.setPlaceholder(emptyLabel);
        }

        boolean expandFirstMatchingChromosome(String chromosome) {
            for (int i = 0; i < items.size(); i++) {
                GeneGroup group = items.get(i);
                for (VariantEntry entry : group.variants) {
                    if (chromosome.equals(entry.row.chromosome())) {
                        ensureDescription(group);
                        group.expanded = true;
                        list.scrollTo(i);
                        Platform.runLater(list::refresh);
                        return true;
                    }
                }
            }
            return false;
        }

        private void ensureDescription(GeneGroup group) {
            if (group == null || group.descriptionLoaded || groupByChromosome) {
                return;
            }
            group.descriptionLoaded = true;
            String description = AnnotationData.getGeneDescription(group.name);
            group.description = description != null ? description : "";
        }

        private void refreshGroup(GeneGroup group, boolean scrollToGene) {
            scheduleRebuild(group, null, scrollToGene);
        }

        /**
         * Rebuild after expand/collapse. The clicked row should stay put — content below
         * grows/shrinks under it. VirtualFlow often jumps when a cell's height changes; we
         * capture the row's screen Y and restore it after rebuild.
         */
        private void scheduleRebuild(GeneGroup group, VariantEntry focusVariant, boolean geneRowAnchor) {
            if (group == null) {
                return;
            }
            int geneIndex = items.indexOf(group);
            pendingWindowAnchor = focusVariant;

            Node anchor = focusVariant != null
                ? findNodeByUserData(list, focusVariant)
                : (geneRowAnchor ? findGeneRowNode(group) : null);
            Double preservedSceneY = null;
            Bounds viewport = listViewportSceneBounds();
            if (anchor != null && viewport != null && anchor.getScene() != null) {
                Bounds b = anchor.localToScene(anchor.getBoundsInLocal());
                // Only preserve when the clicked row is on-screen.
                if (b.getMaxY() > viewport.getMinY() && b.getMinY() < viewport.getMaxY()) {
                    preservedSceneY = b.getMinY();
                }
            }

            boolean collapsingGene = geneRowAnchor && !group.expanded;
            if (!group.expanded || group == pinnedGroup) {
                clearPinnedHeader();
            }

            final Double sceneY = preservedSceneY;
            Platform.runLater(() -> {
                rebuildGeneCell(group);
                Platform.runLater(() -> {
                    list.applyCss();
                    list.layout();
                    if (sceneY != null) {
                        restoreAnchorSceneY(group, focusVariant, geneRowAnchor, sceneY);
                        Platform.runLater(() -> {
                            list.layout();
                            restoreAnchorSceneY(group, focusVariant, geneRowAnchor, sceneY);
                            updatePinnedHeader();
                        });
                    } else if (collapsingGene && geneIndex >= 0) {
                        // Collapsed while scrolled deep in variants — gene was off-screen.
                        VirtualFlow<?> flow = ensureVirtualFlow();
                        if (flow != null) {
                            flow.scrollToTop(geneIndex);
                        } else {
                            list.scrollTo(geneIndex);
                        }
                        updatePinnedHeader();
                    } else {
                        updatePinnedHeader();
                    }
                });
            });
        }

        /** Put {@code anchor} back at the same scene Y it had before the rebuild. */
        private void restoreAnchorSceneY(
                GeneGroup group,
                VariantEntry focusVariant,
                boolean geneRowAnchor,
                double sceneY) {
            for (int pass = 0; pass < 6; pass++) {
                list.layout();
                Node anchor = focusVariant != null
                    ? findNodeByUserData(list, focusVariant)
                    : (geneRowAnchor ? findGeneRowNode(group) : null);
                if (anchor == null || anchor.getScene() == null) {
                    return;
                }
                double newY = anchor.localToScene(anchor.getBoundsInLocal()).getMinY();
                double delta = newY - sceneY;
                if (Math.abs(delta) < 2) {
                    return;
                }
                double moved = scrollListByPixels(delta);
                if (moved == 0.0) {
                    VirtualFlow<?> flow = ensureVirtualFlow();
                    if (flow == null) {
                        return;
                    }
                    flow.setPosition(clamp01(flow.getPosition() + (delta > 0 ? 0.05 : -0.05)));
                }
            }
        }

        private Node findGeneRowNode(GeneGroup group) {
            for (Node node : list.lookupAll(".list-cell")) {
                if (node instanceof ListCell<?> cell && cell.getItem() == group) {
                    Node geneRow = findFirstWithStyleClass(cell, "vn-node-gene");
                    return geneRow != null ? geneRow : cell;
                }
            }
            return null;
        }

        private VirtualFlow<?> ensureVirtualFlow() {
            if (pinnedFlow != null) {
                return pinnedFlow;
            }
            Node flow = list.lookup(".virtual-flow");
            if (flow instanceof VirtualFlow<?> vf) {
                pinnedFlow = vf;
                return vf;
            }
            return null;
        }

        private void rebuildGeneCell(GeneGroup group) {
            for (Node node : list.lookupAll(".list-cell")) {
                if (node instanceof ListCell<?> cell && cell.getItem() == group) {
                    int rowNumber = Math.max(1, cell.getIndex() + 1);
                    cell.setGraphic(buildGeneNode(group, filterSupplier.get(), rowNumber));
                    return;
                }
            }
            list.refresh();
        }

        private static Node findFirstWithStyleClass(Node root, String styleClass) {
            if (root == null || styleClass == null) {
                return null;
            }
            if (root.getStyleClass().contains(styleClass)) {
                return root;
            }
            if (root instanceof javafx.scene.Parent parent) {
                for (Node child : parent.getChildrenUnmodifiable()) {
                    Node found = findFirstWithStyleClass(child, styleClass);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        private static double clamp01(double v) {
            return Math.max(0.0, Math.min(1.0, v));
        }

        /** @return pixels actually moved by VirtualFlow (0 if scroll was a no-op). */
        private double scrollListByPixels(double deltaPixels) {
            if (Math.abs(deltaPixels) < 1) {
                return 0;
            }
            VirtualFlow<?> flow = ensureVirtualFlow();
            if (flow == null) {
                return 0;
            }
            // Positive delta moves content up (toward list end).
            return flow.scrollPixels(deltaPixels);
        }

        private static Node findNodeByUserData(Node root, Object data) {
            if (root == null || data == null) {
                return null;
            }
            if (data.equals(root.getUserData())) {
                return root;
            }
            if (root instanceof javafx.scene.Parent parent) {
                for (Node child : parent.getChildrenUnmodifiable()) {
                    Node found = findNodeByUserData(child, data);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        private void wirePinScrollTracking() {
            list.addEventFilter(ScrollEvent.ANY, e -> {
                requestVirtualWindowRefresh();
                Platform.runLater(this::updatePinnedHeader);
            });
            list.heightProperty().addListener((obs, o, n) -> {
                requestVirtualWindowRefresh();
                Platform.runLater(this::updatePinnedHeader);
            });
            list.skinProperty().addListener((obs, o, n) -> Platform.runLater(this::attachVirtualFlowPinListeners));
            Platform.runLater(this::attachVirtualFlowPinListeners);
        }

        private void attachVirtualFlowPinListeners() {
            Node flow = list.lookup(".virtual-flow");
            if (flow instanceof VirtualFlow<?> vf && vf != pinnedFlow) {
                pinnedFlow = vf;
                vf.positionProperty().addListener((obs, o, n) -> {
                    requestVirtualWindowRefresh();
                    updatePinnedHeader();
                });
            }
            if (!pinScrollBarsWired) {
                for (Node n : list.lookupAll(".scroll-bar")) {
                    if (n instanceof ScrollBar bar
                            && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                        bar.valueProperty().addListener((obs, o, v) -> {
                            requestVirtualWindowRefresh();
                            updatePinnedHeader();
                        });
                        pinScrollBarsWired = true;
                    }
                }
            }
        }

        private void requestVirtualWindowRefresh() {
            if (virtualWindowRefreshQueued) {
                return;
            }
            virtualWindowRefreshQueued = true;
            Platform.runLater(() -> {
                virtualWindowRefreshQueued = false;
                refreshVirtualVariantWindows();
            });
        }

        private void refreshVirtualVariantWindows() {
            for (Node node : list.lookupAll(".vn-virtual-variants")) {
                if (node instanceof VirtualVariantBranch branch) {
                    branch.refreshWindow(false);
                }
            }
        }

        private void updatePinnedHeader() {
            GeneGroup candidate = findPinnedGene();
            if (candidate == null || !candidate.expanded) {
                clearPinnedHeader();
                return;
            }
            if (candidate == pinnedGroup && pinBar.isVisible()) {
                return;
            }
            showPinnedHeader(candidate);
        }

        private GeneGroup findPinnedGene() {
            Bounds viewport = listViewportSceneBounds();
            if (viewport == null) {
                return null;
            }
            GeneGroup best = null;
            double bestMinY = Double.NEGATIVE_INFINITY;
            for (Node node : list.lookupAll(".list-cell")) {
                if (!(node instanceof ListCell<?> cell) || cell.isEmpty()) {
                    continue;
                }
                Object item = cell.getItem();
                if (!(item instanceof GeneGroup group) || !group.expanded) {
                    continue;
                }
                Bounds cellBounds = cell.localToScene(cell.getBoundsInLocal());
                // Gene cell has scrolled under the top, but its body is still in view.
                if (cellBounds.getMinY() < viewport.getMinY() - 1
                        && cellBounds.getMaxY() > viewport.getMinY() + 24) {
                    if (cellBounds.getMinY() > bestMinY) {
                        bestMinY = cellBounds.getMinY();
                        best = group;
                    }
                }
            }
            return best;
        }

        private Bounds listViewportSceneBounds() {
            Node clipped = list.lookup(".clipped-container");
            if (clipped != null) {
                return clipped.localToScene(clipped.getBoundsInLocal());
            }
            if (list.getScene() == null) {
                return null;
            }
            return list.localToScene(list.getBoundsInLocal());
        }

        private void showPinnedHeader(GeneGroup group) {
            pinnedGroup = group;
            int rowNumber = items.indexOf(group) + 1;
            if (rowNumber <= 0) {
                rowNumber = 1;
            }
            VariantFilter filter = filterSupplier.get();
            HBox geneRow = buildGeneContent(group, filter, rowNumber);
            if (!geneRow.getStyleClass().contains("vn-node-pinned")) {
                geneRow.getStyleClass().add("vn-node-pinned");
            }
            // Only pin the gene row; variant column header scrolls with the variants.
            pinBar.getChildren().setAll(geneRow);
            pinBar.setVisible(true);
            pinBar.setManaged(true);
        }

        private void clearPinnedHeader() {
            pinnedGroup = null;
            pinBar.getChildren().clear();
            pinBar.setVisible(false);
            pinBar.setManaged(false);
        }

        private HBox buildGeneHeader() {
            HBox header = new HBox(0);
            header.getStyleClass().addAll("vn-header", "vn-header-gene");
            header.setAlignment(Pos.CENTER_LEFT);
            header.getChildren().add(headerSpacer(TOGGLE_SIZE));
            ColAppender cols = new ColAppender(header, true);
            cols.add(headerLabel("#", widths.num), widths.num);
            cols.add(sortNameHeader, widths.geneName);
            cols.add(sortPosHeader, widths.chr);
            cols.add(sortVarsHeader, widths.vars);
            cols.add(headerLabel("Types", widths.types), widths.types);
            cols.add(headerLabel("Samples", widths.samples), widths.samples);
            cols.add(headerLabel("AF", widths.af), widths.af);
            cols.addGrow(headerGrow("Description"));
            return header;
        }

        /** Column labels for variant rows; sits under an expanded gene row. */
        private HBox buildVariantHeader() {
            HBox header = new HBox(0);
            header.getStyleClass().addAll("vn-header", "vn-header-variant", "vn-header-inline");
            header.setAlignment(Pos.CENTER_LEFT);
            header.getChildren().add(headerSpacer(TOGGLE_SIZE));
            ColAppender cols = new ColAppender(header, true);
            if (layout == VariantDetailLayout.STRUCTURAL) {
                cols.add(headerLabel("Position", widths.pos), widths.pos);
                cols.add(headerLabel("Length", widths.refAlt), widths.refAlt);
                cols.add(headerLabel("Type", widths.type), widths.type);
                cols.add(headerLabel("Mate", widths.effect), widths.effect);
                cols.add(headerLabel("Samples", widths.samples), widths.samples);
                cols.add(headerLabel("AF", widths.af), widths.af);
                cols.add(headerLabel("QUAL", widths.gq), widths.gq);
                cols.addGrow(headerGrow(""));
            } else {
                cols.add(headerLabel("Position", widths.pos), widths.pos);
                cols.add(headerLabel("Ref/Alt", widths.refAlt), widths.refAlt);
                cols.add(headerLabel("Type", widths.type), widths.type);
                if (layout == VariantDetailLayout.POINT_CODING) {
                    cols.add(headerLabel("Effect", widths.effect), widths.effect);
                    cols.add(headerLabel("AA", widths.aa), widths.aa);
                }
                cols.add(headerLabel("Samples", widths.samples), widths.samples);
                cols.add(headerLabel("AF", widths.af), widths.af);
                cols.add(headerLabel("GQ", widths.gq), widths.gq);
                cols.addGrow(headerGrow(""));
            }
            return header;
        }

        /** Column labels for sample call rows; sits under an expanded variant row. */
        private HBox buildSampleHeader() {
            HBox header = new HBox(0);
            header.getStyleClass().addAll("vn-header", "vn-header-sample", "vn-header-inline");
            header.setAlignment(Pos.CENTER_LEFT);
            header.getChildren().add(headerSpacer(GUTTER_WIDTH));
            ColAppender cols = new ColAppender(header, true);
            cols.add(headerLabel("Sample", widths.sampleName), widths.sampleName);
            cols.add(headerLabel("GT", widths.gt), widths.gt);
            cols.add(headerLabel("AF", widths.af), widths.af);
            cols.add(headerLabel("GQ", widths.gq), widths.gq);
            cols.add(headerLabel("DP", widths.dp), widths.dp);
            cols.addGrow(headerGrow(""));
            return header;
        }

        /** Full-height background strip between columns (matches row gap). */
        private static Region colSep() {
            Region sep = new Region();
            sep.getStyleClass().add("vn-col-sep");
            sep.setMinWidth(COL_SEP_WIDTH);
            sep.setPrefWidth(COL_SEP_WIDTH);
            sep.setMaxWidth(COL_SEP_WIDTH);
            sep.setMaxHeight(Double.MAX_VALUE);
            return sep;
        }

        private static Region headerSpacer(double width) {
            Region region = new Region();
            fixWidth(region, width);
            return region;
        }

        private Label headerLabel(String text, DoubleProperty width) {
            Label label = new Label(text);
            label.getStyleClass().addAll("vn-header-label", "vn-col");
            bindWidth(label, width);
            label.setAlignment(Pos.CENTER_LEFT);
            label.setMaxHeight(Double.MAX_VALUE);
            return label;
        }

        private static Label headerGrow(String text) {
            Label label = new Label(text);
            label.getStyleClass().addAll("vn-header-label", "vn-col");
            label.setMaxWidth(Double.MAX_VALUE);
            label.setMaxHeight(Double.MAX_VALUE);
            HBox.setHgrow(label, Priority.ALWAYS);
            label.setAlignment(Pos.CENTER_LEFT);
            return label;
        }

        private static void bindWidth(Region node, DoubleProperty width) {
            bindWidthStatic(node, width);
        }

        /** Mutable column widths shared by headers and data rows. */
        private static final class ColumnWidths {
            final DoubleProperty num = new SimpleDoubleProperty(COL_NUM);
            final DoubleProperty geneName = new SimpleDoubleProperty(COL_NAME);
            final DoubleProperty chr = new SimpleDoubleProperty(COL_CHR);
            final DoubleProperty vars = new SimpleDoubleProperty(COL_COUNT);
            final DoubleProperty types = new SimpleDoubleProperty(COL_TYPES);
            final DoubleProperty samples = new SimpleDoubleProperty(COL_SAMPLES);
            final DoubleProperty af = new SimpleDoubleProperty(COL_AF);
            final DoubleProperty pos = new SimpleDoubleProperty(COL_POS);
            final DoubleProperty refAlt = new SimpleDoubleProperty(COL_REFALT);
            final DoubleProperty type = new SimpleDoubleProperty(COL_TYPE);
            final DoubleProperty effect = new SimpleDoubleProperty(COL_EFFECT);
            final DoubleProperty aa = new SimpleDoubleProperty(COL_AA);
            final DoubleProperty gq = new SimpleDoubleProperty(COL_GQ);
            final DoubleProperty sampleName = new SimpleDoubleProperty(COL_SAMPLE);
            final DoubleProperty gt = new SimpleDoubleProperty(COL_GT);
            final DoubleProperty dp = new SimpleDoubleProperty(COL_DP);

            ColumnWidths(VariantDetailLayout layout) {
                if (layout == VariantDetailLayout.STRUCTURAL) {
                    pos.set(COL_POS + 40);
                }
            }
        }

        private static void wireColumnResize(Region sep, DoubleProperty width) {
            if (sep == null || width == null) {
                return;
            }
            // Keep the same layout width as data-row seps so columns stay aligned.
            sep.getStyleClass().add("vn-col-sep-resize");
            sep.setCursor(Cursor.H_RESIZE);
            final double min = Math.max(28, width.get() * 0.35);
            sep.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                if (e.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                sep.setUserData(e.getSceneX());
                e.consume();
            });
            sep.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
                if (e.getButton() != MouseButton.PRIMARY || !(sep.getUserData() instanceof Number start)) {
                    return;
                }
                double dx = e.getSceneX() - start.doubleValue();
                sep.setUserData(e.getSceneX());
                width.set(Math.max(min, width.get() + dx));
                e.consume();
            });
            sep.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
        }

        /** Appends columns with separators; optional drag-resize on header separators. */
        private final class ColAppender {
            private final HBox row;
            private final boolean resizable;
            private DoubleProperty prevWidth;
            private boolean addedColumn;

            ColAppender(HBox row, boolean resizable) {
                this.row = row;
                this.resizable = resizable;
            }

            void add(Node col, DoubleProperty width) {
                if (addedColumn) {
                    Region sep = colSep();
                    if (resizable && prevWidth != null) {
                        wireColumnResize(sep, prevWidth);
                    }
                    row.getChildren().add(sep);
                }
                row.getChildren().add(col);
                prevWidth = width;
                addedColumn = true;
            }

            void addGrow(Node col) {
                if (addedColumn) {
                    Region sep = colSep();
                    if (resizable && prevWidth != null) {
                        wireColumnResize(sep, prevWidth);
                    }
                    row.getChildren().add(sep);
                }
                row.getChildren().add(col);
                prevWidth = null;
                addedColumn = true;
            }
        }

        private Node buildGeneNode(GeneGroup group, VariantFilter filter, int rowNumber) {
            HBox content = buildGeneContent(group, filter, rowNumber);
            if (!group.expanded) {
                return content;
            }
            List<VariantEntry> filtered = new ArrayList<>(group.variants.size());
            for (VariantEntry entry : group.variants) {
                if (passesGeneTypeFilter(group, entry)) {
                    filtered.add(entry);
                }
            }
            VariantEntry windowAnchor = pendingWindowAnchor;
            pendingWindowAnchor = null;

            List<Node> children = new ArrayList<>();
            if (filtered.size() > VIRTUALIZE_AFTER) {
                children.add(new VirtualVariantBranch(group, filter, filtered, windowAnchor));
            } else {
                children.add(buildVariantHeader());
                for (VariantEntry entry : filtered) {
                    children.add(buildVariantNode(group, entry, filter));
                }
            }
            return wrapBranch(content, children, "vn-gutter-gene", () -> {
                group.expanded = false;
                group.typeFilters.clear();
                refreshGroup(group, true);
            });
        }

        private static boolean passesGeneTypeFilter(GeneGroup group, VariantEntry entry) {
            if (group == null || group.typeFilters.isEmpty()) {
                return true;
            }
            return group.typeFilters.contains(EffectBucket.of(rowEffect(entry.row)));
        }

        private HBox buildTypeCounts(GeneGroup group) {
            int trunc = 0;
            int mis = 0;
            int syn = 0;
            int other = 0;
            for (VariantEntry entry : group.variants) {
                switch (EffectBucket.of(rowEffect(entry.row))) {
                    case TRUNCATING -> trunc++;
                    case MISSENSE -> mis++;
                    case SYNONYMOUS -> syn++;
                    case NONCODING -> other++;
                }
            }

            HBox box = new HBox(6);
            box.getStyleClass().add("vn-col");
            box.setAlignment(Pos.CENTER_LEFT);
            box.setMaxHeight(Double.MAX_VALUE);
            bindWidth(box, widths.types);

            addTypeChip(box, group, EffectBucket.TRUNCATING, trunc);
            addTypeChip(box, group, EffectBucket.MISSENSE, mis);
            addTypeChip(box, group, EffectBucket.SYNONYMOUS, syn);
            addTypeChip(box, group, EffectBucket.NONCODING, other);
            if (box.getChildren().isEmpty()) {
                Label empty = new Label("—");
                empty.getStyleClass().add("vn-summary");
                box.getChildren().add(empty);
            }
            return box;
        }

        private void addTypeChip(HBox box, GeneGroup group, EffectBucket bucket, int count) {
            if (count <= 0) {
                return;
            }
            boolean filtered = !group.typeFilters.isEmpty();
            boolean active = group.typeFilters.contains(bucket);

            Region swatch = new Region();
            swatch.getStyleClass().add("vn-type-swatch");
            swatch.setStyle("-fx-background-color: " + bucket.color + ";");

            Label countLabel = new Label(String.valueOf(count));
            countLabel.getStyleClass().add("vn-type-count");

            HBox chip = new HBox(4, swatch, countLabel);
            chip.getStyleClass().add("vn-type-chip");
            if (active) {
                chip.getStyleClass().add("vn-type-chip-active");
            } else if (filtered) {
                chip.getStyleClass().add("vn-type-chip-muted");
            }
            chip.setAlignment(Pos.CENTER_LEFT);
            Tooltip.install(chip, new Tooltip(bucket.tooltip
                + (active ? " (filter on — click to clear)" : " (click to filter this gene)")));
            chip.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                if (e.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                if (group.typeFilters.contains(bucket)) {
                    group.typeFilters.remove(bucket);
                } else {
                    group.typeFilters.add(bucket);
                }
                if (!group.expanded) {
                    ensureDescription(group);
                    group.expanded = true;
                }
                scheduleRebuild(group, null, true);
                e.consume();
            });
            box.getChildren().add(chip);
        }

        private HBox buildGeneContent(GeneGroup group, VariantFilter filter, int rowNumber) {
            HBox row = new HBox(0);
            row.getStyleClass().addAll("vn-node", "vn-node-gene");
            if (group.cancer) {
                row.getStyleClass().add("vn-node-gene-cancer");
            }
            row.setAlignment(Pos.CENTER_LEFT);

            Label toggle = buildToggle(group.expanded);
            toggle.setMaxHeight(Region.USE_PREF_SIZE);

            int sampleUnion = countUnionSamples(group.variants, filter);
            String af = formatGroupAlleleFractions(group.variants, filter);

            HBox nameBox = new HBox(4);
            nameBox.getStyleClass().add("vn-col");
            nameBox.setAlignment(Pos.CENTER_LEFT);
            nameBox.setMaxHeight(Double.MAX_VALUE);
            bindWidth(nameBox, widths.geneName);
            Label name = new Label(group.name);
            name.getStyleClass().add("vn-title");
            if (group.cancer) {
                name.getStyleClass().add("vn-title-cancer");
            }
            name.setEllipsisString("…");
            name.maxWidthProperty().bind(widths.geneName);
            nameBox.getChildren().add(name);
            if (group.cancer && group.tier != null && !group.tier.isBlank()) {
                Label tier = new Label("T" + group.tier);
                tier.getStyleClass().add("vn-tier-badge");
                nameBox.getChildren().add(tier);
            }

            row.getChildren().add(toggle);
            ColAppender cols = new ColAppender(row, false);
            cols.add(fixedCol(String.valueOf(rowNumber), widths.num, "vn-meta"), widths.num);
            cols.add(nameBox, widths.geneName);
            cols.add(fixedCol(formatGeneChromosome(group), widths.chr, "vn-summary"), widths.chr);
            cols.add(fixedCol(String.valueOf(group.variants.size()), widths.vars, "vn-summary"), widths.vars);
            cols.add(buildTypeCounts(group), widths.types);
            cols.add(fixedCol(String.valueOf(sampleUnion), widths.samples, "vn-summary"), widths.samples);
            cols.add(fixedCol(af, widths.af, "vn-summary"), widths.af);
            if (group.descriptionLoaded
                    && group.description != null
                    && !group.description.isBlank()) {
                Label desc = fixedCol(group.description, 0, "vn-desc");
                desc.getStyleClass().add("vn-col");
                desc.setMaxWidth(Double.MAX_VALUE);
                desc.setMaxHeight(Double.MAX_VALUE);
                desc.setMinWidth(40);
                HBox.setHgrow(desc, Priority.ALWAYS);
                cols.addGrow(desc);
            } else {
                Region trail = new Region();
                trail.getStyleClass().add("vn-col");
                trail.setMaxHeight(Double.MAX_VALUE);
                HBox.setHgrow(trail, Priority.ALWAYS);
                cols.addGrow(trail);
            }

            wireExpandableRow(row, toggle, () -> {
                if (!group.expanded) {
                    ensureDescription(group);
                }
                group.expanded = !group.expanded;
                if (!group.expanded) {
                    group.typeFilters.clear();
                }
                refreshGroup(group, true);
            }, e -> {
                if (!groupByChromosome
                        && onGeneDoubleClick != null
                        && !group.variants.isEmpty()) {
                    ensureDescription(group);
                    onGeneDoubleClick.accept(group.variants.get(0).row);
                    return true;
                }
                return false;
            });
            return row;
        }

        private Node buildVariantNode(GeneGroup group, VariantEntry entry, VariantFilter filter) {
            HBox content = buildVariantContent(group, entry, filter);
            if (!entry.expanded) {
                return content;
            }
            List<Node> children = new ArrayList<>(entry.calls.size() + 1);
            children.add(buildSampleHeader());
            for (VariantNode.SampleCall call : entry.calls) {
                children.add(buildSampleNode(entry.row, call));
            }
            return wrapBranch(content, children, "vn-gutter-variant", () -> {
                entry.expanded = false;
                scheduleRebuild(group, entry, false);
            });
        }

        private HBox buildEffectColumn(TableRow tableRow, VariantFilter filter, String colorClass) {
            EffectBucket bucket = EffectBucket.of(rowEffect(tableRow));
            Region swatch = new Region();
            swatch.getStyleClass().add("vn-type-swatch");
            swatch.setStyle("-fx-background-color: " + bucket.color + ";");
            swatch.setMaxHeight(Region.USE_PREF_SIZE);

            Label effect = new Label(resolveTableColumnValue(tableRow, "effectDisplay", filter));
            effect.getStyleClass().addAll("vn-summary", colorClass);
            effect.setWrapText(false);
            effect.setEllipsisString("…");
            effect.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(effect, Priority.ALWAYS);

            HBox box = new HBox(4, swatch, effect);
            box.getStyleClass().add("vn-col");
            box.setAlignment(Pos.CENTER_LEFT);
            box.setMaxHeight(Double.MAX_VALUE);
            bindWidth(box, widths.effect);
            return box;
        }

        private HBox buildVariantContent(GeneGroup group, VariantEntry entry, VariantFilter filter) {
            HBox row = new HBox(0);
            row.getStyleClass().addAll("vn-node", "vn-node-variant");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setUserData(entry);

            TableRow tableRow = entry.row;
            String colorClass = effectStyleClass(tableRow);

            Label toggle = buildToggle(entry.expanded);
            toggle.setMaxHeight(Region.USE_PREF_SIZE);

            row.getChildren().add(toggle);
            ColAppender cols = new ColAppender(row, false);
            if (layout == VariantDetailLayout.STRUCTURAL) {
                Label pos = fixedCol(formatSvPosition(tableRow), widths.pos, "vn-title");
                pos.getStyleClass().add(colorClass);
                Label length = fixedCol(formatSvLength(tableRow), widths.refAlt, "vn-summary");
                length.getStyleClass().add(colorClass);
                Label type = fixedCol(VariantTypeVisuals.shortLabel(tableRow.node().type), widths.type, "vn-summary");
                type.getStyleClass().add(colorClass);
                Label mate = fixedCol(formatSvMate(tableRow), widths.effect, "vn-summary");
                mate.getStyleClass().add(colorClass);
                cols.add(pos, widths.pos);
                cols.add(length, widths.refAlt);
                cols.add(type, widths.type);
                cols.add(mate, widths.effect);
            } else {
                Label pos = fixedCol(resolveTableColumnValue(tableRow, "position", filter), widths.pos, "vn-title");
                pos.getStyleClass().add(colorClass);
                Label refAlt = fixedCol(
                    resolveTableColumnValue(tableRow, "refAlt", filter), widths.refAlt, "vn-summary");
                refAlt.getStyleClass().add(colorClass);
                Label type = fixedCol(
                    resolveTableColumnValue(tableRow, "variantType", filter), widths.type, "vn-summary");
                type.getStyleClass().add(colorClass);
                cols.add(pos, widths.pos);
                cols.add(refAlt, widths.refAlt);
                cols.add(type, widths.type);
                if (layout == VariantDetailLayout.POINT_CODING) {
                    cols.add(buildEffectColumn(tableRow, filter, colorClass), widths.effect);
                    Label aa = fixedCol(
                        resolveTableColumnValue(tableRow, "aaChange", filter), widths.aa, "vn-summary");
                    aa.getStyleClass().add(colorClass);
                    cols.add(aa, widths.aa);
                }
            }
            Label samples = fixedCol(String.valueOf(entry.calls.size()), widths.samples, "vn-summary");
            samples.getStyleClass().add(colorClass);
            Label af = fixedCol(formatAlleleFractions(tableRow.node(), filter), widths.af, "vn-summary");
            af.getStyleClass().add(colorClass);
            Label gq = fixedCol(
                layout == VariantDetailLayout.STRUCTURAL
                    ? formatSvQual(tableRow)
                    : resolveTableColumnValue(tableRow, "maxQuality", filter),
                widths.gq, "vn-summary");
            gq.getStyleClass().add(colorClass);
            cols.add(samples, widths.samples);
            cols.add(af, widths.af);
            cols.add(gq, widths.gq);
            Region trail = new Region();
            trail.getStyleClass().add("vn-col");
            trail.setMaxHeight(Double.MAX_VALUE);
            HBox.setHgrow(trail, Priority.ALWAYS);
            cols.addGrow(trail);

            wireExpandableRow(row, toggle, () -> {
                entry.expanded = !entry.expanded;
                // Keep this row's screen position; samples push content below it.
                scheduleRebuild(group, entry, false);
            }, e -> {
                if (onPositionClick != null) {
                    onPositionClick.accept(tableRow);
                    return true;
                }
                return false;
            });
            return row;
        }

        private HBox buildSampleNode(TableRow row, VariantNode.SampleCall call) {
            HBox sampleRow = new HBox(0);
            sampleRow.getStyleClass().addAll("vn-node", "vn-node-sample");
            sampleRow.setAlignment(Pos.CENTER_LEFT);

            Region pad = new Region();
            pad.setMinWidth(GUTTER_WIDTH);
            pad.setPrefWidth(GUTTER_WIDTH);
            pad.setMaxWidth(GUTTER_WIDTH);

            VariantFilter activeFilter = filterSupplier != null ? filterSupplier.get() : null;
            String rawGt = call.gt != null && !call.gt.isBlank() ? call.gt : "—";
            String gt = rawGt;
            if (activeFilter != null && activeFilter.isLohMode()) {
                String loh = row.node().lohAlleleClass(call);
                if (loh != null) {
                    gt = loh;
                }
            }
            String af = call.alleleFraction >= 0
                ? String.format(Locale.ROOT, "%.2f", call.alleleFraction) : "—";
            String gq = call.quality >= 0
                ? String.format(Locale.ROOT, "%.0f", call.quality) : "—";
            String dp = call.depth >= 0 ? String.valueOf(call.depth) : "—";

            Label name = fixedCol(sampleDisplayName(call), widths.sampleName, "vn-title");
            Label gtLabel = fixedCol(gt, widths.gt, "vn-cell-gt");
            if (!gt.equals(rawGt)) {
                Tooltip.install(gtLabel, new Tooltip("LOH " + gt + "  (GT " + rawGt + ")"));
            }
            Label afLabel = fixedCol(af, widths.af, "vn-cell-af");
            Label gqLabel = fixedCol(gq, widths.gq, "vn-summary");
            Label dpLabel = fixedCol(dp, widths.dp, "vn-summary");
            Region trail = new Region();
            trail.getStyleClass().add("vn-col");
            trail.setMaxHeight(Double.MAX_VALUE);
            HBox.setHgrow(trail, Priority.ALWAYS);

            sampleRow.getChildren().add(pad);
            ColAppender cols = new ColAppender(sampleRow, false);
            cols.add(name, widths.sampleName);
            cols.add(gtLabel, widths.gt);
            cols.add(afLabel, widths.af);
            cols.add(gqLabel, widths.gq);
            cols.add(dpLabel, widths.dp);
            cols.addGrow(trail);

            sampleRow.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
                if (e.getButton() == MouseButton.PRIMARY
                        && e.getClickCount() >= 2
                        && onPositionClick != null) {
                    onPositionClick.accept(row);
                    e.consume();
                }
            });
            return sampleRow;
        }

        private static VBox wrapBranch(
                Node parentContent,
                List<Node> children,
                String gutterStyleClass,
                Runnable onCollapse) {
            VBox root = new VBox(0);
            root.getStyleClass().add("vn-branch-root");
            root.getChildren().add(parentContent);

            Region gutter = new Region();
            gutter.getStyleClass().addAll("vn-gutter", gutterStyleClass);
            gutter.setMinWidth(GUTTER_WIDTH);
            gutter.setPrefWidth(GUTTER_WIDTH);
            gutter.setMaxWidth(GUTTER_WIDTH);
            gutter.setMaxHeight(Double.MAX_VALUE);
            gutter.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    onCollapse.run();
                    e.consume();
                }
            });

            VBox childrenBox = new VBox(4);
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

        private static void wireExpandableRow(
                HBox row,
                Label toggle,
                Runnable onToggle,
                java.util.function.Function<MouseEvent, Boolean> onDoubleClick) {
            // Use PRESSED so ListView selection does not eat the first click.
            toggle.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                if (e.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                onToggle.run();
                e.consume();
            });
            // Bubble phase: type chips and other functional children consume first.
            // Single-click empty/non-functional areas expand or collapse.
            row.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
                if (e.getButton() != MouseButton.PRIMARY || e.isConsumed()) {
                    return;
                }
                if (e.getClickCount() >= 2) {
                    return;
                }
                onToggle.run();
                e.consume();
            });
            row.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
                if (e.getButton() != MouseButton.PRIMARY || e.isConsumed()) {
                    return;
                }
                if (e.getClickCount() >= 2 && Boolean.TRUE.equals(onDoubleClick.apply(e))) {
                    e.consume();
                }
            });
        }

        /**
         * Viewport-aware variant list for large gene expansions.
         * Off-screen rows are spacers; only the visible window (+ overscan) builds nodes.
         */
        private final class VirtualVariantBranch extends VBox {
            private final GeneGroup group;
            private final VariantFilter filter;
            private final List<VariantEntry> entries;
            private final double[] heights;
            private final Region topSpacer = new Region();
            private final Region bottomSpacer = new Region();
            private int windowFrom = -1;
            private int windowTo = -1;
            private boolean refreshing;

            VirtualVariantBranch(
                    GeneGroup group,
                    VariantFilter filter,
                    List<VariantEntry> entries,
                    VariantEntry preferVisible) {
                super(BRANCH_CHILD_GAP);
                this.group = group;
                this.filter = filter;
                this.entries = entries;
                this.heights = new double[entries.size()];
                for (int i = 0; i < entries.size(); i++) {
                    heights[i] = estimateHeight(entries.get(i));
                }
                getStyleClass().addAll("vn-branch-children", "vn-virtual-variants");
                setFillWidth(true);
                layoutBoundsProperty().addListener((obs, o, n) -> {
                    if (!refreshing) {
                        requestVirtualWindowRefresh();
                    }
                });
                // First paint: prefer anchored variant, else top of list until viewport is known.
                int seed = preferVisible != null ? entries.indexOf(preferVisible) : 0;
                if (seed < 0) {
                    seed = 0;
                }
                int seedTo = Math.min(entries.size(), seed + VIRTUAL_OVERSCAN * 2 + 12);
                rebuildWindow(Math.max(0, seed - VIRTUAL_OVERSCAN), Math.max(seedTo, Math.min(entries.size(), 12)));
                Platform.runLater(() -> refreshWindow(true));
            }

            void refreshWindow(boolean force) {
                if (refreshing || entries.isEmpty() || getScene() == null) {
                    return;
                }
                Bounds viewport = listViewportSceneBounds();
                if (viewport == null) {
                    return;
                }
                Bounds local = sceneToLocal(viewport);
                double viewTop = local.getMinY() - VIRTUAL_OVERSCAN * EST_VARIANT_ROW;
                double viewBot = local.getMaxY() + VIRTUAL_OVERSCAN * EST_VARIANT_ROW;

                Node header = getChildren().isEmpty() ? null : getChildren().get(0);
                double y = (header != null ? Math.max(header.getLayoutBounds().getHeight(), EST_INLINE_HEADER) : 0)
                    + BRANCH_CHILD_GAP;

                int from = 0;
                int to = entries.size();
                boolean started = false;
                for (int i = 0; i < entries.size(); i++) {
                    double h = heights[i];
                    double next = y + h;
                    if (!started && next >= viewTop) {
                        from = i;
                        started = true;
                    }
                    if (y <= viewBot) {
                        to = i + 1;
                    } else if (started) {
                        break;
                    }
                    y = next + BRANCH_CHILD_GAP;
                }
                from = Math.max(0, from - VIRTUAL_OVERSCAN);
                to = Math.min(entries.size(), to + VIRTUAL_OVERSCAN);
                if (!force && from == windowFrom && to == windowTo) {
                    return;
                }
                rebuildWindow(from, to);
            }

            private void rebuildWindow(int from, int to) {
                refreshing = true;
                try {
                    windowFrom = from;
                    windowTo = to;
                    List<Node> next = new ArrayList<>(4 + Math.max(0, to - from));
                    next.add(buildVariantHeader());

                    double top = 0;
                    for (int i = 0; i < from; i++) {
                        top += heights[i];
                        if (i + 1 < from) {
                            top += BRANCH_CHILD_GAP;
                        }
                    }
                    setSpacerHeight(topSpacer, top);
                    if (from > 0) {
                        next.add(topSpacer);
                    }

                    for (int i = from; i < to; i++) {
                        VariantEntry entry = entries.get(i);
                        Node node = buildVariantNode(group, entry, filter);
                        final int index = i;
                        node.layoutBoundsProperty().addListener((obs, o, n) -> {
                            double measured = n.getHeight();
                            if (measured > 1 && Math.abs(measured - heights[index]) > 1) {
                                heights[index] = measured;
                            }
                        });
                        // Seed from estimate; layout listener refines.
                        heights[i] = Math.max(heights[i], estimateHeight(entry));
                        next.add(node);
                    }

                    double bottom = 0;
                    for (int i = to; i < entries.size(); i++) {
                        if (i > to) {
                            bottom += BRANCH_CHILD_GAP;
                        }
                        bottom += heights[i];
                    }
                    setSpacerHeight(bottomSpacer, bottom);
                    if (to < entries.size()) {
                        next.add(bottomSpacer);
                    }

                    getChildren().setAll(next);
                } finally {
                    refreshing = false;
                }
            }

            private static void setSpacerHeight(Region spacer, double height) {
                double h = Math.max(0, height);
                spacer.setMinHeight(h);
                spacer.setPrefHeight(h);
                spacer.setMaxHeight(h);
            }

            private static double estimateHeight(VariantEntry entry) {
                double h = EST_VARIANT_ROW;
                if (entry != null && entry.expanded) {
                    h += BRANCH_CHILD_GAP + EST_INLINE_HEADER;
                    int samples = entry.calls != null ? entry.calls.size() : 0;
                    if (samples > 0) {
                        h += samples * (BRANCH_CHILD_GAP + EST_SAMPLE_ROW);
                    }
                }
                return h;
            }
        }

        /** Virtualized gene row — only visible genes create scene-graph nodes. */
        private final class GeneThreadCell extends ListCell<GeneGroup> {
            {
                getStyleClass().add("vn-thread-cell");
            }

            @Override
            protected void updateItem(GeneGroup group, boolean empty) {
                super.updateItem(group, empty);
                if (empty || group == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                VariantFilter filter = filterSupplier.get();
                setText(null);
                setGraphic(buildGeneNode(group, filter, getIndex() + 1));
            }
        }
    }

    /** ListView selection model that never selects — avoids selection-related FX crashes. */
    private static final class NoSelectionModel<T> extends MultipleSelectionModel<T> {
        private final ObservableList<Integer> indices = FXCollections.observableArrayList();
        private final ObservableList<T> selected = FXCollections.observableArrayList();

        @Override
        public ObservableList<Integer> getSelectedIndices() {
            return indices;
        }

        @Override
        public ObservableList<T> getSelectedItems() {
            return selected;
        }

        @Override public void selectIndices(int index, int... indices) {}
        @Override public void selectAll() {}
        @Override public void selectFirst() {}
        @Override public void selectLast() {}
        @Override public void clearAndSelect(int index) {}
        @Override public void select(int index) {}
        @Override public void select(T obj) {}
        @Override public void clearSelection(int index) {}
        @Override public void clearSelection() {}
        @Override public boolean isSelected(int index) { return false; }
        @Override public boolean isEmpty() { return true; }
        @Override public void selectPrevious() {}
        @Override public void selectNext() {}
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    private static void fixWidth(Region node, double width) {
        node.setMinWidth(width);
        node.setPrefWidth(width);
        node.setMaxWidth(width);
    }

    private static Label fixedCol(String text, double width, String styleClass) {
        Label label = new Label(text == null ? "" : text);
        label.getStyleClass().add(styleClass);
        if (width > 0) {
            label.getStyleClass().add("vn-col");
            fixWidth(label, width);
        }
        label.setWrapText(false);
        label.setEllipsisString("…");
        label.setAlignment(Pos.CENTER_LEFT);
        label.setMaxHeight(Double.MAX_VALUE);
        return label;
    }

    private static Label fixedCol(String text, DoubleProperty width, String styleClass) {
        Label label = new Label(text == null ? "" : text);
        label.getStyleClass().add(styleClass);
        if (width != null) {
            label.getStyleClass().add("vn-col");
            bindWidthStatic(label, width);
        }
        label.setWrapText(false);
        label.setEllipsisString("…");
        label.setAlignment(Pos.CENTER_LEFT);
        label.setMaxHeight(Double.MAX_VALUE);
        return label;
    }

    private static void bindWidthStatic(Region node, DoubleProperty width) {
        node.minWidthProperty().bind(width);
        node.prefWidthProperty().bind(width);
        node.maxWidthProperty().bind(width);
    }

    /** Gene row shows chromosome only; interval/position live on variant rows. */
    private static String formatGeneChromosome(GeneGroup group) {
        if (group == null || group.variants.isEmpty()) {
            return "—";
        }

        GeneLocation loc = AnnotationData.getGeneLocation(group.name);
        if (loc != null && loc.chrom() != null && !loc.chrom().isBlank()) {
            return ChromosomeNames.forDisplay(loc.chrom());
        }

        for (VariantEntry entry : group.variants) {
            TableRow row = entry.row;
            if (row != null && row.chromosome() != null && !row.chromosome().isBlank()) {
                return ChromosomeNames.forDisplay(row.chromosome());
            }
        }
        return "—";
    }


    public static String formatSvPosition(TableRow row) {
        if (row == null || row.node() == null) {
            return "—";
        }
        VariantNode node = row.node();
        String chrom = row.chromosome();
        if (node.svEnd > node.position) {
            return chrom + ":" + node.position + "–" + node.svEnd;
        }
        return chrom + ":" + node.position;
    }

    public static String formatSvLength(TableRow row) {
        if (row == null || row.node() == null) {
            return "—";
        }
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

    public static String formatSvMate(TableRow row) {
        if (row == null || row.node() == null) {
            return "—";
        }
        VariantNode node = row.node();
        VcfVariantType type = node.type;
        if (type != VcfVariantType.SV_TRANSLOCATION && type != VcfVariantType.SV_BREAKEND) {
            return "—";
        }
        String mateChrom = node.mateChromosome();
        long matePos = node.matePosition();
        if (mateChrom == null || mateChrom.isBlank()) {
            return "—";
        }
        if (matePos >= 0) {
            return mateChrom + ":" + matePos;
        }
        return mateChrom;
    }

    public static String formatSvQual(TableRow row) {
        if (row == null || row.node() == null) {
            return "—";
        }
        double q = row.node().siteQuality;
        return q >= 0 ? String.format(Locale.ROOT, "%.0f", q) : "—";
    }

    private static String formatChrPos(TableRow row) {
        if (row == null || row.node() == null) {
            return "—";
        }
        return String.format(Locale.ROOT, "%,d", row.node().position);
    }

    private static String effectStyleClass(TableRow row) {
        return switch (rowEffect(row)) {
            case CODING_SYNONYMOUS -> "vn-fx-syn";
            case CODING_MISSENSE, CODING_INFRAME -> "vn-fx-mis";
            case CODING_STOP_GAIN, CODING_STOP_LOSS, CODING_FRAMESHIFT, SPLICE_SITE -> "vn-fx-trunc";
            case CODING_OTHER, UTR5, UTR3, INTRONIC, NONCODING_GENE -> "vn-fx-nc";
            default -> "vn-fx-default";
        };
    }

    // ── Shared data helpers ───────────────────────────────────────────────────

    private static int countUnionSamples(List<VariantEntry> variants, VariantFilter filter) {
        Set<Integer> tracks = new LinkedHashSet<>();
        for (VariantEntry entry : variants) {
            for (VariantNode.SampleCall call : entry.calls) {
                int idx = call.getTrackIndex();
                if (idx >= 0) {
                    tracks.add(idx);
                }
            }
        }
        return tracks.size();
    }

    private static String formatGroupAlleleFractions(List<VariantEntry> variants, VariantFilter filter) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int n = 0;
        for (VariantEntry entry : variants) {
            for (VariantNode.SampleCall call : entry.calls) {
                if (call.alleleFraction < 0) {
                    continue;
                }
                min = Math.min(min, call.alleleFraction);
                max = Math.max(max, call.alleleFraction);
                n++;
            }
        }
        if (n == 0) {
            return "—";
        }
        if (n == 1 || Math.abs(max - min) < 0.0005) {
            return String.format(Locale.ROOT, "%.2f", max);
        }
        return String.format(Locale.ROOT, "%.2f–%.2f", min, max);
    }

    static String formatAlleleFractions(VariantNode node, VariantFilter filter) {
        if (node == null) {
            return "";
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int n = 0;
        for (VariantNode.SampleCall call : node.getSamples()) {
            if (call == null || call.alleleFraction < 0) {
                continue;
            }
            if (filter != null && !filter.passesSampleDisplay(node, call)) {
                continue;
            }
            min = Math.min(min, call.alleleFraction);
            max = Math.max(max, call.alleleFraction);
            n++;
        }
        if (n == 0) {
            return "—";
        }
        if (n == 1 || Math.abs(max - min) < 0.0005) {
            return String.format(Locale.ROOT, "%.2f", max);
        }
        return String.format(Locale.ROOT, "%.2f–%.2f", min, max);
    }

    static String sampleDisplayName(VariantNode.SampleCall call) {
        if (call == null) {
            return "—";
        }
        SampleTrack track = call.getTrack();
        if (track != null && track.getName() != null && !track.getName().isBlank()) {
            return track.getName();
        }
        if (call.sample != null && call.sample.getName() != null && !call.sample.getName().isBlank()) {
            return call.sample.getName();
        }
        return "Sample";
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
        if (row == null) {
            return null;
        }
        // Prefer the explicit display gene (SV fan-out / gene-level rows).
        if (row.displayGenes() != null && !row.displayGenes().isEmpty()) {
            String gene = row.displayGenes().get(0);
            if (gene != null && !gene.isBlank()) {
                return gene;
            }
        }
        return rowGeneName(row.node());
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

    /** Gene label for export; falls back to chromosome when no gene is set. */
    public static String exportGeneName(TableRow row) {
        String gene = rowGeneName(row);
        if (gene != null && !gene.isBlank()) {
            return gene;
        }
        if (row != null && row.chromosome() != null && !row.chromosome().isBlank()) {
            return ChromosomeNames.forDisplay(row.chromosome());
        }
        return "";
    }

    public static String resolveTableColumnValue(TableRow row, String property, VariantFilter filter) {
        VariantNode node = row != null ? row.node() : null;
        if (node == null) {
            return "";
        }
        VariantAnnotation ann = rowAnnotation(node);
        return switch (property) {
            case "position" -> formatChrPos(row);
            case "refAlt" -> node.ref + " → " + (node.alt.isEmpty() ? "." : node.alt);
            case "variantType" -> typeLabel(node.type);
            case "effectDisplay" -> rowEffect(node).displayName();
            case "aaChange" -> ann != null && ann.aaChange() != null ? ann.aaChange() : "";
            case "codonChange" -> ann != null && ann.codonChange() != null ? ann.codonChange() : "";
            case "sampleCount" -> filter != null
                ? String.valueOf(filter.countDisplaySamples(node))
                : String.valueOf(node.getSampleCount());
            case "alleleFraction" -> formatAlleleFractions(node, filter);
            case "maxQuality" -> {
                double maxQuality = -1;
                if (filter == null) {
                    for (VariantNode.SampleCall call : node.getSamples()) {
                        if (call.quality > maxQuality) {
                            maxQuality = call.quality;
                        }
                    }
                } else {
                    for (VariantNode.SampleCall call : node.getSamples()) {
                        if (filter.passesSampleDisplay(node, call) && call.quality > maxQuality) {
                            maxQuality = call.quality;
                        }
                    }
                }
                yield maxQuality >= 0 ? String.format("%.0f", maxQuality) : "—";
            }
            default -> "";
        };
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

package org.baseplayer.variant.ui.components;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;

/**
 * Structural-variant results using the same nested Gene → Variant → Sample look
 * as {@link VariantTable}.
 */
public class SvVariantTable extends AbstractNestedVariantTable {

    public static final VcfVariantType[] TYPE_ORDER = {
        VcfVariantType.SV_DELETION,
        VcfVariantType.SV_DUPLICATION,
        VcfVariantType.SV_CNV_GAIN,
        VcfVariantType.SV_CNV_LOSS,
        VcfVariantType.SV_CNV_NEUTRAL,
        VcfVariantType.SV_INVERSION,
        VcfVariantType.SV_TRANSLOCATION,
        VcfVariantType.SV_BREAKEND,
        VcfVariantType.SV_INSERTION
    };

    private final Tab allTab;
    private final NestedPanel allPanel;
    private final Map<VcfVariantType, Tab> typeTabs = new EnumMap<>(VcfVariantType.class);
    private final Map<VcfVariantType, NestedPanel> typePanels = new EnumMap<>(VcfVariantType.class);

    private ObservableList<TableRow> allItems = FXCollections.observableArrayList();
    private final Map<VcfVariantType, ObservableList<TableRow>> typeItems = new EnumMap<>(VcfVariantType.class);
    private String searchQuery = "";

    private final BiConsumer<String, List<SampleTrack>> onGeneDoubleClick;
    private final Consumer<TableRow> onVariantDoubleClick;

    public SvVariantTable(
            TableView<?> allTable,
            Tab allTab,
            Map<VcfVariantType, TableView<?>> typeTableMap,
            Map<VcfVariantType, Tab> typeTabMap,
            BiConsumer<String, List<SampleTrack>> onGeneDoubleClick,
            Consumer<TableRow> onVariantDoubleClick) {
        super();
        this.allTab = allTab;
        this.onGeneDoubleClick = onGeneDoubleClick != null ? onGeneDoubleClick : (g, t) -> {};
        this.onVariantDoubleClick = onVariantDoubleClick != null ? onVariantDoubleClick : r -> {};

        this.allPanel = mountNestedPanel(
            allTable,
            VariantDetailLayout.STRUCTURAL,
            false,
            this.onGeneDoubleClick,
            this.onVariantDoubleClick,
            () -> this.displayFilter);

        for (VcfVariantType type : TYPE_ORDER) {
            TableView<?> tv = typeTableMap != null ? typeTableMap.get(type) : null;
            Tab tab = typeTabMap != null ? typeTabMap.get(type) : null;
            if (tab != null) {
                typeTabs.put(type, tab);
            }
            typeItems.put(type, FXCollections.observableArrayList());
            if (tv != null) {
                NestedPanel panel = mountNestedPanel(
                    tv,
                    VariantDetailLayout.STRUCTURAL,
                    false,
                    this.onGeneDoubleClick,
                    this.onVariantDoubleClick,
                    () -> this.displayFilter);
                if (panel != null) {
                    typePanels.put(type, panel);
                }
            }
        }
    }

    @Override
    public void initializeColumns() {
        // Nested panels are mounted in the constructor.
    }

    public void setItems(
            ObservableList<TableRow> all,
            Map<VcfVariantType, ObservableList<TableRow>> byType) {
        allItems = all != null ? all : FXCollections.observableArrayList();
        for (VcfVariantType type : TYPE_ORDER) {
            ObservableList<TableRow> items = byType != null ? byType.get(type) : null;
            typeItems.put(type, items != null ? items : FXCollections.observableArrayList());
        }
        applySearch();
    }

    /**
     * Bind rows and prebuilt gene groups (built off the FX thread).
     */
    public void setItemsWithPrebuiltGroups(
            ObservableList<TableRow> all,
            Map<VcfVariantType, ObservableList<TableRow>> byType,
            List<GeneGroup> allGroups,
            Map<VcfVariantType, List<GeneGroup>> byTypeGroups) {
        allItems = all != null ? all : FXCollections.observableArrayList();
        for (VcfVariantType type : TYPE_ORDER) {
            ObservableList<TableRow> items = byType != null ? byType.get(type) : null;
            typeItems.put(type, items != null ? items : FXCollections.observableArrayList());
        }
        if (allPanel != null) {
            allPanel.setGroups(allGroups != null ? allGroups : List.of());
        }
        setTitle(allTab, "All", countRowsInGroups(allGroups), true);
        TabPane pane = allTab != null ? allTab.getTabPane() : null;
        for (VcfVariantType type : TYPE_ORDER) {
            NestedPanel panel = typePanels.get(type);
            List<GeneGroup> groups = byTypeGroups != null ? byTypeGroups.get(type) : null;
            if (panel != null) {
                panel.setGroups(groups != null ? groups : List.of());
            }
            Tab tab = typeTabs.get(type);
            setTitle(tab, VariantTypeVisuals.shortLabel(type), countRowsInGroups(groups), true);
            if (tab != null && pane != null && !pane.getTabs().contains(tab)) {
                pane.getTabs().add(insertIndexForType(pane, type), tab);
            }
        }
    }

    public void setSearchQuery(String query) {
        searchQuery = query != null ? query.trim() : "";
        applySearch();
    }

    public String getSearchQuery() {
        return searchQuery;
    }

    /** Post-search rows currently shown in the All SV tab. */
    public ObservableList<TableRow> getDisplayedAllRows() {
        return filter(allItems);
    }

    public void setPlaceholders(String allText, String typeText) {
        if (allPanel != null) {
            allPanel.setPlaceholder(new Label(allText != null ? allText : ""));
        }
        Label typeLabel = new Label(typeText != null ? typeText : "");
        for (NestedPanel panel : typePanels.values()) {
            panel.setPlaceholder(new Label(typeLabel.getText()));
        }
    }

    public void setBaseTabTitles() {
        setTitle(allTab, "All", 0, false);
        TabPane pane = allTab != null ? allTab.getTabPane() : null;
        for (VcfVariantType type : TYPE_ORDER) {
            Tab tab = typeTabs.get(type);
            setTitle(tab, VariantTypeVisuals.shortLabel(type), 0, false);
            if (tab != null) {
                tab.setDisable(false);
                if (pane != null && !pane.getTabs().contains(tab)) {
                    pane.getTabs().add(insertIndexForType(pane, type), tab);
                }
            }
        }
    }

    private void applySearch() {
        boolean expand = !searchQuery.isEmpty();
        ObservableList<TableRow> all = filter(allItems);
        if (allPanel != null) {
            allPanel.setGroups(buildGeneGroups(all, false, displayFilter, expand));
        }
        setTitle(allTab, "All", countUniqueNodes(all), true);

        TabPane pane = allTab != null ? allTab.getTabPane() : null;
        for (VcfVariantType type : TYPE_ORDER) {
            ObservableList<TableRow> filtered = filter(typeItems.get(type));
            NestedPanel panel = typePanels.get(type);
            if (panel != null) {
                panel.setGroups(buildGeneGroups(filtered, false, displayFilter, expand));
            }
            Tab tab = typeTabs.get(type);
            setTitle(tab, VariantTypeVisuals.shortLabel(type), countUniqueNodes(filtered), true);
            if (tab != null && pane != null && !pane.getTabs().contains(tab)) {
                pane.getTabs().add(insertIndexForType(pane, type), tab);
            }
        }
    }

    private static int countUniqueNodes(ObservableList<TableRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        java.util.IdentityHashMap<VariantNode, Boolean> seen = new java.util.IdentityHashMap<>();
        for (TableRow row : rows) {
            if (row != null && row.node() != null) {
                seen.put(row.node(), Boolean.TRUE);
            }
        }
        return seen.size();
    }

    private int insertIndexForType(TabPane pane, VcfVariantType type) {
        int orderIndex = 0;
        for (int i = 0; i < TYPE_ORDER.length; i++) {
            if (TYPE_ORDER[i] == type) {
                orderIndex = i;
                break;
            }
        }
        int pos = 1;
        for (int i = 0; i < orderIndex; i++) {
            Tab earlier = typeTabs.get(TYPE_ORDER[i]);
            if (earlier != null && pane.getTabs().contains(earlier)) {
                pos++;
            }
        }
        return Math.min(pos, pane.getTabs().size());
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
        if (matchesSearch(row, q, displayFilter)) {
            return true;
        }
        if (row == null || row.node() == null) {
            return false;
        }
        if (formatSvPosition(row).toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (formatSvMate(row).toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (formatSvLength(row).toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        return VariantTypeVisuals.shortLabel(row.node().type).toLowerCase(Locale.ROOT).contains(q);
    }

    private static void setTitle(Tab tab, String base, int count, boolean withCount) {
        if (tab == null) {
            return;
        }
        tab.setText(withCount ? base + " (" + count + ")" : base);
    }
}

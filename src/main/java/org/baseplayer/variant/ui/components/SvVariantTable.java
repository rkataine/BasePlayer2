package org.baseplayer.variant.ui.components;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.baseplayer.io.VcfManager;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;

import javafx.application.Platform;
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
    private final AtomicInteger searchEpoch = new AtomicInteger();
    private List<TableRow> displayedAllCache = List.of();

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
        applySearchAsync(null);
    }

    /**
     * Bind rows and prebuilt gene groups (built off the FX thread).
     */
    public void setItemsWithPrebuiltGroups(
            ObservableList<TableRow> all,
            Map<VcfVariantType, ObservableList<TableRow>> byType,
            List<GeneGroup> allGroups,
            Map<VcfVariantType, List<GeneGroup>> byTypeGroups) {
        searchEpoch.incrementAndGet();
        allItems = all != null ? all : FXCollections.observableArrayList();
        for (VcfVariantType type : TYPE_ORDER) {
            ObservableList<TableRow> items = byType != null ? byType.get(type) : null;
            typeItems.put(type, items != null ? items : FXCollections.observableArrayList());
        }
        displayedAllCache = rowsMatchingSearch(
            allItems, normalizeSearchQuery(searchQuery), displayFilter);
        if (allPanel != null) {
            allPanel.setGroups(allGroups != null ? allGroups : List.of());
        }
        setTitle(allTab, "All", countRowsInGroups(allGroups), true);
        TabPane pane = allTab != null ? allTab.getTabPane() : null;
        Set<VcfVariantType> available = sessionAvailableTypes();
        for (VcfVariantType type : TYPE_ORDER) {
            NestedPanel panel = typePanels.get(type);
            List<GeneGroup> groups = byTypeGroups != null ? byTypeGroups.get(type) : null;
            if (panel != null) {
                panel.setGroups(groups != null ? groups : List.of());
            }
            int count = Math.max(
                countRowsInGroups(groups),
                countUniqueNodes(typeItems.get(type)));
            syncTypeTab(pane, type, count, available);
        }
    }

    /** Update stored search text without rebuilding (while debouncing). */
    public void rememberSearchQuery(String query) {
        searchQuery = query != null ? query.trim() : "";
    }

    public void setSearchQuery(String query) {
        setSearchQuery(query, null);
    }

    public void setSearchQuery(String query, Runnable onApplied) {
        rememberSearchQuery(query);
        applySearchAsync(onApplied);
    }

    public String getSearchQuery() {
        return searchQuery;
    }

    /** Post-search rows currently shown in the All SV tab. */
    public ObservableList<TableRow> getDisplayedAllRows() {
        return FXCollections.observableArrayList(displayedAllCache);
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
        Set<VcfVariantType> available = sessionAvailableTypes();
        for (VcfVariantType type : TYPE_ORDER) {
            // Empty / cleared table: only keep tabs for types actually seen in open VCFs.
            syncTypeTab(pane, type, 0, available);
        }
    }

    private void applySearchAsync(Runnable onApplied) {
        final int epoch = searchEpoch.incrementAndGet();
        final String query = searchQuery;
        final VariantFilter filter = displayFilter != null ? displayFilter.copy() : new VariantFilter();
        final List<TableRow> allSnap = List.copyOf(allItems);
        final Map<VcfVariantType, List<TableRow>> typeSnap = new EnumMap<>(VcfVariantType.class);
        for (VcfVariantType type : TYPE_ORDER) {
            ObservableList<TableRow> items = typeItems.get(type);
            typeSnap.put(type, items != null ? List.copyOf(items) : List.of());
        }
        final Set<VcfVariantType> available = sessionAvailableTypes();

        SEARCH_EXECUTOR.execute(() -> {
            if (epoch != searchEpoch.get()) {
                return;
            }
            String q = normalizeSearchQuery(query);
            List<TableRow> allMatched = rowsMatchingSearch(allSnap, q, filter);
            List<GeneGroup> allGroups = buildGeneGroups(allMatched, false, filter, false);
            Map<VcfVariantType, List<GeneGroup>> byTypeGroups = new HashMap<>();
            Map<VcfVariantType, Integer> typeCounts = new EnumMap<>(VcfVariantType.class);
            for (VcfVariantType type : TYPE_ORDER) {
                List<TableRow> filtered = rowsMatchingSearch(typeSnap.get(type), q, filter);
                byTypeGroups.put(type, buildGeneGroups(filtered, false, filter, false));
                typeCounts.put(type, countUniqueNodes(filtered));
            }
            if (epoch != searchEpoch.get()) {
                return;
            }
            Platform.runLater(() -> {
                if (epoch != searchEpoch.get()) {
                    return;
                }
                displayedAllCache = allMatched;
                if (allPanel != null) {
                    allPanel.setGroups(allGroups);
                }
                setTitle(allTab, "All", countUniqueNodes(allMatched), true);
                TabPane pane = allTab != null ? allTab.getTabPane() : null;
                for (VcfVariantType type : TYPE_ORDER) {
                    NestedPanel panel = typePanels.get(type);
                    List<GeneGroup> groups = byTypeGroups.get(type);
                    if (panel != null) {
                        panel.setGroups(groups != null ? groups : List.of());
                    }
                    syncTypeTab(pane, type, typeCounts.getOrDefault(type, 0), available);
                }
                if (onApplied != null) {
                    onApplied.run();
                }
            });
        });
    }

    /**
     * Show a per-type tab only when it has rows or the type was observed in an open VCF
     * (so CNV-only sessions do not keep DEL/DUP/INV/… tabs around).
     */
    private void syncTypeTab(
            TabPane pane,
            VcfVariantType type,
            int count,
            Set<VcfVariantType> available) {
        Tab tab = typeTabs.get(type);
        if (tab == null) {
            return;
        }
        boolean show = count > 0 || (available != null && available.contains(type));
        setTitle(tab, VariantTypeVisuals.shortLabel(type), count, true);
        tab.setDisable(false);
        if (pane == null) {
            return;
        }
        if (show) {
            if (!pane.getTabs().contains(tab)) {
                pane.getTabs().add(insertIndexForType(pane, type), tab);
            }
        } else {
            pane.getTabs().remove(tab);
        }
    }

    private static Set<VcfVariantType> sessionAvailableTypes() {
        try {
            Set<VcfVariantType> types = VcfManager.getInstance().getSessionAvailableTypes();
            return types != null ? types : EnumSet.noneOf(VcfVariantType.class);
        } catch (Exception ignored) {
            return EnumSet.noneOf(VcfVariantType.class);
        }
    }

    private static int countUniqueNodes(List<TableRow> rows) {
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

    private static int countUniqueNodes(ObservableList<TableRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        return countUniqueNodes(List.copyOf(rows));
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

    private static void setTitle(Tab tab, String base, int count, boolean withCount) {
        if (tab == null) {
            return;
        }
        tab.setText(withCount ? base + " (" + count + ")" : base);
    }
}

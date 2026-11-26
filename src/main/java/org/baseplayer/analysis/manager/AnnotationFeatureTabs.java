package org.baseplayer.analysis.manager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.baseplayer.features.BedTrack;
import org.baseplayer.features.BedVariantAnnotation;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable.GeneGroup;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable.NestedPanel;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable.TableRow;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;

/**
 * Point-results tabs for BED annotation tracks (Feature → Variant → Sample): tab sync,
 * fan-out of point rows by overlapping feature name, and per-tab search.
 */
final class AnnotationFeatureTabs {

    record Nodes(TabPane resultsTabPane, Tab intergenicTab, TextField tableSearchField) {}

    record AnnotationFeaturePrebuilt(BedTrack track, List<GeneGroup> groups) {}

    private final ManagerContext ctx;
    private final VariantTableChrome chrome;
    private final Nodes n;
    private final Consumer<VariantFilter> rebuildTables;

    private final Map<BedTrack, AnnotationFeatureTabSlot> annotationFeatureTabs =
        new IdentityHashMap<>();
    private ChangeListener<Number> annotationUiPulseListener;

    static final class AnnotationFeatureTabSlot {
        final BedTrack track;
        final Tab tab;
        final NestedPanel panel;
        String titleBase;
        /** Unfiltered groups from last table rebuild (search restores from this). */
        List<GeneGroup> baseGroups = List.of();
        List<GeneGroup> lastGroups = List.of();

        AnnotationFeatureTabSlot(BedTrack track, Tab tab, NestedPanel panel, String titleBase) {
            this.track = track;
            this.tab = tab;
            this.panel = panel;
            this.titleBase = titleBase;
        }
    }

    AnnotationFeatureTabs(
            ManagerContext ctx,
            VariantTableChrome chrome,
            Nodes nodes,
            Consumer<VariantFilter> rebuildTables) {
        this.ctx = ctx;
        this.chrome = chrome;
        this.n = nodes;
        this.rebuildTables = rebuildTables;
    }

    /** Create feature tabs and keep them in sync with BED annotation changes. */
    void install() {
        syncAnnotationFeatureTabs();
        annotationUiPulseListener = (obs, oldVal, newVal) -> {
            if (ctx.disposed) {
                return;
            }
            Platform.runLater(() -> {
                if (ctx.disposed) {
                    return;
                }
                syncAnnotationFeatureTabs();
                if (ctx.vcfManager != null) {
                    rebuildTables.accept(ctx.vcfManager.getCurrentFilter());
                }
            });
        };
        BedVariantAnnotation.uiPulseProperty().addListener(annotationUiPulseListener);
    }

    void dispose() {
        if (annotationUiPulseListener != null) {
            BedVariantAnnotation.uiPulseProperty().removeListener(annotationUiPulseListener);
            annotationUiPulseListener = null;
        }
    }

    /**
     * Ensure one Point-results tab + NestedPanel per active BED annotation track,
     * placed after Intergenic and before LOH. Titles match Excel header disambiguation.
     */
    private void syncAnnotationFeatureTabs() {
        if (n.resultsTabPane() == null) {
            return;
        }
        List<BedTrack> tracks = BedVariantAnnotation.annotationTracks();
        List<String> titles = BedVariantAnnotation.excelColumnHeaders();

        Set<BedTrack> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
        wanted.addAll(tracks);

        Iterator<Map.Entry<BedTrack, AnnotationFeatureTabSlot>> it =
            annotationFeatureTabs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BedTrack, AnnotationFeatureTabSlot> entry = it.next();
            if (!wanted.contains(entry.getKey())) {
                n.resultsTabPane().getTabs().remove(entry.getValue().tab);
                it.remove();
            }
        }

        int insertAt = indexAfterIntergenicTab();
        for (int i = 0; i < tracks.size(); i++) {
            BedTrack track = tracks.get(i);
            String title = i < titles.size() ? titles.get(i) : BedVariantAnnotation.excelHeaderBase(track);
            AnnotationFeatureTabSlot slot = annotationFeatureTabs.get(track);
            if (slot == null) {
                final BedTrack tabTrack = track;
                NestedPanel panel = AbstractNestedVariantTable.createFeatureAnnotationPanel(
                    (name, sampleTracks) -> chrome.handleFeatureRowDoubleClick(tabTrack, name, sampleTracks),
                    row -> chrome.selectVariant(row, false),
                    chrome::gotoVariant,
                    () -> ctx.vcfManager != null
                        ? ctx.vcfManager.getCurrentFilter()
                        : ctx.buildFilterFromUI());
                panel.setEmptyMessage("No overlapping variants");
                Tab tab = new Tab(title);
                tab.setClosable(false);
                tab.setContent(panel.getRoot());
                slot = new AnnotationFeatureTabSlot(track, tab, panel, title);
                annotationFeatureTabs.put(track, slot);
                n.resultsTabPane().getTabs().add(
                    Math.min(insertAt, n.resultsTabPane().getTabs().size()), tab);
                insertAt++;
            } else {
                slot.titleBase = title;
                int currentIdx = n.resultsTabPane().getTabs().indexOf(slot.tab);
                if (currentIdx < 0) {
                    n.resultsTabPane().getTabs().add(
                        Math.min(insertAt, n.resultsTabPane().getTabs().size()), slot.tab);
                } else if (currentIdx != insertAt) {
                    n.resultsTabPane().getTabs().remove(slot.tab);
                    n.resultsTabPane().getTabs().add(
                        Math.min(insertAt, n.resultsTabPane().getTabs().size()), slot.tab);
                }
                updateFeatureTabTitle(slot, slot.lastGroups != null ? slot.lastGroups.size() : 0);
                insertAt = n.resultsTabPane().getTabs().indexOf(slot.tab) + 1;
            }
        }
    }

    private int indexAfterIntergenicTab() {
        if (n.resultsTabPane() == null) {
            return 0;
        }
        if (n.intergenicTab() != null && n.resultsTabPane().getTabs().contains(n.intergenicTab())) {
            return n.resultsTabPane().getTabs().indexOf(n.intergenicTab()) + 1;
        }
        return 0;
    }

    /** Insert LOH after Intergenic and any annotation feature tabs. */
    int indexAfterAnnotationFeatureTabs() {
        int index = indexAfterIntergenicTab();
        for (AnnotationFeatureTabSlot slot : annotationFeatureTabs.values()) {
            if (slot == null || slot.tab == null || n.resultsTabPane() == null) {
                continue;
            }
            int i = n.resultsTabPane().getTabs().indexOf(slot.tab);
            if (i >= 0) {
                index = Math.max(index, i + 1);
            }
        }
        return index;
    }

    private static void updateFeatureTabTitle(AnnotationFeatureTabSlot slot, int groupCount) {
        if (slot == null || slot.tab == null) {
            return;
        }
        String base = slot.titleBase != null && !slot.titleBase.isBlank()
            ? slot.titleBase
            : BedVariantAnnotation.excelHeaderBase(slot.track);
        slot.tab.setText(base + " (" + Math.max(0, groupCount) + ")");
    }

    private static List<TableRow> fanOutPointRowsByFeatureName(
            List<TableRow> pointRows, BedTrack track) {
        if (pointRows == null || pointRows.isEmpty() || track == null) {
            return List.of();
        }
        List<TableRow> out = new ArrayList<>();
        for (TableRow row : pointRows) {
            if (row == null || row.node() == null) {
                continue;
            }
            List<String> names = BedVariantAnnotation.overlappingFeatureNames(
                track, row.chromosome(), row.node());
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                out.add(new TableRow(row.chromosome(), row.node(), List.of(name)));
            }
        }
        return out;
    }

    void applyAnnotationFeatureGroups(List<AnnotationFeaturePrebuilt> prebuilts) {
        syncAnnotationFeatureTabs();
        if (prebuilts == null) {
            for (AnnotationFeatureTabSlot slot : annotationFeatureTabs.values()) {
                slot.panel.setGroups(List.of());
                slot.baseGroups = List.of();
                slot.lastGroups = List.of();
                slot.panel.setEmptyMessage("No overlapping variants");
                updateFeatureTabTitle(slot, 0);
            }
            return;
        }
        IdentityHashMap<BedTrack, List<GeneGroup>> byTrack = new IdentityHashMap<>();
        for (AnnotationFeaturePrebuilt prebuilt : prebuilts) {
            if (prebuilt != null && prebuilt.track() != null) {
                byTrack.put(prebuilt.track(),
                    prebuilt.groups() != null ? prebuilt.groups() : List.of());
            }
        }
        for (AnnotationFeatureTabSlot slot : annotationFeatureTabs.values()) {
            List<GeneGroup> groups = byTrack.getOrDefault(slot.track, List.of());
            slot.panel.setGroups(groups);
            slot.baseGroups = groups;
            slot.lastGroups = groups;
            if (groups.isEmpty()) {
                slot.panel.setEmptyMessage("No overlapping variants");
            }
            updateFeatureTabTitle(slot, groups.size());
        }
        // Re-apply selected-tab search so a live query still filters the active feature tab.
        Tab selected = n.resultsTabPane() != null
            ? n.resultsTabPane().getSelectionModel().getSelectedItem()
            : null;
        AnnotationFeatureTabSlot active = annotationFeatureSlotForTab(selected);
        if (active != null && n.tableSearchField() != null) {
            applyAnnotationFeatureTabSearch(active, n.tableSearchField().getText());
        }
    }

    AnnotationFeatureTabSlot annotationFeatureSlotForTab(Tab tab) {
        if (tab == null) {
            return null;
        }
        for (AnnotationFeatureTabSlot slot : annotationFeatureTabs.values()) {
            if (slot != null && slot.tab == tab) {
                return slot;
            }
        }
        return null;
    }

    void restoreAnnotationFeatureBaseGroups(Tab selected) {
        for (AnnotationFeatureTabSlot slot : annotationFeatureTabs.values()) {
            if (slot == null || slot.tab == selected) {
                continue;
            }
            List<GeneGroup> base = slot.baseGroups != null ? slot.baseGroups : List.of();
            slot.panel.setGroups(base);
            slot.lastGroups = base;
            updateFeatureTabTitle(slot, base.size());
        }
    }

    void applyAnnotationFeatureTabSearch(AnnotationFeatureTabSlot slot, String query) {
        if (slot == null || slot.panel == null) {
            return;
        }
        VariantFilter filter = ctx.vcfManager != null
            ? ctx.vcfManager.getCurrentFilter()
            : ctx.buildFilterFromUI();
        if (!AbstractNestedVariantTable.isActiveSearchQuery(query)) {
            List<GeneGroup> base = slot.baseGroups != null ? slot.baseGroups : List.of();
            slot.panel.setGroups(base);
            slot.lastGroups = base;
            updateFeatureTabTitle(slot, base.size());
            return;
        }
        if (chrome.variantTable == null) {
            return;
        }
        List<TableRow> fanned = fanOutPointRowsByFeatureName(
            chrome.variantTable.snapshotAllPointRows(), slot.track);
        List<TableRow> matched = AbstractNestedVariantTable.rowsMatchingSearch(
            fanned, query, filter);
        List<GeneGroup> groups = AbstractNestedVariantTable.buildFeatureGroups(
            matched, filter, false);
        slot.panel.setGroups(groups);
        slot.lastGroups = groups;
        updateFeatureTabTitle(slot, groups.size());
    }

    GeneGroup findFeatureGroup(BedTrack track, String featureName) {
        return findFeatureGroup(annotationFeatureTabs.get(track), featureName);
    }

    private static GeneGroup findFeatureGroup(AnnotationFeatureTabSlot slot, String featureName) {
        if (slot == null || slot.lastGroups == null || featureName == null) {
            return null;
        }
        for (GeneGroup group : slot.lastGroups) {
            if (group != null && featureName.equals(group.getName())) {
                return group;
            }
        }
        return null;
    }

    /** One group list per BED annotation track; {@code null} when interrupted. */
    static List<AnnotationFeaturePrebuilt> prebuild(
            List<BedTrack> tracks,
            List<TableRow> coding,
            List<TableRow> intronic,
            List<TableRow> intergenic,
            VariantFilter filter,
            boolean expand,
            Consumer<String> progress) {
        List<AnnotationFeaturePrebuilt> out = new ArrayList<>(tracks.size());
        if (tracks.isEmpty()) {
            return out;
        }
        List<TableRow> pointUnion = new ArrayList<>(
            coding.size() + intronic.size() + intergenic.size());
        pointUnion.addAll(coding);
        pointUnion.addAll(intronic);
        pointUnion.addAll(intergenic);
        for (BedTrack track : tracks) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            if (progress != null) {
                progress.accept("Feature " + BedVariantAnnotation.excelHeaderBase(track));
            }
            List<TableRow> fanned = fanOutPointRowsByFeatureName(pointUnion, track);
            out.add(new AnnotationFeaturePrebuilt(
                track, AbstractNestedVariantTable.buildFeatureGroups(fanned, filter, expand)));
        }
        return out;
    }

    /** FX-thread fallback when no worker prebuild exists; honours the live point search text. */
    List<AnnotationFeaturePrebuilt> buildOnFx(
            List<TableRow> coding,
            List<TableRow> intronic,
            List<TableRow> intergenic,
            VariantFilter filter) {
        String pointSearch = n.tableSearchField() != null ? n.tableSearchField().getText() : null;
        return prebuild(
            BedVariantAnnotation.annotationTracks(),
            AbstractNestedVariantTable.rowsMatchingSearch(coding, pointSearch, filter),
            AbstractNestedVariantTable.rowsMatchingSearch(intronic, pointSearch, filter),
            AbstractNestedVariantTable.rowsMatchingSearch(intergenic, pointSearch, filter),
            filter,
            AbstractNestedVariantTable.isActiveSearchQuery(pointSearch),
            null);
    }
}

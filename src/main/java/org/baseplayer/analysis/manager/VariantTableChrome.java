package org.baseplayer.analysis.manager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.controllers.commands.NavigationCommands;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.features.BedTrack;
import org.baseplayer.features.BedVariantAnnotation;
import org.baseplayer.io.readers.BedFileReader.BedFeature;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable.GeneGroup;
import org.baseplayer.variant.ui.components.LohVariantTable;
import org.baseplayer.variant.ui.components.SvVariantTable;
import org.baseplayer.variant.ui.components.VariantDetailsPanel;
import org.baseplayer.variant.ui.components.VariantTable;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

/**
 * Point / SV / LOH variant table chrome: table construction, live search (selected results
 * tab only), row select → details, and genome navigation.
 */
final class VariantTableChrome {

    /** FXML table nodes; per-type arrays follow {@code SvVariantTable.TYPE_ORDER} / {@code LohVariantTable.TYPE_ORDER}. */
    record Nodes(
            TableView<VariantNode> codingTable,
            TableView<VariantNode> intronicTable,
            TableView<VariantNode> intergenicTable,
            Tab codingTab,
            Tab intronicTab,
            Tab intergenicTab,
            Tab lohRegionsTab,
            TabPane resultsTabPane,
            TextField tableSearchField,
            TableView<?> svAllTable,
            Tab svAllTab,
            TableView<?>[] svTypeTables,
            Tab[] svTypeTabs,
            TabPane svResultsTabPane,
            TextField svTableSearchField,
            TableView<?> lohAllTable,
            Tab lohAllTab,
            TableView<?>[] lohTypeTables,
            Tab[] lohTypeTabs) {}

    private final ManagerContext ctx;
    private final ModeHost modeHost;
    private final Nodes n;
    private AnnotationFeatureTabs features;

    VariantTable variantTable;
    SvVariantTable svVariantTable;
    LohVariantTable lohVariantTable;
    private final VariantDetailsPanel pointVariantDetails = new VariantDetailsPanel();
    private final VariantDetailsPanel svVariantDetails = new VariantDetailsPanel();

    /** Last results tab that received point-mode search (clear others only on change). */
    private Tab lastPointSearchTab;
    /** Last SV results tab that received search. */
    private Tab lastSvSearchTab;
    private final DebouncedAction pointSearch;
    private final DebouncedAction svSearch;

    VariantTableChrome(ManagerContext ctx, Nodes nodes) {
        this.ctx = ctx;
        this.modeHost = ctx.modeHost;
        this.n = nodes;
        this.pointSearch = new DebouncedAction(
            javafx.util.Duration.millis(200), this::applyPointTableSearchNow);
        this.svSearch = new DebouncedAction(
            javafx.util.Duration.millis(200), this::applySvTableSearchNow);
    }

    /** Annotation feature tabs; search + navigation consult them. */
    void bindFeatureTabs(AnnotationFeatureTabs features) {
        this.features = features;
    }

    VariantTable variantTable() {
        return variantTable;
    }

    SvVariantTable svVariantTable() {
        return svVariantTable;
    }

    void install() {
        variantTable = new VariantTable(
            n.codingTable(), n.intronicTable(), n.intergenicTable(),
            n.codingTab(), n.intronicTab(), n.intergenicTab(),
            this::handleGeneRowDoubleClick,
            row -> selectVariant(row, false),
            this::gotoVariant);
        variantTable.initializeColumns();

        if (n.svAllTable() != null) {
            svVariantTable = new SvVariantTable(
                n.svAllTable(), n.svAllTab(),
                typeMap(SvVariantTable.TYPE_ORDER, n.svTypeTables()),
                typeMap(SvVariantTable.TYPE_ORDER, n.svTypeTabs()),
                this::handleGeneRowDoubleClick,
                row -> selectVariant(row, true),
                this::gotoSvVariant);
            svVariantTable.initializeColumns();
            // FXML lists every SV type tab; prune to types observed in open VCFs.
            svVariantTable.setBaseTabTitles();
        }
        if (n.lohAllTable() != null) {
            // LOH reuses the point-mutation search box.
            lohVariantTable = new LohVariantTable(
                n.lohAllTable(), n.lohAllTab(),
                typeMap(LohVariantTable.TYPE_ORDER, n.lohTypeTables()),
                typeMap(LohVariantTable.TYPE_ORDER, n.lohTypeTabs()),
                this::handleGeneRowDoubleClick,
                row -> selectVariant(row, false),
                this::gotoLohVariant);
            lohVariantTable.initializeColumns();
        }

        if (n.tableSearchField() != null) {
            n.tableSearchField().textProperty().addListener((obs, oldVal, newVal) -> {
                rememberPointSearchQuery(newVal);
                pointSearch.schedule();
            });
        }
        if (n.resultsTabPane() != null) {
            n.resultsTabPane().getSelectionModel().selectedItemProperty().addListener(
                (obs, oldTab, newTab) -> applyPointTableSearchNow());
        }
        if (n.svTableSearchField() != null) {
            n.svTableSearchField().textProperty().addListener((obs, oldVal, newVal) -> {
                rememberSvSearchQuery(newVal);
                svSearch.schedule();
            });
        }
        TabPane svResults = svResultsPane();
        if (svResults != null) {
            svResults.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldTab, newTab) -> applySvTableSearchNow());
        }
    }

    private static <T> Map<VcfVariantType, T> typeMap(VcfVariantType[] order, T[] values) {
        Map<VcfVariantType, T> map = new EnumMap<>(VcfVariantType.class);
        for (int i = 0; i < order.length; i++) {
            map.put(order[i], values[i]);
        }
        return map;
    }

    private TabPane svResultsPane() {
        return n.svResultsTabPane() != null ? n.svResultsTabPane() : n.resultsTabPane();
    }

    void stopSearchTimers() {
        pointSearch.stop();
        svSearch.stop();
    }

    /** Forget which tab last got search so the next apply clears the others. */
    void reapplySearchAfterRebuild() {
        lastPointSearchTab = null;
        lastSvSearchTab = null;
        applyPointTableSearchNow();
        applySvTableSearchNow();
    }

    /** New Project / Clear All: drop search text and result filters. */
    void clearSearch() {
        if (n.tableSearchField() != null) {
            n.tableSearchField().clear();
        }
        if (n.svTableSearchField() != null) {
            n.svTableSearchField().clear();
        }
        lastPointSearchTab = null;
        lastSvSearchTab = null;
        if (variantTable != null) {
            variantTable.setTableSearchQuery(
                "", AbstractNestedVariantTable.SearchBucket.NONE, null);
        }
        if (lohVariantTable != null) {
            lohVariantTable.setSearchQuery("");
        }
        if (svVariantTable != null) {
            svVariantTable.setSearchQuery(
                "", SvVariantTable.SearchScope.NONE, null, null);
        }
        if (modeHost.pointEnrichmentPanel() != null) {
            modeHost.pointEnrichmentPanel().getResultTable().setSearchQuery("");
        }
        if (modeHost.svEnrichmentPanel() != null) {
            modeHost.svEnrichmentPanel().getResultTable().setSearchQuery("");
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /** Remember query only on the selected point-results target (avoids cross-tab filtering). */
    private void rememberPointSearchQuery(String query) {
        Tab selected = n.resultsTabPane() != null
            ? n.resultsTabPane().getSelectionModel().getSelectedItem()
            : null;
        if (selected == modeHost.pointGroupResultsTab()) {
            if (modeHost.pointEnrichmentPanel() != null) {
                modeHost.pointEnrichmentPanel().getResultTable().rememberSearchQuery(query);
            }
            return;
        }
        if (selected == n.lohRegionsTab()) {
            if (lohVariantTable != null) {
                lohVariantTable.rememberSearchQuery(query);
            }
            return;
        }
        if (selected == n.codingTab() || selected == n.intronicTab() || selected == n.intergenicTab()) {
            if (variantTable != null) {
                variantTable.rememberSearchQuery(query);
            }
        }
    }

    private void rememberSvSearchQuery(String query) {
        TabPane pane = n.svResultsTabPane() != null ? n.svResultsTabPane() : n.resultsTabPane();
        Tab selected = pane != null ? pane.getSelectionModel().getSelectedItem() : null;
        if (selected == modeHost.svGroupResultsTab()) {
            if (modeHost.svEnrichmentPanel() != null) {
                modeHost.svEnrichmentPanel().getResultTable().rememberSearchQuery(query);
            }
            return;
        }
        if (svVariantTable != null) {
            svVariantTable.rememberSearchQuery(query);
        }
    }

    /**
     * Apply debounced point/LOH/feature/group-comparison search to the
     * currently selected results tab only.
     */
    private void applyPointTableSearchNow() {
        String q = n.tableSearchField() != null ? n.tableSearchField().getText() : "";
        Tab selected = n.resultsTabPane() != null
            ? n.resultsTabPane().getSelectionModel().getSelectedItem()
            : null;

        if (selected != lastPointSearchTab) {
            clearPointSearchExcept(selected);
            lastPointSearchTab = selected;
        }

        if (selected == null) {
            return;
        }
        if (selected == n.codingTab()) {
            if (variantTable != null) {
                variantTable.setTableSearchQuery(
                    q, AbstractNestedVariantTable.SearchBucket.CODING, null);
            }
            return;
        }
        if (selected == n.intronicTab()) {
            if (variantTable != null) {
                variantTable.setTableSearchQuery(
                    q, AbstractNestedVariantTable.SearchBucket.INTRONIC, null);
            }
            return;
        }
        if (selected == n.intergenicTab()) {
            if (variantTable != null) {
                variantTable.setTableSearchQuery(
                    q, AbstractNestedVariantTable.SearchBucket.INTERGENIC, null);
            }
            return;
        }
        if (selected == n.lohRegionsTab()) {
            if (lohVariantTable != null) {
                lohVariantTable.setSearchQuery(q);
            }
            return;
        }
        if (selected == modeHost.pointGroupResultsTab()) {
            if (modeHost.pointEnrichmentPanel() != null) {
                modeHost.pointEnrichmentPanel().getResultTable().setSearchQuery(q);
            }
            return;
        }
        AnnotationFeatureTabs.AnnotationFeatureTabSlot featureSlot = features.annotationFeatureSlotForTab(selected);
        if (featureSlot != null) {
            features.applyAnnotationFeatureTabSearch(featureSlot, q);
        }
    }

    private void clearPointSearchExcept(Tab selected) {
        if (variantTable != null
            && selected != n.codingTab()
            && selected != n.intronicTab()
            && selected != n.intergenicTab()) {
            variantTable.setTableSearchQuery(
                "", AbstractNestedVariantTable.SearchBucket.NONE, null);
        }
        if (lohVariantTable != null && selected != n.lohRegionsTab()) {
            lohVariantTable.setSearchQuery("");
        }
        if (modeHost.pointEnrichmentPanel() != null && selected != modeHost.pointGroupResultsTab()) {
            modeHost.pointEnrichmentPanel().getResultTable().setSearchQuery("");
        }
        features.restoreAnnotationFeatureBaseGroups(selected);
    }

    private void applySvTableSearchNow() {
        String q = n.svTableSearchField() != null ? n.svTableSearchField().getText() : "";
        TabPane pane = n.svResultsTabPane() != null ? n.svResultsTabPane() : n.resultsTabPane();
        Tab selected = pane != null ? pane.getSelectionModel().getSelectedItem() : null;

        if (selected != lastSvSearchTab) {
            if (selected != modeHost.svGroupResultsTab() && modeHost.svEnrichmentPanel() != null) {
                modeHost.svEnrichmentPanel().getResultTable().setSearchQuery("");
            }
            if (selected == modeHost.svGroupResultsTab() && svVariantTable != null) {
                svVariantTable.setSearchQuery(
                    "", SvVariantTable.SearchScope.NONE, null, null);
            }
            lastSvSearchTab = selected;
        }

        if (selected == modeHost.svGroupResultsTab()) {
            if (modeHost.svEnrichmentPanel() != null) {
                modeHost.svEnrichmentPanel().getResultTable().setSearchQuery(q);
            }
            return;
        }
        if (svVariantTable == null) {
            return;
        }
        if (selected == n.svAllTab()) {
            svVariantTable.setSearchQuery(
                q, SvVariantTable.SearchScope.ALL_TAB, null, null);
            return;
        }
        VcfVariantType type = svTypeForTab(selected);
        if (type != null) {
            svVariantTable.setSearchQuery(
                q, SvVariantTable.SearchScope.TYPE, type, null);
            return;
        }
        svVariantTable.setSearchQuery(
            "", SvVariantTable.SearchScope.NONE, null, null);
    }

    private VcfVariantType svTypeForTab(Tab tab) {
        if (tab == null) {
            return null;
        }
        Tab[] tabs = n.svTypeTabs();
        for (int i = 0; i < tabs.length; i++) {
            if (tab == tabs[i]) {
                return SvVariantTable.TYPE_ORDER[i];
            }
        }
        return null;
    }

    // ── Select / goto ─────────────────────────────────────────────────────────

    private String rowChromosome(VariantTable.TableRow row) {
        String rowChromosome = row.chromosome();
        return rowChromosome == null || rowChromosome.isBlank() ? ctx.chromosome : rowChromosome;
    }

    /** Single-click: open the Variant details tab (no genome navigation). */
    void selectVariant(VariantTable.TableRow row, boolean structural) {
        if (row == null || row.node() == null) {
            return;
        }
        openVariantDetails(row.node(), rowChromosome(row), structural);
    }

    /** Double-click / Go to variant: navigate the genome browser to this allele. */
    void gotoVariant(VariantTable.TableRow row) {
        if (row == null || row.node() == null) {
            return;
        }
        VariantNode node = row.node();
        gotoPositionWindow(rowChromosome(row), node.position, resolveTracksFromCalls(node.getSamples()));
    }

    /** SV: full span for DEL/DUP/INV/CNV; dual-stack for TRA/BND. */
    void gotoSvVariant(VariantTable.TableRow row) {
        if (row == null || row.node() == null) {
            return;
        }
        VariantNode node = row.node();
        String chrom = rowChromosome(row);
        List<SampleTrack> tracks = resolveTracksFromCalls(node.getSamples());

        if (node.type == VcfVariantType.SV_TRANSLOCATION || node.type == VcfVariantType.SV_BREAKEND) {
            navigateSvTranslocation(chrom, node, tracks);
        } else if ((node.type == VcfVariantType.SV_DELETION
                || node.type == VcfVariantType.SV_DUPLICATION
                || node.type == VcfVariantType.SV_INVERSION
                || VariantTypeVisuals.isCnv(node.type))
                && node.svEnd > node.position) {
            gotoPaddedSpan(chrom, node.position, node.svEnd,
                "SV:" + chrom + ":" + node.position + "-" + node.svEnd, tracks);
        } else {
            gotoPositionWindow(chrom, node.position, tracks);
        }
    }

    /** LOH: zoom to the AA/BB span like a deletion. */
    void gotoLohVariant(VariantTable.TableRow row) {
        if (row == null || row.node() == null) {
            return;
        }
        VariantNode node = row.node();
        if (VariantTypeVisuals.isLohRegion(node.type) && node.svEnd >= node.position) {
            String chrom = rowChromosome(row);
            long end = node.svEnd > node.position ? node.svEnd : node.position + 1;
            gotoPaddedSpan(chrom, node.position, end,
                "LOH:" + chrom + ":" + node.position + "-" + node.svEnd,
                resolveTracksFromCalls(node.getSamples()));
            return;
        }
        gotoVariant(row);
    }

    private void gotoPositionWindow(String chrom, long position, List<SampleTrack> tracks) {
        long minViewLength = 40;
        final long navStart = Math.max(1, position - minViewLength / 2);
        final long navEnd = position + minViewLength / 2;
        navigateAndApplySampleFilter(
            () -> NavigationCommands.navigateToPosition(chrom, (int) navStart, (int) navEnd),
            tracks,
            "Position:" + position);
    }

    /** SV / LOH span navigation with padding, then load variants for the region. */
    private void gotoPaddedSpan(
            String chrom, long start, long end, String label, List<SampleTrack> tracks) {
        long[] view = paddedSvSpan(start, end);
        final long navStart = view[0];
        final long navEnd = view[1];
        navigateAndApplySampleFilter(
            () -> {
                NavigationCommands.navigateToPosition(chrom, (int) navStart, (int) navEnd);
                tryLoadRegionVariants(chrom, navStart, navEnd);
            },
            tracks,
            label);
    }

    /**
     * Open or refresh the Variant details tool tab (beside Filters / Control Files).
     *
     * @param structural when true, use the SV workspace tool tab pane
     */
    void openVariantDetails(VariantNode node, String chrom, boolean structural) {
        if (node == null) {
            return;
        }
        String chromName = chrom == null || chrom.isBlank() ? ctx.chromosome : chrom;
        VariantTable.TableRow gotoRow = fakeRow(node, chromName);
        Runnable gotoAction = () -> {
            if (structural) {
                gotoSvVariant(gotoRow);
            } else if (VariantTypeVisuals.isLohRegion(node.type)) {
                gotoLohVariant(gotoRow);
            } else {
                gotoVariant(gotoRow);
            }
        };
        if (structural) {
            TabPane tools = modeHost.sv() != null ? modeHost.sv().toolTabPane() : modeHost.svToolTabPane();
            svVariantDetails.show(tools, node, chromName, gotoAction);
        } else {
            TabPane tools = modeHost.point() != null
                ? modeHost.point().toolTabPane()
                : modeHost.pointToolTabPane();
            pointVariantDetails.show(tools, node, chromName, gotoAction);
        }
    }

    private static VariantTable.TableRow fakeRow(VariantNode node, String chrom) {
        return new VariantTable.TableRow(chrom, node);
    }

    private void handleGeneRowDoubleClick(String geneName, List<SampleTrack> tracks) {
        if (geneName == null || geneName.isBlank()) {
            return;
        }
        navigateAndApplySampleFilter(
            () -> NavigationCommands.navigateToGene(geneName, true),
            tracks,
            geneName);
    }

    void handleFeatureRowDoubleClick(
            BedTrack track, String featureName, List<SampleTrack> tracks) {
        if (featureName == null || featureName.isBlank()) {
            return;
        }
        String navChromosome = ctx.chromosome;
        long navStart = -1;
        long navEnd = -1;

        GeneGroup group = features.findFeatureGroup(track, featureName);
        if (group != null) {
            String groupChrom = group.firstVariantChromosome();
            if (groupChrom != null && !groupChrom.isBlank()) {
                navChromosome = groupChrom;
            }
        }

        BedFeature feature = BedVariantAnnotation.findFeatureByName(track, navChromosome, featureName);
        if (feature != null) {
            // BED start is 0-based; end is exclusive (same convention as overlap helpers).
            navStart = feature.start() + 1;
            navEnd = Math.max(navStart, feature.end());
            if (feature.chrom() != null && !feature.chrom().isBlank()) {
                navChromosome = feature.chrom();
            }
        } else if (group != null) {
            long[] span = group.variantSpanInclusive();
            if (span != null) {
                navStart = span[0];
                navEnd = Math.max(span[0], span[1]);
            }
        }

        if (navChromosome == null || navChromosome.isBlank() || navStart < 1) {
            return;
        }
        if (navEnd < navStart) {
            navEnd = navStart;
        }
        final String chrom = navChromosome;
        final long start = navStart;
        final long end = navEnd;
        navigateAndApplySampleFilter(
            () -> NavigationCommands.navigateToPosition(chrom, (int) start, (int) end),
            tracks,
            featureName);
    }

    private void navigateSvTranslocation(String primaryChrom, VariantNode node, List<SampleTrack> tracks) {
        long primaryPos = node.position;
        long[] primaryView = paddedBreakpointWindow(primaryPos);
        String mateChrom = node.mateChromosome();
        long matePos = node.matePosition();

        navigateAndApplySampleFilter(
            () -> {
                DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
                List<org.baseplayer.draw.DrawStack> stacks =
                    stackManager != null ? stackManager.getStacks() : List.of();

                if (!stacks.isEmpty()) {
                    stacks.get(0).navigateTo(primaryChrom, primaryView[0], primaryView[1]);
                } else {
                    NavigationCommands.navigateToPosition(
                        primaryChrom, (int) primaryView[0], (int) primaryView[1]);
                }
                tryLoadRegionVariants(primaryChrom, primaryView[0], primaryView[1]);

                if (mateChrom != null && !mateChrom.isBlank() && matePos >= 0) {
                    long[] mateView = paddedBreakpointWindow(matePos);
                    if (stacks.size() >= 2) {
                        stacks.get(1).navigateTo(mateChrom, mateView[0], mateView[1]);
                        tryLoadRegionVariants(mateChrom, mateView[0], mateView[1]);
                    } else {
                        org.baseplayer.controllers.MainController.addStackAtRegion(
                            mateChrom, mateView[0], mateView[1]);
                    }
                }
            },
            tracks,
            "TRA:" + primaryChrom + ":" + primaryPos);
    }

    private static long[] paddedSvSpan(long start, long end) {
        long span = Math.max(1, end - start);
        long pad = Math.max(500, Math.min(50_000, span / 10));
        long viewStart = Math.max(1, start - pad);
        long viewEnd = end + pad;
        return new long[] { viewStart, viewEnd };
    }

    private static long[] paddedBreakpointWindow(long position) {
        long half = 500;
        long start = Math.max(1, position - half);
        return new long[] { start, position + half };
    }

    private void tryLoadRegionVariants(String chrom, long start, long end) {
        if (ctx.vcfManager == null || chrom == null || chrom.isBlank()) {
            return;
        }
        try {
            ctx.vcfManager.loadRegionVariants(chrom, start, end);
        } catch (Exception ignored) {
            // Navigation still succeeds without a forced VCF load
        }
    }

    /**
     * Resolve unique sample tracks from per-variant sample calls.
     */
    private List<SampleTrack> resolveTracksFromCalls(java.util.List<VariantNode.SampleCall> samples) {
        if (samples == null || samples.isEmpty()) {
            return Collections.emptyList();
        }

        Set<SampleTrack> uniqueTracks = new LinkedHashSet<>();

        for (VariantNode.SampleCall call : samples) {
            if (call.getTrack() != null) {
                uniqueTracks.add(call.getTrack());
            }
        }
        return new ArrayList<>(uniqueTracks);
    }

    /**
     * Navigate to a location and apply sample filtering.
     * Accepts sample objects directly; SampleRegistry handles track index extraction.
     *
     * @param navigationAction The navigation action to perform
     * @param tracksWithFeature List of sample tracks containing the feature (may be null for gene filtering)
     * @param featureName Human-readable name of the feature for the sample subset
     */
    private void navigateAndApplySampleFilter(
            Runnable navigationAction,
             List<SampleTrack> tracksWithFeature,
            String featureName) {

        navigationAction.run();

        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        if (tracksWithFeature != null && !tracksWithFeature.isEmpty()) {
            registry.applyGeneSubset(tracksWithFeature, featureName);

            int displayedCount = registry.getDisplayedTrackCount();
            if (displayedCount > 0) {
                double viewportHeight = estimateSampleViewportHeight(registry);
                registry.setVisibleSamples(0, displayedCount - 1, viewportHeight);
            }
        }
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
        MinimizedAnalysisManagerWindow.handleMinimize(ctx.stage);
    }

    private double estimateSampleViewportHeight(SampleRegistry registry) {
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        if (!stackManager.isEmpty() && stackManager.getFirst().sampleTrackCanvas != null) {
            double fromCanvas = stackManager.getFirst().sampleTrackCanvas.getHeight();
            if (fromCanvas > 0) {
                return fromCanvas;
            }
        }
        double derived = registry.getSampleHeight() * Math.max(1, registry.getVisibleSampleCount());
        return Math.max(0, derived);
    }
}

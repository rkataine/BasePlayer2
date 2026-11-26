package org.baseplayer.analysis.manager;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.baseplayer.io.VcfManager;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.annotation.VariantEffect;

import javafx.application.Platform;
import javafx.stage.Window;

/**
 * Public entry points for the Analysis Manager shell.
 */
public final class AnalysisManagerFacade {
    private static volatile AnalysisBusyGate busyGate;

    private AnalysisManagerFacade() {}

    /** Bind the live busy gate from the shell (cleared on dispose). */
    public static void bindBusyGate(AnalysisBusyGate gate) {
        busyGate = gate;
    }

    public static AnalysisBusyGate getBusyGate() {
        return busyGate;
    }

    public static void open(Window owner, VcfManager vcfManager, Runnable onClose) {
        AnalysisManagerWindow.open(owner, vcfManager, onClose);
    }

    public static void openForSignalAnalysis() {
        AnalysisManagerWindow.openForSignalAnalysis();
    }

    public static void openForSignalDataset(String datasetId) {
        AnalysisManagerWindow.openForSignalDataset(datasetId);
    }

    public static void bringToFrontOrOpen() {
        AnalysisManagerWindow.bringToFrontOrOpen();
    }

    public static AnalysisManagerController getController() {
        return AnalysisManagerWindow.getCurrentController();
    }

    /**
     * Post-load table build/apply port for {@link VcfManager}.
     * {@code null} when Analysis is not open.
     */
    public static PostLoadTablePort getPostLoadTables() {
        return AnalysisManagerWindow.getCurrentController();
    }

    public static void setBusyStatus(String status) {
        AnalysisBusyGate gate = busyGate;
        if (gate != null) {
            gate.setStatus(status);
        }
    }

    public static void loadFilterState(VariantFilter filter) {
        AnalysisManagerController controller = getController();
        if (controller != null && filter != null) {
            controller.loadFilterState(filter);
        }
    }

    public static VariantFilter snapshotUiFilter() {
        AnalysisManagerController controller = getController();
        return controller != null ? controller.snapshotUiFilter() : null;
    }

    /**
     * Called when sample/VCF data is removed so type checkboxes, mode tabs, and
     * tables drop types that are no longer present in any cached list.
     */
    public static void notifySampleDataChanged() {
        AnalysisManagerController controller = getController();
        if (controller != null) {
            Platform.runLater(controller::onSampleDataChanged);
        }
    }

    /**
     * Called when a VCF file visibility checkbox changes so the common-variant
     * slider max and filtered table match currently visible VCF tracks.
     */
    public static void notifySampleVisibilityChanged() {
        AnalysisManagerController controller = getController();
        if (controller != null) {
            Platform.runLater(controller::onSampleVisibilityChanged);
        }
    }

    /** Sync checkboxes when allowed types are toggled outside the Variant Manager UI. */
    public static void syncFilterUiFromExternal(VariantFilter filter) {
        AnalysisManagerController controller = getController();
        if (controller != null && filter != null) {
            Platform.runLater(() -> controller.loadFilterState(filter));
        }
    }

    /**
     * Add the given types to the load-time filter (Variant Manager checkboxes),
     * keep them canvas-visible, and reload cached chromosomes so they materialize.
     * Used when aggregate legends request a type that was not included in the last load.
     */
    public static void enableTypesAndReload(Set<VcfVariantType> types) {
        if (types == null || types.isEmpty()) {
            return;
        }
        Runnable work = () -> {
            VcfManager vcfManager = VcfManager.getInstance();
            if (!vcfManager.hasLoadedVcf()) {
                return;
            }

            AnalysisManagerController controller = getController();
            VariantFilter target;
            if (controller != null) {
                target = controller.snapshotUiFilter();
            } else {
                VariantFilter current = vcfManager.getCurrentFilter();
                target = current != null ? current.copy() : new VariantFilter();
            }

            // Ensure class slices exist, then add types to the matching slice.
            VariantFilter point = target.getPointSlice() != null
                ? target.getPointSlice().copy()
                : new VariantFilter();
            VariantFilter sv = target.getSvSlice() != null
                ? target.getSvSlice().copy()
                : new VariantFilter();
            if (target.getPointSlice() == null && target.getSvSlice() == null) {
                // Legacy single filter: seed slices from current allowed types only
                // (never invent a full Point/SV inventory).
                EnumSet<VcfVariantType> currentTypes = target.getAllowedTypes() == null
                    ? EnumSet.noneOf(VcfVariantType.class)
                    : EnumSet.copyOf(target.getAllowedTypes());
                EnumSet<VcfVariantType> pointTypes = EnumSet.copyOf(VariantTypeVisuals.VariantClass.POINT.allTypes());
                pointTypes.retainAll(currentTypes);
                EnumSet<VcfVariantType> svTypes = EnumSet.copyOf(VariantTypeVisuals.VariantClass.STRUCTURAL.allTypes());
                svTypes.retainAll(currentTypes);
                point.setAllowedTypes(pointTypes);
                point.setMinQuality(target.getMinQuality());
                point.setMinDepth(target.getMinDepth());
                point.setMinAlleleFraction(target.getMinAlleleFraction());
                point.setMaxAlleleFraction(target.getMaxAlleleFraction());
                point.setAllowedEffects(target.getAllowedEffects());
                point.setCancerGenesOnly(target.isCancerGenesOnly());
                sv.setAllowedTypes(svTypes);
                sv.setMinQuality(target.getMinQuality());
                sv.setAllowedEffects(EnumSet.allOf(VariantEffect.class));
            }

            EnumSet<VcfVariantType> pointAllowed = point.getAllowedTypes() == null || point.getAllowedTypes().isEmpty()
                ? EnumSet.noneOf(VcfVariantType.class)
                : EnumSet.copyOf(point.getAllowedTypes());
            EnumSet<VcfVariantType> svAllowed = sv.getAllowedTypes() == null || sv.getAllowedTypes().isEmpty()
                ? EnumSet.noneOf(VcfVariantType.class)
                : EnumSet.copyOf(sv.getAllowedTypes());
            EnumSet<VcfVariantType> lohTypes = EnumSet.noneOf(VcfVariantType.class);
            for (VcfVariantType type : types) {
                if (VariantTypeVisuals.isLohRegion(type)) {
                    // Synthetic — not part of Point/SV load filters.
                    lohTypes.add(type);
                } else if (VariantTypeVisuals.isStructural(type)) {
                    svAllowed.add(type);
                } else {
                    pointAllowed.add(type);
                }
            }
            point.setAllowedTypes(pointAllowed);
            sv.setAllowedTypes(svAllowed);
            target.setClassSlices(point, sv);

            vcfManager.unionSessionAvailableFilters(types, null);
            vcfManager.ensureCanvasTypesVisible(types);
            // LOH arcs need no VCF reload; just keep them canvas-visible.
            if (!lohTypes.isEmpty() && lohTypes.containsAll(types)) {
                vcfManager.refreshCanvasesForTypeVisibility();
                return;
            }
            vcfManager.setCurrentFilterForNextLoad(target);

            if (controller != null) {
                controller.applyEnabledTypes(target, point, sv);
            } else {
                reloadCachedChromosomesHeadless(vcfManager, target);
            }
        };

        if (Platform.isFxApplicationThread()) {
            work.run();
        } else {
            Platform.runLater(work);
        }
    }

    /** Reload cached chromosomes when Variant Manager is not open. */
    private static void reloadCachedChromosomesHeadless(VcfManager vcfManager, VariantFilter target) {
        List<String> chromosomes = vcfManager.getCachedChromosomesInOrder(null);
        if (chromosomes.isEmpty()) {
            String chrom = vcfManager.getLastLoadedChromosome();
            if (chrom != null && !chrom.isBlank()) {
                chromosomes = List.of(chrom);
            }
        }
        if (chromosomes.isEmpty()) {
            return;
        }

        vcfManager.markAllCachedVariantListsDirty();
        final VariantFilter filterSnapshot = target.copy();
        final List<String> cachedChroms = List.copyOf(chromosomes);
        vcfManager.annotateAllReferenceChromosomes(
            filterSnapshot,
            cachedChroms,
            null,
            result -> {
                if (result != null && !result.cancelled()) {
                    vcfManager.setCurrentFilterForNextLoad(filterSnapshot);
                    vcfManager.applyFilter(filterSnapshot);
                    org.baseplayer.project.ProjectSessionState.get().markDirty();
                }
            });
    }

    public static void closeAndDispose() {
        AnalysisManagerWindow.closeAndDispose();
    }

    public static boolean shouldShowToolbarButton() {
        return AnalysisManagerWindow.shouldShowToolbarButton();
    }

    public static boolean hasSignalDatasets() {
        return AnalysisManagerWindow.hasSignalDatasets();
    }

    public static boolean hasComparableSignalData() {
        return AnalysisManagerWindow.hasComparableSignalData();
    }

    public static boolean isOpen() {
        return AnalysisManagerWindow.isOpen();
    }

    public static void refreshTheme() {
        AnalysisManagerWindow.refreshTheme();
    }
}

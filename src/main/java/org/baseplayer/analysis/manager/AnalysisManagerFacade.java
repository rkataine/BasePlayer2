package org.baseplayer.analysis.manager;

import javafx.stage.Window;
import org.baseplayer.io.VcfManager;
import org.baseplayer.variant.VariantFilter;

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

    public static void notifySampleDataChanged() {
        AnalysisManagerController.notifySampleDataChanged();
    }

    public static void notifySampleVisibilityChanged() {
        AnalysisManagerController.notifySampleVisibilityChanged();
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

package org.baseplayer.analysis.manager;

import javafx.stage.Window;
import org.baseplayer.io.VcfManager;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.ui.VariantManagerController;
import org.baseplayer.variant.ui.VariantManagerWindow;

/**
 * Public entry points for the Analysis Manager shell.
 * <p>
 * Until the FXML shell moves under {@code analysis.manager}, this façade
 * delegates to {@link VariantManagerWindow} / {@link VariantManagerController}.
 * Prefer this class over calling those types directly.
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
        VariantManagerWindow.openVariantManager(owner, vcfManager, onClose);
    }

    public static void openForSignalAnalysis() {
        VariantManagerWindow.openForSignalAnalysis();
    }

    public static void openForSignalDataset(String datasetId) {
        VariantManagerWindow.openForSignalDataset(datasetId);
    }

    public static void bringToFrontOrOpen() {
        VariantManagerWindow.bringToFrontOrOpen();
    }

    public static VariantManagerController getController() {
        return VariantManagerWindow.getCurrentController();
    }

    /**
     * Post-load table build/apply port for {@link VcfManager}.
     * {@code null} when Analysis is not open.
     */
    public static PostLoadTablePort getPostLoadTables() {
        return VariantManagerWindow.getCurrentController();
    }

    public static void setBusyStatus(String status) {
        AnalysisBusyGate gate = busyGate;
        if (gate != null) {
            gate.setStatus(status);
        }
    }

    public static void loadFilterState(VariantFilter filter) {
        VariantManagerController controller = getController();
        if (controller != null && filter != null) {
            controller.loadFilterState(filter);
        }
    }

    public static VariantFilter snapshotUiFilter() {
        VariantManagerController controller = getController();
        return controller != null ? controller.snapshotUiFilter() : null;
    }

    public static void notifySampleDataChanged() {
        VariantManagerController.notifySampleDataChanged();
    }

    public static void notifySampleVisibilityChanged() {
        VariantManagerController.notifySampleVisibilityChanged();
    }

    public static void closeAndDispose() {
        VariantManagerWindow.closeAndDispose();
    }

    public static boolean shouldShowToolbarButton() {
        return VariantManagerWindow.shouldShowToolbarButton();
    }

    public static boolean hasSignalDatasets() {
        return VariantManagerWindow.hasSignalDatasets();
    }

    public static boolean hasComparableSignalData() {
        return VariantManagerWindow.hasComparableSignalData();
    }

    public static boolean isOpen() {
        return VariantManagerWindow.isOpen();
    }

    public static void refreshTheme() {
        VariantManagerWindow.refreshTheme();
    }
}

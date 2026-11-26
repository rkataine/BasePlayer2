package org.baseplayer.analysis.manager;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.baseplayer.MainApp;
import org.baseplayer.controllers.MenuBarController;
import org.baseplayer.io.VcfManager;

import java.io.IOException;

/**
 * Singleton Analysis Manager window. Only one instance can be open at a time.
 * Prefer {@link AnalysisManagerFacade} for call sites outside this package.
 */
public class AnalysisManagerWindow {
    private static Stage currentStage = null;
    private static AnalysisManagerController currentController = null;
    private static boolean listenersAttached = false;

    public static void show(Window owner, VcfManager vcfManager, Runnable onClose) {
        if (currentStage != null) {
            MinimizedAnalysisManagerWindow.clearMinimizedFlag();
            if (currentStage.isIconified()) {
                currentStage.setIconified(false);
            }
            if (!currentStage.isShowing()) {
                currentStage.show();
            }
            currentStage.toFront();
            currentStage.requestFocus();
            if (currentController != null) {
                currentController.updateVcfManager(vcfManager);
            }
            MenuBarController.updateAnalysisManagerButtonVisibility();
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(MainApp.getResource("AnalysisManager.fxml"));
            Parent root = loader.load();

            AnalysisManagerController controller = loader.getController();

            Stage stage = new Stage();
            stage.initModality(Modality.NONE);
            stage.setTitle("Analysis");
            stage.setResizable(true);
            stage.setMinWidth(820);
            stage.setMinHeight(580);

            Scene scene = new Scene(root, 1050, 700);

            // Apply the same theme and styles as the main application
            org.baseplayer.ui.theme.AppTheme.setDark(MainApp.darkMode);
            if (MainApp.darkMode) {
                scene.getStylesheets().add(MainApp.getResource("theme-dark.css").toExternalForm());
            } else {
                scene.getStylesheets().add(MainApp.getResource("theme-light.css").toExternalForm());
            }
            scene.getStylesheets().add(MainApp.getResource("application.css").toExternalForm());

            stage.setScene(scene);

            // Set up controller with stage reference
            controller.setup(stage, vcfManager, onClose);

            stage.setOnCloseRequest(event -> {
                event.consume();
                stage.hide();
                MinimizedAnalysisManagerWindow.clearMinimizedFlag();
                if (onClose != null) {
                    onClose.run();
                }
                MenuBarController.updateAnalysisManagerButtonVisibility();
            });

            // Keep manager coupled to main app lifecycle without forcing owned-window behavior.
            if (owner != null) {
                owner.showingProperty().addListener((obs, wasShowing, isShowing) -> {
                    if (!isShowing) {
                        MinimizedAnalysisManagerWindow.handleCleanup();
                        if (stage.isShowing()) {
                            stage.hide();
                        }
                        MenuBarController.updateAnalysisManagerButtonVisibility();
                    }
                });
            }

            // Store singleton references
            currentStage = stage;
            currentController = controller;
            attachVisibilityListeners(stage);

            stage.show();
            MenuBarController.updateAnalysisManagerButtonVisibility();

        } catch (IOException e) {
           // System.err.println("Error loading Variant Manager FXML: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void attachVisibilityListeners(Stage stage) {
        if (listenersAttached || stage == null) {
            return;
        }
        listenersAttached = true;
        Runnable refresh = MenuBarController::updateAnalysisManagerButtonVisibility;
        stage.focusedProperty().addListener((obs, o, n) -> refresh.run());
        stage.iconifiedProperty().addListener((obs, o, n) -> refresh.run());
        stage.showingProperty().addListener((obs, o, n) -> {
            if (Boolean.TRUE.equals(n)) {
                MinimizedAnalysisManagerWindow.clearMinimizedFlag();
            }
            refresh.run();
        });
    }

    public static boolean isOpen() {
        return currentStage != null && currentStage.isShowing();
    }

    /** Re-apply theme stylesheets when the main app toggles light/dark mode. */
    public static void refreshTheme() {
        if (currentStage == null || currentStage.getScene() == null) {
            return;
        }
        org.baseplayer.ui.theme.AppTheme.setDark(MainApp.darkMode);
        javafx.scene.Scene scene = currentStage.getScene();
        scene.getStylesheets().clear();
        if (MainApp.darkMode) {
            scene.getStylesheets().add(MainApp.getResource("theme-dark.css").toExternalForm());
        } else {
            scene.getStylesheets().add(MainApp.getResource("theme-light.css").toExternalForm());
        }
        scene.getStylesheets().add(MainApp.getResource("application.css").toExternalForm());
    }

    public static boolean shouldShowToolbarButton() {
        if (VcfManager.getInstance().hasLoadedVcf()) {
            return true;
        }
        return hasSignalDatasets() || hasComparableSignalData();
    }

    /**
     * BED scores, BigWig, methylation BAM, or mapped tables (VALUE / allele-CN) —
     * enough to open Analysis without VCF.
     */
    public static boolean hasComparableSignalData() {
        org.baseplayer.services.SampleRegistry registry =
            org.baseplayer.services.ServiceRegistry.getInstance().getSampleRegistry();
        if (registry == null) {
            return false;
        }
        for (org.baseplayer.samples.SampleTrack track : registry.snapshotSampleTracks()) {
            if (track == null) {
                continue;
            }
            if (org.baseplayer.analysis.source.BedScoreSource.trackHasScoredBed(track)
                || org.baseplayer.analysis.source.BigWigValueSource.trackHasBigWig(track)
                || org.baseplayer.analysis.source.BamMethylSource.trackHasMethylBam(track)
                || org.baseplayer.analysis.source.BedScoreSource.firstBed(track) != null
                || org.baseplayer.analysis.source.MappedTableValueSource.trackHasValueTable(track)
                || org.baseplayer.analysis.source.AlleleCnLohValueSource.trackHasAlleleCn(track)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Open Analysis and focus the Signal tab (table / LOH / BigWig comparison).
     * Safe when the window is already open.
     */
    public static void openForSignalAnalysis() {
        bringToFrontOrOpen();
        AnalysisManagerController controller = getCurrentController();
        if (controller != null) {
            controller.selectSignalMode();
            controller.refreshSignalSources();
        }
    }

    /**
     * Open Analysis and focus a named Signal dataset tab.
     */
    public static void openForSignalDataset(String datasetId) {
        bringToFrontOrOpen();
        AnalysisManagerController controller = getCurrentController();
        if (controller != null) {
            controller.refreshSignalDataset(datasetId);
            controller.selectSignalDataset(datasetId);
        }
    }

    /** True when at least one named Signal dataset is registered. */
    public static boolean hasSignalDatasets() {
        return !org.baseplayer.analysis.signal.SignalDatasetRegistry.get().isEmpty();
    }

    public static void bringToFrontOrOpen() {
        MinimizedAnalysisManagerWindow.clearMinimizedFlag();
        Window owner = MainApp.stage;
        open(owner, VcfManager.getInstance(), null);
    }

    public static AnalysisManagerController getCurrentController() {
        return currentController;
    }

    /**
     * Tear down the singleton window so the next {@link #show} rebuilds FXML from scratch.
     * Used by New Project / Open Project session wipe.
     */
    public static void closeAndDispose() {
        MinimizedAnalysisManagerWindow.handleCleanup();
        MinimizedAnalysisManagerWindow.clearMinimizedFlag();

        AnalysisManagerController controller = currentController;
        Stage stage = currentStage;
        currentController = null;
        currentStage = null;
        // Allow visibility listeners to re-attach on the next Stage.
        listenersAttached = false;

        if (controller != null) {
            controller.cleanup();
        }
        if (stage != null) {
            stage.setOnCloseRequest(null);
            if (stage.isShowing()) {
                stage.hide();
            }
            stage.close();
        }
        MenuBarController.updateAnalysisManagerButtonVisibility();
    }

    public static void open(Window owner, VcfManager vcfManager, Runnable onClose) {
        if (vcfManager == null || owner == null) {
            return;
        }
        show(owner, vcfManager, onClose);
    }
}

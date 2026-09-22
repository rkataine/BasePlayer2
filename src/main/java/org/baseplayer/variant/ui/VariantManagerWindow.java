package org.baseplayer.variant.ui;

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
 * Singleton Variant Manager window. Only one instance can be open at a time.
 * Uses the unified CSS system with theme support.
 */
public class VariantManagerWindow {
    private static Stage currentStage = null;
    private static VariantManagerController currentController = null;
    private static boolean listenersAttached = false;

    public static void show(Window owner, VcfManager vcfManager, Runnable onClose) {
        if (currentStage != null) {
            MinimizedVariantManagerWindow.clearMinimizedFlag();
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
            MenuBarController.updateVariantManagerButtonVisibility();
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(MainApp.getResource("VariantManager.fxml"));
            Parent root = loader.load();

            VariantManagerController controller = loader.getController();

            Stage stage = new Stage();
            stage.initModality(Modality.NONE);
            stage.setTitle("Variant Manager");
            stage.setResizable(true);
            stage.setMinWidth(820);
            stage.setMinHeight(580);

            Scene scene = new Scene(root, 1050, 700);

            // Apply the same theme and styles as the main application
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
                MinimizedVariantManagerWindow.clearMinimizedFlag();
                if (onClose != null) {
                    onClose.run();
                }
                MenuBarController.updateVariantManagerButtonVisibility();
            });

            // Keep manager coupled to main app lifecycle without forcing owned-window behavior.
            if (owner != null) {
                owner.showingProperty().addListener((obs, wasShowing, isShowing) -> {
                    if (!isShowing) {
                        MinimizedVariantManagerWindow.handleCleanup();
                        if (stage.isShowing()) {
                            stage.hide();
                        }
                        MenuBarController.updateVariantManagerButtonVisibility();
                    }
                });
            }

            // Store singleton references
            currentStage = stage;
            currentController = controller;
            attachVisibilityListeners(stage);

            stage.show();
            MenuBarController.updateVariantManagerButtonVisibility();

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
        Runnable refresh = MenuBarController::updateVariantManagerButtonVisibility;
        stage.focusedProperty().addListener((obs, o, n) -> refresh.run());
        stage.iconifiedProperty().addListener((obs, o, n) -> refresh.run());
        stage.showingProperty().addListener((obs, o, n) -> {
            if (Boolean.TRUE.equals(n)) {
                MinimizedVariantManagerWindow.clearMinimizedFlag();
            }
            refresh.run();
        });
    }

    public static boolean isOpen() {
        return currentStage != null && currentStage.isShowing();
    }

    public static boolean shouldShowToolbarButton() {
        if (!VcfManager.getInstance().hasLoadedVcf()) {
            return false;
        }
        if (MinimizedVariantManagerWindow.isMinimized()) {
            return true;
        }
        if (currentStage == null) {
            return true;
        }
        if (!currentStage.isShowing() || currentStage.isIconified()) {
            return true;
        }
        return !currentStage.isFocused();
    }

    public static void bringToFrontOrOpen() {
        MinimizedVariantManagerWindow.clearMinimizedFlag();
        Window owner = MainApp.stage;
        openVariantManager(owner, VcfManager.getInstance(), null);
    }

    public static VariantManagerController getCurrentController() {
        return currentController;
    }

    public static void openVariantManager(Window owner, VcfManager vcfManager, Runnable onClose) {
        if (vcfManager == null || owner == null) {
            return;
        }
        show(owner, vcfManager, onClose);
    }
}

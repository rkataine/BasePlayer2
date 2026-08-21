package org.baseplayer.variant.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.baseplayer.MainApp;
import org.baseplayer.io.VcfManager;

import java.io.IOException;

/**
 * Singleton Variant Manager window. Only one instance can be open at a time.
 * Uses the unified CSS system with theme support.
 */
public class VariantManagerWindow {
    private static Stage currentStage = null;
    private static VariantManagerController currentController = null;

    public static void show(Window owner, VcfManager vcfManager, Runnable onClose) {
        // If already open, bring to front and update VcfManager reference
        if (currentStage != null && currentStage.isShowing()) {
            currentStage.toFront();
            if (currentController != null) {
                currentController.updateVcfManager(vcfManager);
            }
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
            
            // Store singleton references
            currentStage = stage;
            currentController = controller;
            
            // Handle window close — clear singleton references
            stage.setOnHidden(e -> {
                controller.cleanup();
                currentStage = null;
                currentController = null;
            });
            
            stage.show();
            
        } catch (IOException e) {
            System.err.println("Error loading Variant Manager FXML: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Check if the Variant Manager window is currently open. */
    public static boolean isOpen() {
        return currentStage != null && currentStage.isShowing();
    }
}

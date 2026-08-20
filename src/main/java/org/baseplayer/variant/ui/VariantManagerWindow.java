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
 * Utility class to show the FXML-based Variant Manager dialog.
 * Uses the unified CSS system with theme support.
 */
public class VariantManagerWindow {

    public static void show(Window owner, VcfManager vcfManager, Runnable onClose) {
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
            
            // Handle window close
            stage.setOnHidden(e -> controller.cleanup());
            
            stage.show();
            
        } catch (IOException e) {
            System.err.println("Error loading Variant Manager FXML: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

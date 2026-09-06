package org.baseplayer.components;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.io.VcfManager;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.RegionFetchCache;
import org.baseplayer.services.ServiceRegistry;

/**
 * A clickable text label that appears when the current viewport region 
 * is not cached in VCF data. Allows users to manually load variants for 
 * the current zoom level.
 */
public class LoadRegionButton extends Region {

    private final Label textLabel;
    private final Label debugLabel;
    private final DrawStackManager stackManager;

    public LoadRegionButton() {
        this.stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        
        // Set explicit width/height (not just pref) so StackPane respects sizing
        this.setMinWidth(120);
        this.setMaxWidth(120);
        this.setPrefWidth(120);
        this.setMinHeight(30);
        this.setMaxHeight(30);
        this.setPrefHeight(30);
        
        // Keep MANAGED so StackPane can properly layout with alignment
        this.setManaged(true);
        
        this.setStyle("-fx-padding: 0;");

        // Create main text label
        textLabel = new Label("[Load Region]");
        textLabel.setWrapText(true);
        textLabel.setStyle(
            "-fx-text-fill: #5a9fd4;" +
            "-fx-font-size: 10;" +
            "-fx-font-weight: bold;" +
            "-fx-cursor: hand;"
        );

        textLabel.setOnMouseEntered(e -> textLabel.setStyle(
            "-fx-text-fill: #4a8fb4;" +
            "-fx-font-size: 10;" +
            "-fx-font-weight: bold;" +
            "-fx-cursor: hand;"
        ));

        textLabel.setOnMouseExited(e -> textLabel.setStyle(
            "-fx-text-fill: #5a9fd4;" +
            "-fx-font-size: 10;" +
            "-fx-font-weight: bold;" +
            "-fx-cursor: hand;"
        ));

        textLabel.setOnMouseClicked(e -> loadCurrentRegion());

        // Create debug label showing coordinates and color
        debugLabel = new Label();
        debugLabel.setStyle(
            "-fx-text-fill: #888888;" +
            "-fx-font-size: 8;"
        );

        // Container for both labels
        VBox container = new VBox(1);
        container.setStyle("-fx-padding: 2;");
        container.getChildren().addAll(textLabel, debugLabel);

        this.getChildren().add(container);

        // Start HIDDEN - visibility is controlled by listener when samples load
        this.setVisible(false);
    }

    public void attachRegionListener() {
        DrawStack mainStack = stackManager.getHoverStack();
        if (mainStack == null) {
            return;
        }
        
        // Define the listener logic
        java.util.function.Consumer<Void> updateVisibility = unused -> {
            DrawStack stack = stackManager.getHoverStack();
            if (stack != null) {
                var region = stack.getRegion();
                if (region != null) {
                    RegionFetchCache cache = ServiceRegistry.getInstance().getRegionFetchCache();
                    boolean isCached = cache.isFetched("VCF", region.chrom(), (long) region.start(), (long) region.end());
                    var fetchedRegions = cache.getFetched("VCF", region.chrom());
                    
                    // Determine if button should be visible:
                    // Show button only if viewport extends BEYOND any cached region
                    boolean shouldShowButton = false;
                    
                    if (!isCached && !fetchedRegions.isEmpty()) {
                        // Viewport is not fully cached - check if it extends beyond a fetched region
                        var firstFetched = fetchedRegions.get(0);
                        boolean viewportExtendsBefore = region.start() < firstFetched.start();
                        boolean viewportExtendsAfter = region.end() > firstFetched.end();
                        
                        if (viewportExtendsBefore || viewportExtendsAfter) {
                            shouldShowButton = true;
                        }
                    }
                    
                    LoadRegionButton.this.setVisible(shouldShowButton);
                }
            }
        };
        
        // Attach listener to regionProperty
        mainStack.regionProperty().addListener((obs, oldVal, newVal) -> {
            updateVisibility.accept(null);
        });
        
        // IMPORTANT: Also check visibility immediately after attaching listener
        // because the region may already be set and the listener won't fire until next change
        updateVisibility.accept(null);
    }

    /**
     * Load variants for the current viewport region.
     */
    private void loadCurrentRegion() {
        DrawStack mainStack = stackManager.getHoverStack();
        if (mainStack == null) {
            return;
        }

        var region = mainStack.getRegion();
        if (region == null) {
            return;
        }

        textLabel.setText("[Loading...");
        textLabel.setStyle(
            "-fx-text-fill: #cccccc;" +
            "-fx-font-size: 10;" +
            "-fx-font-weight: bold;" +
            "-fx-cursor: wait;"
        );

        // Load variants for the current viewport region (with its exact bounds)
        VcfManager.getInstance().loadRegionVariants(
            region.chrom(),
            (long) region.start(),
            (long) region.end()
        );

        // After a brief delay, reset the button state back to normal
        // (The button will auto-hide once the region is marked as cached via the regionProperty listener)
        new Thread(() -> {
            try {
                Thread.sleep(300);  // Give the load some time to complete
                Platform.runLater(() -> {
                    textLabel.setText("[Load Region]");
                    textLabel.setStyle(
                        "-fx-text-fill: #5a9fd4;" +
                        "-fx-font-size: 10;" +
                        "-fx-font-weight: bold;" +
                        "-fx-cursor: hand;"
                    );
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
}

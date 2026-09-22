package org.baseplayer.components;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.io.VcfManager;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.RegionFetchCache;
import org.baseplayer.services.ServiceRegistry;

/**
 * Overlay control shown when the current viewport is outside cached VCF data.
 * Styled as a compact accent button so it stays visible on the genomic canvas.
 */
public class LoadRegionButton extends StackPane {

    private static final String STYLE_IDLE =
        "-fx-background-color: linear-gradient(to bottom, #3a9fd4 0%, #2a7fb0 100%);"
            + "-fx-background-radius: 5;"
            + "-fx-border-color: #7ec8f0;"
            + "-fx-border-radius: 5;"
            + "-fx-border-width: 1;"
            + "-fx-cursor: hand;"
            + "-fx-padding: 5 12 5 12;";

    private static final String STYLE_HOVER =
        "-fx-background-color: linear-gradient(to bottom, #4db0e0 0%, #3590c4 100%);"
            + "-fx-background-radius: 5;"
            + "-fx-border-color: #b0e0ff;"
            + "-fx-border-radius: 5;"
            + "-fx-border-width: 1;"
            + "-fx-cursor: hand;"
            + "-fx-padding: 5 12 5 12;"
            + "-fx-effect: dropshadow(gaussian, rgba(90, 180, 240, 0.55), 10, 0.35, 0, 0);";

    private static final String STYLE_LOADING =
        "-fx-background-color: linear-gradient(to bottom, #3c3c3c 0%, #2a2a2a 100%);"
            + "-fx-background-radius: 5;"
            + "-fx-border-color: #666666;"
            + "-fx-border-radius: 5;"
            + "-fx-border-width: 1;"
            + "-fx-cursor: wait;"
            + "-fx-padding: 5 12 5 12;";

    private static final String LABEL_IDLE =
        "-fx-text-fill: #f2f8ff; -fx-font-size: 11; -fx-font-weight: bold;";
    private static final String LABEL_LOADING =
        "-fx-text-fill: #bbbbbb; -fx-font-size: 11; -fx-font-weight: bold;";

    private final Label textLabel;
    private final DrawStackManager stackManager;
    private final RegionFetchCache regionFetchCache;
    private final Runnable cacheListener = this::refreshVisibilityOnFxThread;
    private final Set<DrawStack> stacksWithRegionListeners =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private boolean cacheListenerAttached;
    private boolean loading;

    public LoadRegionButton() {
        this.stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        this.regionFetchCache = ServiceRegistry.getInstance().getRegionFetchCache();

        setManaged(true);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        setAlignment(Pos.CENTER);
        setPadding(Insets.EMPTY);

        DropShadow idleShadow = new DropShadow();
        idleShadow.setColor(Color.rgb(0, 0, 0, 0.55));
        idleShadow.setRadius(8);
        idleShadow.setOffsetY(1);
        setEffect(idleShadow);

        textLabel = new Label("Load region");
        textLabel.setMouseTransparent(true);
        textLabel.setStyle(LABEL_IDLE);

        getChildren().add(textLabel);
        applyIdleStyle();

        setOnMouseEntered(e -> {
            if (!loading) {
                setStyle(STYLE_HOVER);
            }
        });
        setOnMouseExited(e -> {
            if (!loading) {
                applyIdleStyle();
            }
        });
        setOnMouseClicked(e -> {
            if (!loading) {
                loadCurrentRegion();
            }
        });

        setVisible(false);
    }

    public void attachRegionListener() {
        if (!cacheListenerAttached) {
            cacheListenerAttached = true;
            regionFetchCache.addListener(cacheListener);
        }
        for (DrawStack stack : stackManager.getStacks()) {
            attachStackRegionListener(stack);
        }
        updateVisibility();
    }

    private void attachStackRegionListener(DrawStack stack) {
        if (stack == null || !stacksWithRegionListeners.add(stack)) {
            return;
        }
        stack.regionProperty().addListener((obs, oldVal, newVal) -> updateVisibility());
    }

    private void refreshVisibilityOnFxThread() {
        if (Platform.isFxApplicationThread()) {
            updateVisibility();
        } else {
            Platform.runLater(this::updateVisibility);
        }
    }

    private void updateVisibility() {
        DrawStack stack = stackManager.getHoverStack();
        attachStackRegionListener(stack);
        if (stack == null) {
            setVisible(false);
            return;
        }

        var region = stack.getRegion();
        if (region == null) {
            setVisible(false);
            return;
        }

        boolean isCached = regionFetchCache.isFetched(
            "VCF", region.chrom(), (long) region.start(), (long) region.end());
        var fetchedRegions = regionFetchCache.getFetched("VCF", region.chrom());

        // Show when this chrom has some VCF coverage but the viewport is not fully covered.
        boolean shouldShowButton = !isCached && !fetchedRegions.isEmpty();

        if (loading && !VcfManager.getInstance().isLoading()) {
            loading = false;
            applyIdleStyle();
        }
        if (isCached) {
            loading = false;
            applyIdleStyle();
        }

        setVisible(shouldShowButton || loading);
        if (!shouldShowButton && !loading) {
            applyIdleStyle();
        }
    }

    private void loadCurrentRegion() {
        DrawStack mainStack = stackManager.getHoverStack();
        if (mainStack == null) {
            return;
        }

        var region = mainStack.getRegion();
        if (region == null) {
            return;
        }

        loading = true;
        textLabel.setText("Loading…");
        textLabel.setStyle(LABEL_LOADING);
        setStyle(STYLE_LOADING);

        VcfManager.getInstance().loadRegionVariants(
            region.chrom(),
            (long) region.start(),
            (long) region.end()
        );

        // Cache listener hides the button once the fetch is marked complete.
        // Re-check immediately for synchronous cache-reuse paths.
        updateVisibility();
    }

    private void applyIdleStyle() {
        textLabel.setText("Load region");
        textLabel.setStyle(LABEL_IDLE);
        setStyle(STYLE_IDLE);
    }
}

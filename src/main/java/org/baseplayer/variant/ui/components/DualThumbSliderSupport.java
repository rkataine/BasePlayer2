package org.baseplayer.variant.ui.components;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

/**
 * Shared dual-thumb stack used by {@link IntegerRangeSlider} and {@link DualRangeSlider}.
 * High slider draws the track underneath. Low sits on top but is clipped to its thumb only,
 * so the high thumb stays visible and receives mouse events everywhere else.
 */
final class DualThumbSliderSupport {

    private static final double THUMB_CLIP_PAD = 3;

    private DualThumbSliderSupport() {}

    static StackPane buildStack(Slider lowSlider, Slider highSlider) {
        lowSlider.getStyleClass().add("range-slider-low");
        highSlider.getStyleClass().add("range-slider-high");
        lowSlider.setShowTickLabels(false);
        lowSlider.setShowTickMarks(false);
        highSlider.setShowTickLabels(false);
        highSlider.setShowTickMarks(false);
        lowSlider.setPickOnBounds(false);

        StackPane track = new StackPane(highSlider, lowSlider);
        track.setAlignment(Pos.CENTER_LEFT);
        track.setPickOnBounds(false);
        track.getStyleClass().add("range-slider-stack");
        HBox.setHgrow(track, Priority.ALWAYS);

        installLowThumbOnly(lowSlider);
        return track;
    }

    /**
     * Clip the low slider to its thumb and make non-thumb chrome transparent / non-picking
     * so the high thumb is never buried under an invisible full-width layer.
     */
    private static void installLowThumbOnly(Slider lowSlider) {
        Runnable apply = () -> {
            clearNonThumbChrome(lowSlider);
            clipToThumb(lowSlider);
        };
        lowSlider.skinProperty().addListener((obs, oldSkin, skin) -> {
            if (skin != null) {
                Platform.runLater(apply);
            }
        });
        if (lowSlider.getSkin() != null) {
            Platform.runLater(apply);
        }
        lowSlider.valueProperty().addListener((obs, o, n) -> Platform.runLater(apply));
        lowSlider.layoutBoundsProperty().addListener((obs, o, n) -> Platform.runLater(apply));
        lowSlider.widthProperty().addListener((obs, o, n) -> Platform.runLater(apply));
    }

    private static void clipToThumb(Slider slider) {
        Node thumb = slider.lookup(".thumb");
        if (thumb == null) {
            slider.setClip(null);
            return;
        }
        Bounds b = thumb.getBoundsInParent();
        if (b.getWidth() <= 0 || b.getHeight() <= 0) {
            slider.setClip(null);
            return;
        }
        Rectangle clip = new Rectangle(
            b.getMinX() - THUMB_CLIP_PAD,
            b.getMinY() - THUMB_CLIP_PAD,
            b.getWidth() + 2 * THUMB_CLIP_PAD,
            b.getHeight() + 2 * THUMB_CLIP_PAD);
        slider.setClip(clip);
    }

    private static void clearNonThumbChrome(Slider slider) {
        Node thumb = slider.lookup(".thumb");
        for (Node n : slider.lookupAll("*")) {
            if (n == thumb || isAncestorOf(thumb, n)) {
                continue;
            }
            if (n instanceof Region region) {
                region.setBackground(null);
                region.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
            }
            n.setMouseTransparent(true);
            n.setPickOnBounds(false);
        }
        if (thumb != null) {
            thumb.setMouseTransparent(false);
            thumb.setPickOnBounds(true);
            Node walk = thumb;
            while (walk != null) {
                walk.setMouseTransparent(false);
                if (walk == slider) {
                    break;
                }
                walk = walk.getParent();
            }
        }
        slider.setPickOnBounds(false);
        Node track = slider.lookup(".track");
        if (track != null) {
            track.setMouseTransparent(true);
            track.setPickOnBounds(false);
            if (track instanceof Region region) {
                region.setBackground(null);
                region.setStyle("-fx-background-color: transparent;");
            }
        }
    }

    private static boolean isAncestorOf(Node node, Node possibleAncestor) {
        if (node == null || possibleAncestor == null) {
            return false;
        }
        Node walk = node.getParent();
        while (walk != null) {
            if (walk == possibleAncestor) {
                return true;
            }
            walk = walk.getParent();
        }
        return false;
    }

    static void configureBaseSlider(Slider slider) {
        slider.setSnapToTicks(false);
        slider.setShowTickLabels(false);
        slider.setShowTickMarks(false);
        slider.setPrefWidth(280);
        slider.setPrefHeight(22);
        slider.setMinHeight(22);
        slider.setMaxHeight(28);
        HBox.setHgrow(slider, Priority.ALWAYS);
    }
}

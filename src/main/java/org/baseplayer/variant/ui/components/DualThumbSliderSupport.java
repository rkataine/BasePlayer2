package org.baseplayer.variant.ui.components;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;

/**
 * Shared dual-thumb stack used by {@link IntegerRangeSlider} and {@link DualRangeSlider}.
 * High slider draws the track; low slider sits on top with a transparent, click-through
 * track so its thumb does not block the high thumb.
 */
final class DualThumbSliderSupport {

    private DualThumbSliderSupport() {}

    static StackPane buildStack(Slider lowSlider, Slider highSlider) {
        lowSlider.getStyleClass().add("range-slider-low");
        highSlider.getStyleClass().add("range-slider-high");
        // Single set of ticks/labels from the high slider underneath.
        lowSlider.setShowTickLabels(false);
        lowSlider.setShowTickMarks(false);

        StackPane track = new StackPane(highSlider, lowSlider);
        track.getStyleClass().add("range-slider-stack");
        HBox.setHgrow(track, Priority.ALWAYS);

        installLowTrackClickThrough(lowSlider);
        return track;
    }

    /** Low track must not steal presses meant for the high thumb/track below. */
    private static void installLowTrackClickThrough(Slider lowSlider) {
        Runnable apply = () -> {
            Node track = lowSlider.lookup(".track");
            if (track != null) {
                track.setMouseTransparent(true);
            }
        };
        lowSlider.skinProperty().addListener((obs, oldSkin, skin) -> {
            if (skin != null) {
                Platform.runLater(apply);
            }
        });
        if (lowSlider.getSkin() != null) {
            Platform.runLater(apply);
        }
    }

    static void configureBaseSlider(Slider slider) {
        slider.setSnapToTicks(false);
        slider.setPrefWidth(280);
        HBox.setHgrow(slider, Priority.ALWAYS);
    }
}

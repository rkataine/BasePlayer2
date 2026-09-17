package org.baseplayer.variant.ui;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Dual-thumb integer range control: low and high ends on a shared track.
 * Values are inclusive (e.g. 3–7 keeps variants shared by 3 to 7 samples).
 */
public class IntegerRangeSlider extends VBox {

    private final Slider lowSlider = new Slider();
    private final Slider highSlider = new Slider();
    private final TextField lowField = new TextField();
    private final TextField highField = new TextField();
    private final Label summaryLabel = new Label();

    private final IntegerProperty lowValue = new SimpleIntegerProperty(1);
    private final IntegerProperty highValue = new SimpleIntegerProperty(1);
    private final IntegerProperty absoluteMax = new SimpleIntegerProperty(1);

    private boolean adjusting;

    public IntegerRangeSlider() {
        getStyleClass().add("integer-range-slider");
        setSpacing(6);

        summaryLabel.getStyleClass().add("value-label");

        configureSlider(lowSlider);
        configureSlider(highSlider);
        lowSlider.setShowTickLabels(false);
        lowSlider.setShowTickMarks(false);
        lowSlider.getStyleClass().add("range-slider-low");
        highSlider.getStyleClass().add("range-slider-high");

        StackPane track = new StackPane(highSlider, lowSlider);
        track.getStyleClass().add("range-slider-stack");
        HBox.setHgrow(track, Priority.ALWAYS);

        lowField.getStyleClass().add("filter-field");
        highField.getStyleClass().add("filter-field");
        lowField.setPrefWidth(52);
        highField.setPrefWidth(52);

        Label toLabel = new Label("–");
        toLabel.getStyleClass().add("subsection-label");

        HBox controls = new HBox(8, lowField, toLabel, highField, track);
        controls.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(summaryLabel, controls);

        lowSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (adjusting) return;
            int v = clampLow((int) Math.round(newVal.doubleValue()));
            adjusting = true;
            lowSlider.setValue(v);
            adjusting = false;
            if (v != lowValue.get()) {
                lowValue.set(v);
            }
            syncFieldsAndSummary();
        });

        highSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (adjusting) return;
            int v = clampHigh((int) Math.round(newVal.doubleValue()));
            adjusting = true;
            highSlider.setValue(v);
            adjusting = false;
            if (v != highValue.get()) {
                highValue.set(v);
            }
            syncFieldsAndSummary();
        });

        lowValue.addListener((obs, oldVal, newVal) -> {
            if (adjusting) return;
            adjusting = true;
            lowSlider.setValue(newVal.intValue());
            adjusting = false;
            syncFieldsAndSummary();
        });

        highValue.addListener((obs, oldVal, newVal) -> {
            if (adjusting) return;
            adjusting = true;
            highSlider.setValue(newVal.intValue());
            adjusting = false;
            syncFieldsAndSummary();
        });

        absoluteMax.addListener((obs, oldVal, newVal) ->
            applyAbsoluteMax(oldVal.intValue(), newVal.intValue()));

        lowField.setOnAction(e -> commitField(lowField, true));
        highField.setOnAction(e -> commitField(highField, false));
        lowField.focusedProperty().addListener((obs, was, is) -> {
            if (!is) commitField(lowField, true);
        });
        highField.focusedProperty().addListener((obs, was, is) -> {
            if (!is) commitField(highField, false);
        });

        applyAbsoluteMax(1, 1);
    }

    private static void configureSlider(Slider slider) {
        slider.setMin(1);
        slider.setMax(1);
        slider.setValue(1);
        // Tick marks are for display only — never snap to them (that skips integers
        // when majorTickUnit > 1 for larger sample counts).
        slider.setSnapToTicks(false);
        slider.setMinorTickCount(0);
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(true);
        slider.setBlockIncrement(1);
        slider.setPrefWidth(280);
        HBox.setHgrow(slider, Priority.ALWAYS);
    }

    private void applyAbsoluteMax(int previousMax, int max) {
        int capped = Math.max(1, max);
        boolean highWasPinnedToMax = highValue.get() >= Math.max(1, previousMax);

        adjusting = true;
        lowSlider.setMax(capped);
        highSlider.setMax(capped);
        // Sparse labels when many samples; values still snap to every integer below.
        double labelTick = capped <= 10 ? 1 : Math.max(1, Math.ceil(capped / 5.0));
        lowSlider.setMajorTickUnit(labelTick);
        highSlider.setMajorTickUnit(labelTick);

        int low = Math.min(Math.max(1, lowValue.get()), capped);
        int high = highWasPinnedToMax
            ? capped
            : Math.min(Math.max(low, highValue.get()), capped);
        lowValue.set(low);
        highValue.set(high);
        lowSlider.setValue(low);
        highSlider.setValue(high);
        adjusting = false;
        syncFieldsAndSummary();
    }

    private int clampLow(int value) {
        return Math.max(1, Math.min(value, highValue.get()));
    }

    private int clampHigh(int value) {
        return Math.max(lowValue.get(), Math.min(value, absoluteMax.get()));
    }

    private void commitField(TextField field, boolean isLow) {
        try {
            int parsed = Integer.parseInt(field.getText().trim());
            int max = absoluteMax.get();
            if (isLow) {
                int high = highValue.get();
                lowValue.set(Math.max(1, Math.min(parsed, high)));
            } else {
                int low = lowValue.get();
                highValue.set(Math.max(low, Math.min(parsed, max)));
            }
        } catch (NumberFormatException ignored) {
            syncFieldsAndSummary();
        }
    }

    private void syncFieldsAndSummary() {
        int low = lowValue.get();
        int high = highValue.get();
        int total = absoluteMax.get();
        if (!lowField.isFocused()) {
            lowField.setText(String.valueOf(low));
        }
        if (!highField.isFocused()) {
            highField.setText(String.valueOf(high));
        }
        String unit = summaryUnit != null && !summaryUnit.isBlank() ? summaryUnit : "samples";
        if (low == 1 && high >= total) {
            summaryLabel.setText("All variants (shared by 1–" + total + " of " + total + " " + unit + ")");
        } else {
            summaryLabel.setText("Variants shared by " + low + "–" + high + " of " + total + " " + unit);
        }
    }

    /** Optional noun after the sample count, e.g. {@code "samples (window clusters)"}. */
    private String summaryUnit = "samples";

    public void setSummaryUnit(String unit) {
        this.summaryUnit = unit == null || unit.isBlank() ? "samples" : unit;
        syncFieldsAndSummary();
    }

    public IntegerProperty lowValueProperty() { return lowValue; }
    public IntegerProperty highValueProperty() { return highValue; }
    public IntegerProperty absoluteMaxProperty() { return absoluteMax; }

    public int getLowValue() { return lowValue.get(); }
    public int getHighValue() { return highValue.get(); }
    public int getAbsoluteMax() { return absoluteMax.get(); }

    public void setRange(int low, int high) {
        int max = absoluteMax.get();
        int lo = Math.max(1, Math.min(low, max));
        int hi = Math.max(lo, Math.min(high, max));
        adjusting = true;
        lowValue.set(lo);
        highValue.set(hi);
        lowSlider.setValue(lo);
        highSlider.setValue(hi);
        adjusting = false;
        syncFieldsAndSummary();
    }

    public void setAbsoluteMax(int max) {
        absoluteMax.set(Math.max(1, max));
    }

    public void resetToFullRange() {
        setRange(1, absoluteMax.get());
    }
}

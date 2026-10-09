package org.baseplayer.variant.ui.components;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Dual-thumb continuous range control (same layout as {@link IntegerRangeSlider}).
 * Defaults to the unit interval [0, 1] (e.g. allele fraction).
 */
public class DualRangeSlider extends VBox {

    private static final double EPSILON = 1e-9;

    private final Slider lowSlider = new Slider();
    private final Slider highSlider = new Slider();
    private final TextField lowField = new TextField();
    private final TextField highField = new TextField();
    private final Label summaryLabel = new Label();

    private final DoubleProperty lowValue = new SimpleDoubleProperty(0);
    private final DoubleProperty highValue = new SimpleDoubleProperty(1);
    private final DoubleProperty absoluteMin = new SimpleDoubleProperty(0);
    private final DoubleProperty absoluteMax = new SimpleDoubleProperty(1);

    private boolean adjusting;
    private String summaryNoun = "allele fraction";

    public DualRangeSlider() {
        getStyleClass().add("dual-range-slider");
        setSpacing(6);

        summaryLabel.getStyleClass().add("value-label");

        DualThumbSliderSupport.configureBaseSlider(lowSlider);
        DualThumbSliderSupport.configureBaseSlider(highSlider);
        lowSlider.setMin(0);
        lowSlider.setMax(1);
        lowSlider.setValue(0);
        highSlider.setMin(0);
        highSlider.setMax(1);
        highSlider.setValue(1);
        highSlider.setMajorTickUnit(0.25);
        highSlider.setMinorTickCount(4);
        highSlider.setShowTickMarks(true);
        highSlider.setShowTickLabels(true);
        highSlider.setBlockIncrement(0.05);
        lowSlider.setBlockIncrement(0.05);

        StackPane track = DualThumbSliderSupport.buildStack(lowSlider, highSlider);

        lowField.getStyleClass().add("filter-field");
        highField.getStyleClass().add("filter-field");
        lowField.setPrefWidth(60);
        highField.setPrefWidth(60);

        Label toLabel = new Label("–");
        toLabel.getStyleClass().add("subsection-label");

        HBox controls = new HBox(8, lowField, toLabel, highField, track);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(track, Priority.ALWAYS);

        getChildren().addAll(summaryLabel, controls);

        lowSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (adjusting) return;
            double v = clampLow(newVal.doubleValue());
            adjusting = true;
            lowSlider.setValue(v);
            adjusting = false;
            if (Math.abs(v - lowValue.get()) > EPSILON) {
                lowValue.set(v);
            }
            syncFieldsAndSummary();
        });

        highSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (adjusting) return;
            double v = clampHigh(newVal.doubleValue());
            adjusting = true;
            highSlider.setValue(v);
            adjusting = false;
            if (Math.abs(v - highValue.get()) > EPSILON) {
                highValue.set(v);
            }
            syncFieldsAndSummary();
        });

        lowValue.addListener((obs, oldVal, newVal) -> {
            if (adjusting) return;
            adjusting = true;
            lowSlider.setValue(newVal.doubleValue());
            adjusting = false;
            syncFieldsAndSummary();
        });

        highValue.addListener((obs, oldVal, newVal) -> {
            if (adjusting) return;
            adjusting = true;
            highSlider.setValue(newVal.doubleValue());
            adjusting = false;
            syncFieldsAndSummary();
        });

        lowField.setOnAction(e -> commitField(lowField, true));
        highField.setOnAction(e -> commitField(highField, false));
        lowField.focusedProperty().addListener((obs, was, is) -> {
            if (!is) commitField(lowField, true);
        });
        highField.focusedProperty().addListener((obs, was, is) -> {
            if (!is) commitField(highField, false);
        });

        applyAbsoluteRange(0, 1);
    }

    private void applyAbsoluteRange(double min, double max) {
        double loBound = min;
        double hiBound = Math.max(min + EPSILON, max);

        adjusting = true;
        absoluteMin.set(loBound);
        absoluteMax.set(hiBound);
        lowSlider.setMin(loBound);
        lowSlider.setMax(hiBound);
        highSlider.setMin(loBound);
        highSlider.setMax(hiBound);

        double span = hiBound - loBound;
        highSlider.setMajorTickUnit(span <= 1.0 + EPSILON ? 0.25 : span / 4.0);

        double low = clamp(lowValue.get(), loBound, hiBound);
        double high = clamp(highValue.get(), low, hiBound);
        lowValue.set(low);
        highValue.set(high);
        lowSlider.setValue(low);
        highSlider.setValue(high);
        adjusting = false;
        syncFieldsAndSummary();
    }

    private double clampLow(double value) {
        return clamp(value, absoluteMin.get(), highValue.get());
    }

    private double clampHigh(double value) {
        return clamp(value, lowValue.get(), absoluteMax.get());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private void commitField(TextField field, boolean isLow) {
        try {
            double parsed = Double.parseDouble(field.getText().trim());
            if (isLow) {
                lowValue.set(clamp(parsed, absoluteMin.get(), highValue.get()));
            } else {
                highValue.set(clamp(parsed, lowValue.get(), absoluteMax.get()));
            }
        } catch (NumberFormatException ignored) {
            syncFieldsAndSummary();
        }
    }

    private void syncFieldsAndSummary() {
        double low = lowValue.get();
        double high = highValue.get();
        if (!lowField.isFocused()) {
            lowField.setText(format(low));
        }
        if (!highField.isFocused()) {
            highField.setText(format(high));
        }
        boolean full = low <= absoluteMin.get() + EPSILON
            && high >= absoluteMax.get() - EPSILON;
        if (full) {
            summaryLabel.setText("All " + summaryNoun + "s ("
                + format(absoluteMin.get()) + "–" + format(absoluteMax.get()) + ")");
        } else {
            summaryLabel.setText(capitalize(summaryNoun) + " " + format(low) + "–" + format(high));
        }
    }

    private static String format(double v) {
        return String.format("%.2f", v);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public void setSummaryNoun(String noun) {
        this.summaryNoun = noun == null || noun.isBlank() ? "value" : noun;
        syncFieldsAndSummary();
    }

    public DoubleProperty lowValueProperty() { return lowValue; }
    public DoubleProperty highValueProperty() { return highValue; }

    public double getLowValue() { return lowValue.get(); }
    public double getHighValue() { return highValue.get(); }
    public double getAbsoluteMin() { return absoluteMin.get(); }
    public double getAbsoluteMax() { return absoluteMax.get(); }

    public void setRange(double low, double high) {
        double lo = clamp(low, absoluteMin.get(), absoluteMax.get());
        double hi = clamp(high, lo, absoluteMax.get());
        adjusting = true;
        lowValue.set(lo);
        highValue.set(hi);
        lowSlider.setValue(lo);
        highSlider.setValue(hi);
        adjusting = false;
        syncFieldsAndSummary();
    }

    public void setAbsoluteRange(double min, double max) {
        applyAbsoluteRange(min, max);
    }

    public void resetToFullRange() {
        setRange(absoluteMin.get(), absoluteMax.get());
    }
}

package org.baseplayer.components;

import org.baseplayer.ui.theme.AppTheme;
import org.baseplayer.utils.GeneColors;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;

/**
 * Gene-sidebar-style toggle: colored swatch with strike when off, or ink checkbox chrome.
 * Exposes {@link #selectedProperty()} so callers can wire like a CheckBox.
 */
public class ColoredToggle extends HBox {

  private final Region chrome = new Region();
  private final Polyline check = new Polyline();
  private final Line strike = new Line(1, 9, 11, 1);
  private final StackPane chromePane = new StackPane();
  private final Label label = new Label();
  private final BooleanProperty selected = new SimpleBooleanProperty(this, "selected", false);
  private Color swatchColor;
  private boolean inkMode;
  private EventHandler<ActionEvent> onAction;

  /** Ink checkbox with optional label (select-all / boolean filters). */
  public ColoredToggle(String text) {
    this(text, null);
  }

  /** Colored swatch row when {@code color} is non-null; otherwise ink checkbox. */
  public ColoredToggle(String text, Color color) {
    this.swatchColor = color;
    this.inkMode = color == null;
    getStyleClass().add("colored-toggle");
    setSpacing(5);
    setAlignment(Pos.CENTER_LEFT);
    setPadding(new Insets(2, 4, 2, 2));
    setCursor(Cursor.HAND);
    setMaxWidth(Double.MAX_VALUE);

    chrome.setMinSize(12, 10);
    chrome.setPrefSize(12, 10);
    chrome.setMaxSize(12, 10);
    chrome.setMouseTransparent(true);

    if (inkMode) {
      check.getPoints().setAll(2.4, 5.5, 5.0, 7.8, 9.8, 2.2);
      check.setStrokeWidth(1.6);
      check.setFill(null);
      check.setMouseTransparent(true);
      chromePane.getChildren().setAll(chrome, check);
    } else {
      strike.setStrokeWidth(1.2);
      strike.setMouseTransparent(true);
      chromePane.getChildren().setAll(chrome, strike);
    }
    chromePane.setMinSize(12, 10);
    chromePane.setPrefSize(12, 10);
    chromePane.setMaxSize(12, 10);
    chromePane.setMouseTransparent(true);
    chromePane.setAlignment(Pos.CENTER);

    label.setText(text == null ? "" : text);
    label.setStyle("-fx-font-size: 11px;");
    label.setMaxWidth(Double.MAX_VALUE);
    label.setMouseTransparent(true);
    boolean showLabel = text != null && !text.isBlank();
    label.setVisible(showLabel);
    label.setManaged(showLabel);
    if (showLabel) {
      HBox.setHgrow(label, Priority.ALWAYS);
      getChildren().setAll(chromePane, label);
    } else {
      getChildren().setAll(chromePane);
    }

    selected.addListener((obs, was, is) -> applyStyle());
    addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
      if (e.isConsumed() || isDisabled()) {
        return;
      }
      setSelected(!isSelected());
      if (onAction != null) {
        onAction.handle(new ActionEvent(this, this));
      }
      e.consume();
    });
    setOnMouseEntered(e -> applyHover(true));
    setOnMouseExited(e -> applyHover(false));
    applyStyle();
  }

  /** Ink select-all chrome with no label. */
  public ColoredToggle() {
    this("");
  }

  public BooleanProperty selectedProperty() {
    return selected;
  }

  public boolean isSelected() {
    return selected.get();
  }

  public void setSelected(boolean value) {
    selected.set(value);
  }

  public String getText() {
    return label.getText();
  }

  public void setText(String text) {
    label.setText(text == null ? "" : text);
    boolean showLabel = text != null && !text.isBlank();
    label.setVisible(showLabel);
    label.setManaged(showLabel);
    if (showLabel && !getChildren().contains(label)) {
      HBox.setHgrow(label, Priority.ALWAYS);
      getChildren().setAll(chromePane, label);
    } else if (!showLabel && getChildren().contains(label)) {
      getChildren().setAll(chromePane);
    }
  }

  public void setOnAction(EventHandler<ActionEvent> handler) {
    this.onAction = handler;
  }

  public EventHandler<ActionEvent> getOnAction() {
    return onAction;
  }

  public void setTooltip(Tooltip tooltip) {
    Tooltip.install(this, tooltip);
  }

  /**
   * Switch to (or update) colored-swatch mode — e.g. COSMIC orange matching the gene sidebar.
   * Pass {@code null} to revert to ink checkbox chrome.
   */
  public void setSwatchColor(Color color) {
    this.swatchColor = color;
    this.inkMode = color == null;
    if (inkMode) {
      check.getPoints().setAll(2.4, 5.5, 5.0, 7.8, 9.8, 2.2);
      check.setStrokeWidth(1.6);
      check.setFill(null);
      check.setMouseTransparent(true);
      chromePane.getChildren().setAll(chrome, check);
    } else {
      strike.setStrokeWidth(1.2);
      strike.setMouseTransparent(true);
      chromePane.getChildren().setAll(chrome, strike);
    }
    applyStyle();
  }

  private void applyStyle() {
    boolean on = isSelected();
    var chromeTheme = AppTheme.chrome();
    var canvas = AppTheme.canvas();
    if (inkMode) {
      Color stroke = on ? canvas.axisInk() : chromeTheme.muted();
      chrome.setStyle(
          "-fx-background-color: transparent;"
              + "-fx-border-color: " + GeneColors.toHexString(stroke) + ";"
              + "-fx-border-width: 1.2;"
              + "-fx-border-radius: 2;"
              + "-fx-background-radius: 2;");
      check.setStroke(canvas.axisInk());
      check.setVisible(on);
    } else {
      Color color = swatchColor != null ? swatchColor : Color.GRAY;
      double alpha = on ? 0.95 : 0.28;
      String fill = String.format(
          "rgba(%d,%d,%d,%.2f)",
          (int) (color.getRed() * 255),
          (int) (color.getGreen() * 255),
          (int) (color.getBlue() * 255),
          alpha);
      chrome.setStyle(
          "-fx-background-color: " + fill + ";"
              + "-fx-background-radius: 2;");
      strike.setVisible(!on);
      strike.setStroke(chromeTheme.muted());
    }
    label.setTextFill(on ? canvas.axisInk() : chromeTheme.muted());
  }

  private void applyHover(boolean hovered) {
    if (hovered && !isDisabled()) {
      // Light wash only — avoid opaque elevated blocks over transparent panels.
      String wash = AppTheme.isDark()
          ? "rgba(255,255,255,0.06)"
          : "rgba(0,0,0,0.05)";
      setStyle(
          "-fx-background-color: " + wash + ";"
              + "-fx-background-radius: 3;");
    } else {
      setStyle("-fx-background-color: transparent;");
    }
  }
}

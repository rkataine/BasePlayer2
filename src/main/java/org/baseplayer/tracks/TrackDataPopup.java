package org.baseplayer.tracks;

import org.baseplayer.utils.AppFonts;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.stage.Window;

/**
 * Base popup for displaying track item data when user clicks on a track.
 * Subclasses should override buildContent() to provide specific content.
 * 
 * Provides common styling and utility methods for consistent appearance
 * across all track popups.
 */
public abstract class TrackDataPopup {
  
  protected static final double MAX_WIDTH = 400;
  protected static final double MAX_HEIGHT = 350;
  
  protected final Popup popup;
  protected final VBox content;
  
  protected TrackDataPopup() {
    popup = new Popup();
    popup.setAutoHide(true);
    popup.setHideOnEscape(true);
    
    content = new VBox(6);
    content.setPadding(new Insets(12));
    content.setMaxWidth(MAX_WIDTH);
    content.setStyle(
      "-fx-background-color: rgba(30, 30, 30, 0.98);" +
      "-fx-background-radius: 8;" +
      "-fx-border-color: #555555;" +
      "-fx-border-radius: 8;" +
      "-fx-border-width: 1;"
    );
    
    popup.getContent().add(content);
  }
  
  /**
   * Show the popup at the specified position.
   */
  public void show(Window owner, double x, double y) {
    content.getChildren().clear();
    buildContent();
    popup.show(owner, x, y);
  }
  
  /**
   * Hide the popup.
   */
  public void hide() {
    popup.hide();
  }
  
  /**
   * Check if popup is currently showing.
   */
  public boolean isShowing() {
    return popup.isShowing();
  }
  
  /**
   * Build the popup content. Subclasses must implement this.
   */
  protected abstract void buildContent();
  
  // ============= Utility methods for building content =============
  
  /**
   * Add a header section with title.
   */
  protected void addHeader(String title, Color titleColor) {
    Label titleLabel = new Label(title);
    titleLabel.setFont(AppFonts.getBoldFont(14));
    titleLabel.setTextFill(titleColor);
    content.getChildren().add(titleLabel);
  }
  
  /**
   * Add a header with title and subtitle.
   */
  protected void addHeader(String title, String subtitle, Color titleColor) {
    HBox header = new HBox(10);
    header.setAlignment(Pos.CENTER_LEFT);
    
    Label titleLabel = new Label(title);
    titleLabel.setFont(AppFonts.getBoldFont(14));
    titleLabel.setTextFill(titleColor);
    
    Label subtitleLabel = new Label(subtitle);
    subtitleLabel.setFont(AppFonts.getUIFont());
    subtitleLabel.setTextFill(Color.LIGHTGRAY);
    subtitleLabel.setStyle("-fx-background-color: rgba(80,80,80,0.5); -fx-padding: 2 6; -fx-background-radius: 3;");
    
    header.getChildren().addAll(titleLabel, subtitleLabel);
    content.getChildren().add(header);
  }
  
  /**
   * Add a simple key-value info row.
   */
  protected void addInfoRow(String label, String value) {
    addInfoRow(label, value, Color.LIGHTGRAY);
  }
  
  /**
   * Add a key-value info row with custom value color.
   */
  protected void addInfoRow(String label, String value, Color valueColor) {
    HBox row = new HBox(8);
    row.setAlignment(Pos.CENTER_LEFT);
    
    Label labelNode = new Label(label + ":");
    labelNode.setFont(AppFonts.getUIFont());
    labelNode.setTextFill(Color.GRAY);
    labelNode.setMinWidth(90);
    
    Label valueNode = new Label(value);
    valueNode.setFont(AppFonts.getUIFont());
    valueNode.setTextFill(valueColor);
    valueNode.setWrapText(true);
    HBox.setHgrow(valueNode, Priority.ALWAYS);
    
    row.getChildren().addAll(labelNode, valueNode);
    content.getChildren().add(row);
  }
  
  /**
   * Add an info row with a clickable link.
   */
  protected void addClickableInfoRow(String label, String displayText, Runnable action) {
    HBox row = new HBox(8);
    row.setAlignment(Pos.CENTER_LEFT);
    
    Label labelNode = new Label(label + ":");
    labelNode.setFont(AppFonts.getUIFont());
    labelNode.setTextFill(Color.GRAY);
    labelNode.setMinWidth(90);
    
    Hyperlink link = new Hyperlink(displayText);
    link.setFont(AppFonts.getUIFont());
    link.setTextFill(Color.rgb(100, 180, 255));
    link.setBorder(null);
    link.setPadding(Insets.EMPTY);
    link.setOnAction(e -> action.run());
    
    row.getChildren().addAll(labelNode, link);
    content.getChildren().add(row);
  }
  
  /**
   * Add a separator line.
   */
  protected void addSeparator() {
    content.getChildren().add(new Separator());
  }
  
  /**
   * Add a section title.
   */
  protected void addSectionTitle(String title) {
    Label titleLabel = new Label(title);
    titleLabel.setFont(AppFonts.getUIFont());
    titleLabel.setTextFill(Color.GRAY);
    titleLabel.setStyle("-fx-padding: 4 0 0 0;");
    content.getChildren().add(titleLabel);
  }
  
  /**
   * Add a badge/tag element.
   */
  protected void addBadge(String text, Color bgColor, Color textColor) {
    Label badge = new Label(text);
    badge.setFont(AppFonts.getMonoFont(10));
    badge.setTextFill(textColor);
    badge.setStyle(String.format(
        "-fx-background-color: %s; -fx-padding: 2 6; -fx-background-radius: 3;",
        toRgbString(bgColor)
    ));
    content.getChildren().add(badge);
  }
  
  /**
   * Add a row of badges.
   */
  protected HBox createBadgeRow() {
    HBox row = new HBox(6);
    row.setAlignment(Pos.CENTER_LEFT);
    return row;
  }
  
  /**
   * Create a badge node for adding to a row.
   */
  protected Label createBadge(String text, Color bgColor, Color textColor) {
    Label badge = new Label(text);
    badge.setFont(AppFonts.getMonoFont(10));
    badge.setTextFill(textColor);
    badge.setStyle(String.format(
        "-fx-background-color: %s; -fx-padding: 2 6; -fx-background-radius: 3;",
        toRgbString(bgColor)
    ));
    return badge;
  }
  
  /**
   * Open URL in default browser.
   */
  protected void openUrl(String url) {
    try {
      java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
    } catch (Exception e) {
      System.err.println("Failed to open URL: " + url);
    }
  }
  
  /**
   * Convert Color to CSS rgb() string.
   */
  protected String toRgbString(Color color) {
    return String.format("rgb(%d,%d,%d)", 
        (int)(color.getRed() * 255),
        (int)(color.getGreen() * 255),
        (int)(color.getBlue() * 255));
  }
}

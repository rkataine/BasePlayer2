package org.baseplayer.features;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.baseplayer.MainApp;
import org.baseplayer.io.APIs.UcscApiClient;
import org.baseplayer.io.UcscTrackInfo;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Dialog for browsing and adding UCSC tracks dynamically.
 * Fetches available tracks from the UCSC API and displays them grouped by category.
 */
public class UcscTracksBrowser {
  
  private final Stage dialog;
  private final VBox contentBox;
  private final TextField searchField;
  private final ProgressIndicator loadingIndicator;
  private final Label statusLabel;
  private final Button refreshBtn;
  private final FeatureTracksCanvas featureTracksCanvas;
  private final String genome;
  
  private List<UcscTrackInfo> allTracks = new ArrayList<>();
  private Map<String, List<UcscTrackInfo>> tracksByGroup = new HashMap<>();
  
  public UcscTracksBrowser(Stage owner, FeatureTracksCanvas featureTracksCanvas, String genome) {
    this.featureTracksCanvas = featureTracksCanvas;
    this.genome = genome != null ? genome : "hg38";
    
    dialog = new Stage(StageStyle.DECORATED);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initOwner(owner);
    dialog.setTitle("UCSC Genome Browser Tracks - " + this.genome);
    dialog.setWidth(700);
    dialog.setHeight(600);
    
    VBox root = new VBox(12);
    root.setPadding(new Insets(16));
    root.setStyle("-fx-background-color: #2b2b2b;");
    
    // Header with title and refresh button
    HBox headerBox = new HBox(12);
    headerBox.setAlignment(Pos.CENTER_LEFT);
    
    Label title = new Label("Browse UCSC Tracks");
    title.setFont(Font.font("Segoe UI", 16));
    title.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: bold;");
    HBox.setHgrow(title, Priority.ALWAYS);
    
    refreshBtn = new Button("⟳ Refresh");
    refreshBtn.setStyle("-fx-background-color: #0078d4; -fx-text-fill: white; -fx-font-size: 11px;");
    refreshBtn.setOnAction(e -> refreshTracks());
    
    headerBox.getChildren().addAll(title, refreshBtn);
    
    // Search box
    searchField = new TextField();
    searchField.setPromptText("Search tracks...");
    searchField.setStyle("-fx-background-color: #3c3c3c; -fx-text-fill: #cccccc; -fx-prompt-text-fill: #888888;");
    searchField.textProperty().addListener((obs, oldVal, newVal) -> filterTracks(newVal));
    
    // Loading indicator
    loadingIndicator = new ProgressIndicator();
    loadingIndicator.setMaxSize(40, 40);
    loadingIndicator.setVisible(true);
    
    // Status label
    statusLabel = new Label("Loading tracks from UCSC...");
    statusLabel.setStyle("-fx-text-fill: #888888;");
    
    // Content area (scrollable)
    contentBox = new VBox(8);
    contentBox.setPadding(new Insets(8));
    
    ScrollPane scrollPane = new ScrollPane(contentBox);
    scrollPane.setFitToWidth(true);
    scrollPane.setStyle("-fx-background: #2b2b2b; -fx-background-color: #2b2b2b;");
    scrollPane.setFocusTraversable(false); // Prevent scroll jumping on button clicks
    VBox.setVgrow(scrollPane, Priority.ALWAYS);
    
    // Close button
    HBox buttonBox = new HBox(8);
    buttonBox.setAlignment(Pos.CENTER_RIGHT);
    
    Button closeBtn = new Button("Close");
    closeBtn.setStyle("-fx-background-color: #555555; -fx-text-fill: #cccccc;");
    closeBtn.setOnAction(e -> dialog.close());
    buttonBox.getChildren().add(closeBtn);
    
    root.getChildren().addAll(headerBox, searchField, loadingIndicator, statusLabel, scrollPane, buttonBox);
    
    Scene scene = new Scene(root);
    // Apply BasePlayer stylesheets for consistent look
    scene.getStylesheets().add(MainApp.getResource("styles.css").toExternalForm());
    if (MainApp.darkMode) {
      scene.getStylesheets().add(MainApp.getResource("darkmode.css").toExternalForm());
    }
    dialog.setScene(scene);
    
    // Fetch tracks after showing
    Platform.runLater(() -> fetchTracks(false));
  }
  
  private void refreshTracks() {
    // Clear the tracks list cache and refetch
    allTracks.clear();
    tracksByGroup.clear();
    contentBox.getChildren().clear();
    fetchTracks(true);
  }
  
  private void fetchTracks(boolean forceRefresh) {
    loadingIndicator.setVisible(true);
    refreshBtn.setDisable(true);
    statusLabel.setText("Loading tracks from UCSC...");
    statusLabel.setStyle("-fx-text-fill: #888888;");
    
    CompletableFuture<List<UcscTrackInfo>> future;
    
    if (forceRefresh) {
      // Clear cache and fetch fresh data
      future = UcscApiClient.fetchAvailableTracksForceRefresh(genome);
    } else {
      // Use cached data if available
      future = UcscApiClient.fetchAvailableTracks(genome);
    }
    
    future.thenAccept(tracks -> Platform.runLater(() -> {
      loadingIndicator.setVisible(false);
      refreshBtn.setDisable(false);
      
      if (tracks.isEmpty()) {
        statusLabel.setText("Failed to load tracks. Check network connection.");
        statusLabel.setStyle("-fx-text-fill: #ff6b6b;");
        return;
      }
      
      allTracks = tracks.stream()
          .filter(UcscTrackInfo::isSupported)
          .collect(Collectors.toList());
      
      String cacheStatus = forceRefresh ? " (refreshed)" : "";
      statusLabel.setText(String.format("Loaded %d tracks%s", allTracks.size(), cacheStatus));
      statusLabel.setStyle("-fx-text-fill: #4CAF50;");
      
      // Group tracks
      tracksByGroup = allTracks.stream()
          .collect(Collectors.groupingBy(UcscTrackInfo::group));
      
      displayTracks("");
    }));
  }
  
  private void filterTracks(String query) {
    if (allTracks.isEmpty()) return;
    displayTracks(query);
  }
  
  private void displayTracks(String query) {
    contentBox.getChildren().clear();
    
    String lowerQuery = query.toLowerCase();
    
    // Filter tracks by query
    List<UcscTrackInfo> filtered = allTracks.stream()
        .filter(t -> query.isEmpty() || 
                     t.shortLabel().toLowerCase().contains(lowerQuery) ||
                     t.longLabel().toLowerCase().contains(lowerQuery) ||
                     t.trackName().toLowerCase().contains(lowerQuery))
        .collect(Collectors.toList());
    
    if (filtered.isEmpty()) {
      Label noResults = new Label("No tracks found matching \"" + query + "\"");
      noResults.setStyle("-fx-text-fill: #888888;");
      contentBox.getChildren().add(noResults);
      return;
    }
    
    // Group filtered tracks
    Map<String, List<UcscTrackInfo>> groups = filtered.stream()
        .collect(Collectors.groupingBy(UcscTrackInfo::group));
    
    // Create titled panes for each group
    List<String> sortedGroups = new ArrayList<>(groups.keySet());
    sortedGroups.sort(String::compareTo);
    
    for (String group : sortedGroups) {
      List<UcscTrackInfo> groupTracks = groups.get(group);
      if (groupTracks.isEmpty()) continue;
      
      VBox trackList = new VBox(4);
      trackList.setPadding(new Insets(8));
      
      for (UcscTrackInfo track : groupTracks) {
        HBox trackRow = createTrackRow(track);
        trackList.getChildren().add(trackRow);
      }
      
      TitledPane pane = new TitledPane();
      String groupLabel = groupTracks.get(0).getGroupLabel() + " (" + groupTracks.size() + ")";
      pane.setText(groupLabel);
      pane.setContent(trackList);
      pane.setExpanded(sortedGroups.size() <= 5); // Auto-expand if few groups
      pane.setFocusTraversable(false); // Prevent focus-related scrolling
      // Styling is now handled by CSS
      
      contentBox.getChildren().add(pane);
    }
  }
  
  private HBox createTrackRow(UcscTrackInfo track) {
    HBox row = new HBox(8);
    row.setAlignment(Pos.CENTER_LEFT);
    row.setPadding(new Insets(4));
    row.setStyle("-fx-background-color: #2b2b2b; -fx-background-radius: 4;");
    
    // Track info
    VBox info = new VBox(2);
    
    Label nameLabel = new Label(track.shortLabel());
    nameLabel.setFont(Font.font("Segoe UI", 11));
    nameLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-weight: bold;");
    
    Label descLabel = new Label(track.longLabel());
    descLabel.setFont(Font.font("Segoe UI", 9));
    descLabel.setStyle("-fx-text-fill: #888888;");
    descLabel.setWrapText(true);
    descLabel.setMaxWidth(450);
    
    Label typeLabel = new Label(track.getTypeLabel());
    typeLabel.setFont(Font.font("Segoe UI", 8));
    typeLabel.setStyle("-fx-text-fill: #666666;");
    
    info.getChildren().addAll(nameLabel, descLabel, typeLabel);
    HBox.setHgrow(info, Priority.ALWAYS);
    
    // Add button
    Button addBtn = new Button("Add");
    addBtn.setStyle("-fx-background-color: #0078d4; -fx-text-fill: white; -fx-font-size: 10px;");
    addBtn.setFocusTraversable(false); // Prevent focus change from scrolling
    addBtn.setOnAction(e -> {
      e.consume(); // Consume event to prevent propagation
      addTrack(track, addBtn);
    });
    
    row.getChildren().addAll(info, addBtn);
    
    // Hover effect - use a simple opacity change to avoid layout shifts
    final String defaultStyle = "-fx-background-color: #2b2b2b; -fx-background-radius: 4;";
    final String hoverStyle = "-fx-background-color: #3c3c3c; -fx-background-radius: 4;";
    row.setOnMouseEntered(e -> {
      if (row.getStyle().equals(defaultStyle)) {
        row.setStyle(hoverStyle);
      }
    });
    row.setOnMouseExited(e -> {
      if (row.getStyle().equals(hoverStyle)) {
        row.setStyle(defaultStyle);
      }
    });
    
    return row;
  }
  
  private void addTrack(UcscTrackInfo trackInfo, Button addBtn) {
    if (featureTracksCanvas == null) return;
    
    // Create appropriate track based on type
    Track track = createTrackFromInfo(trackInfo);
    if (track != null) {
      featureTracksCanvas.addTrack(track);
      featureTracksCanvas.setCollapsed(false);
      
      // Update button to show added
      addBtn.setText("✓ Added");
      addBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 10px;");
      addBtn.setDisable(true);
      
      System.out.println("Added UCSC track: " + trackInfo.shortLabel());
    }
  }
  
  private Track createTrackFromInfo(UcscTrackInfo trackInfo) {
    // For now, create a generic UCSC track
    // TODO: Create specific track types based on trackInfo.type()
    return new FeatureTrack(trackInfo.shortLabel(), "UCSC: " + trackInfo.trackName(),
        UcscApiClient::fetchConservation);
  }
  
  public void show() {
    dialog.show();
  }
}

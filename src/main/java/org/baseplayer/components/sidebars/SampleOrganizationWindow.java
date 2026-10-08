package org.baseplayer.components.sidebars;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.baseplayer.MainApp;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTag;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.SampleRegistry.DirectorySegmentMode;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.ui.theme.AppTheme;
import org.baseplayer.utils.DrawColors;

import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Tool window for creating named sample groups, assigning tracks, and applying
 * fixed {@link SampleTag}s. Use Sample Comparison for inheritance roles afterward.
 */
public final class SampleOrganizationWindow {

  private static SampleOrganizationWindow openInstance;

  private final Stage stage;
  private final SampleRegistry registry;
  private final ObservableList<SampleGroup> groupItems = FXCollections.observableArrayList();
  private final ObservableList<SampleTrack> memberItems = FXCollections.observableArrayList();
  private final ObservableList<SampleTrack> trackItems = FXCollections.observableArrayList();
  private final ListView<SampleGroup> groupList = new ListView<>(groupItems);
  private final ListView<SampleTrack> memberList = new ListView<>(memberItems);
  private final ListView<SampleTrack> trackList = new ListView<>(trackItems);

  {
    // Keep selected-group styling when focus moves to Members / All tracks.
    groupList.getStyleClass().add("sample-list");
    memberList.getStyleClass().add("sample-list");
    trackList.getStyleClass().add("sample-list");
  }
  private final TextField trackFilterField = new TextField();
  private final TextField renameField = new TextField();
  private final ColorPicker groupColorPicker = new ColorPicker();
  private final Label statusLabel = new Label();
  private final Map<SampleTag, ToggleButton> tagToggles = new EnumMap<>(SampleTag.class);
  private final Label tagTargetLabel = new Label();
  private final ChangeListener<Number> revisionListener =
      (obs, oldVal, newVal) -> onSampleGroupsRevision();

  private Integer pendingSelectGroupId;
  private boolean updatingGroupColorPicker;
  private boolean updatingTagChecks;
  private boolean syncingSelection;
  /** Which list last received a non-empty selection for tag sync. */
  private boolean preferMemberSelection;

  private SampleOrganizationWindow(Window owner) {
    registry = ServiceRegistry.getInstance().getSampleRegistry();
    // Independent stage (no initOwner) so Linux/GTK keeps minimize & maximize.
    stage = new Stage(StageStyle.DECORATED);
    stage.initModality(Modality.NONE);
    stage.setResizable(true);
    stage.setTitle("Sample Groups & Tags");
    stage.setMinWidth(780);
    stage.setMinHeight(480);

    Window resolved = owner != null ? owner : MainApp.stage;
    if (resolved != null) {
      resolved.showingProperty().addListener((obs, wasShowing, isShowing) -> {
        if (!isShowing && stage.isShowing()) {
          stage.hide();
        }
      });
    }

    BorderPane root = new BorderPane();
    root.setPadding(new Insets(12));
    root.getStyleClass().add("filter-panel");

    Label title = new Label("Sample Groups & Tags");
    title.getStyleClass().add("panel-title");
    Label hint = new Label(
        "Create named groups and assign samples. Tag tracks as Mother, Father, Child, "
            + "Parental, or Marker; use Sample Comparison for inheritance roles.");
    hint.getStyleClass().add("value-label");
    hint.setWrapText(true);
    VBox header = new VBox(4, title, hint);
    root.setTop(header);

    SplitPane split = new SplitPane(
        buildGroupsPane(), buildMembersPane(), buildTracksPane());
    split.setOrientation(Orientation.HORIZONTAL);
    split.setDividerPositions(0.28, 0.58);
    BorderPane.setMargin(split, new Insets(10, 0, 8, 0));
    root.setCenter(split);
    VBox bottom = new VBox(8, buildTagsPane(), buildFooter());
    root.setBottom(bottom);

    Scene scene = new Scene(root, 960, 620);
    applyTheme(scene);
    stage.setScene(scene);

    groupList.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
      refreshMembers();
      if (n != null) {
        renameField.setText(n.getName());
        updatingGroupColorPicker = true;
        groupColorPicker.setValue(n.getColor());
        updatingGroupColorPicker = false;
        groupColorPicker.setDisable(false);
      } else {
        renameField.clear();
        updatingGroupColorPicker = true;
        groupColorPicker.setValue(Color.web("#4db8ff"));
        updatingGroupColorPicker = false;
        groupColorPicker.setDisable(true);
      }
      updateTagCheckStates();
    });

    trackList.getSelectionModel().getSelectedItems().addListener(
        (ListChangeListener<SampleTrack>) c -> {
          if (!syncingSelection) {
            onTrackListSelectionChanged();
          }
        });
    memberList.getSelectionModel().getSelectedItems().addListener(
        (ListChangeListener<SampleTrack>) c -> {
          if (!syncingSelection) {
            onMemberListSelectionChanged();
          }
        });
    trackList.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY) {
        preferMemberSelection = false;
        Platform.runLater(this::updateTagCheckStates);
      }
    });
    memberList.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY) {
        preferMemberSelection = true;
        // Keep the active group visibly selected while working in Members.
        ensureGroupSelectionPreserved();
        Platform.runLater(this::updateTagCheckStates);
      }
    });

    registry.sampleGroupsRevisionProperty().addListener(revisionListener);
    stage.setOnHidden(e -> {
      registry.sampleGroupsRevisionProperty().removeListener(revisionListener);
      if (openInstance == this) {
        openInstance = null;
      }
    });

    refreshAll(null);
  }

  public static void show(Window owner) {
    if (openInstance != null && openInstance.stage.isShowing()) {
      if (openInstance.stage.isIconified()) {
        openInstance.stage.setIconified(false);
      }
      openInstance.stage.toFront();
      openInstance.stage.requestFocus();
      openInstance.refreshAll(openInstance.preserveSelection());
      return;
    }
    openInstance = new SampleOrganizationWindow(owner);
    openInstance.stage.show();
  }

  private static void applyTheme(Scene scene) {
    AppTheme.setDark(MainApp.darkMode);
    scene.getStylesheets().clear();
    if (MainApp.darkMode) {
      scene.getStylesheets().add(MainApp.getResource("theme-dark.css").toExternalForm());
    } else {
      scene.getStylesheets().add(MainApp.getResource("theme-light.css").toExternalForm());
    }
    scene.getStylesheets().add(MainApp.getResource("application.css").toExternalForm());
  }

  private VBox buildGroupsPane() {
    Label heading = sectionLabel("Groups");

    groupList.setCellFactory(list -> new ListCell<>() {
      @Override
      protected void updateItem(SampleGroup group, boolean empty) {
        super.updateItem(group, empty);
        if (empty || group == null) {
          setText(null);
          setGraphic(null);
          return;
        }
        Rectangle swatch = new Rectangle(10, 10, group.getColor());
        swatch.setArcWidth(2);
        swatch.setArcHeight(2);
        int count = registry.countTracksInGroup(group.getId());
        setText(group.getName() + " (" + count + ")");
        setGraphic(swatch);
      }
    });

    renameField.setPromptText("Selected group name");
    renameField.getStyleClass().add("filter-field");
    renameField.setOnAction(e -> commitRename());
    renameField.focusedProperty().addListener((obs, was, is) -> {
      if (was && !is) {
        commitRename();
      }
    });

    groupColorPicker.setPrefWidth(120);
    groupColorPicker.setDisable(true);
    groupColorPicker.valueProperty().addListener((obs, o, n) -> {
      if (updatingGroupColorPicker || n == null) {
        return;
      }
      SampleGroup selected = selectedGroup();
      if (selected == null) {
        return;
      }
      registry.setGroupColor(selected.getId(), n);
      statusLabel.setText("Updated color for \"" + selected.getName() + "\".");
      refreshAll(selected.getId());
    });

    Button newGroup = secondaryButton("New group");
    newGroup.setOnAction(e -> createEmptyGroup());
    Button deleteGroup = secondaryButton("Delete");
    deleteGroup.setOnAction(e -> deleteSelectedGroup());
    Button fromFolders = secondaryButton("Create from parent folders");
    fromFolders.setOnAction(e -> createFromParentFolders());

    HBox renameRow = new HBox(8, renameField, groupColorPicker);
    HBox.setHgrow(renameField, Priority.ALWAYS);
    renameRow.setAlignment(Pos.CENTER_LEFT);

    HBox row1 = new HBox(8, newGroup, deleteGroup);
    VBox pane = new VBox(8, heading, groupList, renameRow, row1, fromFolders);
    VBox.setVgrow(groupList, Priority.ALWAYS);
    pane.setPadding(new Insets(0, 6, 0, 0));
    return pane;
  }

  private VBox buildMembersPane() {
    Label heading = sectionLabel("Members");
    memberList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    memberList.setCellFactory(list -> trackCell());

    Button remove = secondaryButton("Remove from group");
    remove.setOnAction(e -> removeSelectedMembers());

    VBox pane = new VBox(8, heading, memberList, remove);
    VBox.setVgrow(memberList, Priority.ALWAYS);
    pane.setPadding(new Insets(0, 6, 0, 6));
    return pane;
  }

  private VBox buildTracksPane() {
    Label heading = sectionLabel("All tracks");
    trackFilterField.setPromptText("Filter by name or path…");
    trackFilterField.getStyleClass().add("filter-field");
    trackFilterField.textProperty().addListener((obs, o, n) -> refreshTrackList());

    trackList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    trackList.setCellFactory(list -> trackCell());

    Button add = secondaryButton("Add to selected group");
    add.setOnAction(e -> addSelectedTracks());

    VBox pane = new VBox(8, heading, trackFilterField, trackList, add);
    VBox.setVgrow(trackList, Priority.ALWAYS);
    pane.setPadding(new Insets(0, 0, 0, 6));
    return pane;
  }

  private VBox buildTagsPane() {
    Label tagsHeading = sectionLabel("Tags");
    tagTargetLabel.getStyleClass().add("value-label");
    tagTargetLabel.setText("Select a sample to edit tags");

    HBox tagRow = new HBox(8);
    tagRow.setAlignment(Pos.CENTER_LEFT);
    for (SampleTag tag : SampleTag.values()) {
      tagRow.getChildren().add(tagToggle(tag));
    }
    Button clearTags = secondaryButton("Clear tags");
    clearTags.setOnAction(e -> clearSelectedTrackTags());
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox actions = new HBox(8, tagRow, spacer, clearTags);
    actions.setAlignment(Pos.CENTER_LEFT);

    VBox pane = new VBox(4, tagsHeading, tagTargetLabel, actions);
    pane.setPadding(new Insets(4, 0, 0, 0));
    return pane;
  }

  private ToggleButton tagToggle(SampleTag tag) {
    ToggleButton toggle = new ToggleButton(tag.displayName());
    toggle.setFocusTraversable(false);
    toggle.setTooltip(new Tooltip(tag.description()));
    styleTagToggle(toggle, tag, false);
    tagToggles.put(tag, toggle);
    toggle.setOnAction(e -> {
      if (updatingTagChecks) {
        return;
      }
      applyTagToSelectedTracks(tag, toggle.isSelected());
      styleTagToggle(toggle, tag, toggle.isSelected());
    });
    return toggle;
  }

  private static void styleTagToggle(ToggleButton toggle, SampleTag tag, boolean on) {
    Circle swatch = new Circle(5, tag.color());
    toggle.setGraphic(swatch);
    toggle.setStyle(
        "-fx-background-color: " + (on ? tag.toCssHex() : "#2a2a2a") + ";"
            + "-fx-text-fill: " + (on ? "#111111" : "#dddddd") + ";"
            + "-fx-font-size: 12px; -fx-font-weight: bold;"
            + "-fx-padding: 4 12 4 10; -fx-background-radius: 14;"
            + "-fx-border-color: " + tag.toCssHex() + "; -fx-border-width: 2;"
            + "-fx-border-radius: 14; -fx-cursor: hand;");
  }

  private HBox buildFooter() {
    statusLabel.getStyleClass().add("value-label");
    statusLabel.setWrapText(true);
    statusLabel.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(statusLabel, Priority.ALWAYS);
    Button close = secondaryButton("Close");
    close.setOnAction(e -> stage.close());
    HBox footer = new HBox(12, statusLabel, close);
    footer.setAlignment(Pos.CENTER_LEFT);
    return footer;
  }

  private void refreshAll(Integer selectGroupId) {
    pendingSelectGroupId = selectGroupId;
    refreshGroups();
    refreshTrackList();
    refreshMembers();
    updateTagCheckStates();
    refreshTrackCells();
  }

  private Integer preserveSelection() {
    SampleGroup selected = selectedGroup();
    return selected != null ? selected.getId() : null;
  }

  /** Re-assert group list selection so highlight stays after focus moves to Members. */
  private void ensureGroupSelectionPreserved() {
    SampleGroup selected = selectedGroup();
    if (selected == null && !groupItems.isEmpty()) {
      groupList.getSelectionModel().select(0);
      return;
    }
    if (selected != null) {
      int index = groupItems.indexOf(selected);
      if (index >= 0 && groupList.getSelectionModel().getSelectedIndex() != index) {
        groupList.getSelectionModel().select(index);
      }
      // Nudge cell refresh so :selected style paints while unfocused.
      groupList.refresh();
    }
  }

  private SampleGroup selectedGroup() {
    return groupList.getSelectionModel().getSelectedItem();
  }

  private void refreshGroups() {
    Integer keepId = pendingSelectGroupId != null
        ? pendingSelectGroupId
        : preserveSelection();
    pendingSelectGroupId = null;

    groupItems.setAll(registry.getSampleGroups());
    if (keepId != null) {
      for (int i = 0; i < groupItems.size(); i++) {
        if (groupItems.get(i).getId() == keepId) {
          groupList.getSelectionModel().select(i);
          return;
        }
      }
    }
    if (!groupItems.isEmpty() && groupList.getSelectionModel().getSelectedItem() == null) {
      groupList.getSelectionModel().select(0);
    }
  }

  private void refreshMembers() {
    List<SampleTrack> keep =
        new ArrayList<>(memberList.getSelectionModel().getSelectedItems());
    SampleGroup selected = selectedGroup();
    memberItems.clear();
    if (selected != null) {
      memberItems.addAll(registry.getTracksInGroup(selected.getId()));
    }
    syncingSelection = true;
    try {
      memberList.getSelectionModel().clearSelection();
      for (SampleTrack track : keep) {
        int index = memberItems.indexOf(track);
        if (index >= 0) {
          memberList.getSelectionModel().select(index);
        }
      }
    } finally {
      syncingSelection = false;
    }
  }

  private void refreshTrackList() {
    String filter = trackFilterField.getText() == null
        ? ""
        : trackFilterField.getText().trim().toLowerCase(Locale.ROOT);
    // Don't restore All-tracks selection while the user is working in Members.
    List<SampleTrack> selected = preferMemberSelection
        ? List.of()
        : new ArrayList<>(trackList.getSelectionModel().getSelectedItems());
    trackItems.clear();
    for (SampleTrack track : registry.getSampleTracks()) {
      if (track == null) {
        continue;
      }
      if (!filter.isEmpty()) {
        String name = track.getDisplayName() != null
            ? track.getDisplayName().toLowerCase(Locale.ROOT) : "";
        Path path = SampleRegistry.primaryPathOf(track);
        String pathText = path != null ? path.toString().toLowerCase(Locale.ROOT) : "";
        if (!name.contains(filter) && !pathText.contains(filter)) {
          continue;
        }
      }
      trackItems.add(track);
    }
    syncingSelection = true;
    try {
      trackList.getSelectionModel().clearSelection();
      for (SampleTrack track : selected) {
        int index = trackItems.indexOf(track);
        if (index >= 0) {
          trackList.getSelectionModel().select(index);
        }
      }
    } finally {
      syncingSelection = false;
    }
    updateTagCheckStates();
  }

  private void onTrackListSelectionChanged() {
    if (!trackList.getSelectionModel().getSelectedItems().isEmpty()) {
      preferMemberSelection = false;
      clearOtherListSelection(memberList);
    }
    updateTagCheckStates();
  }

  private void onMemberListSelectionChanged() {
    if (!memberList.getSelectionModel().getSelectedItems().isEmpty()) {
      preferMemberSelection = true;
      clearOtherListSelection(trackList);
    }
    updateTagCheckStates();
  }

  private void clearOtherListSelection(ListView<SampleTrack> other) {
    if (syncingSelection || other.getSelectionModel().getSelectedItems().isEmpty()) {
      return;
    }
    syncingSelection = true;
    try {
      other.getSelectionModel().clearSelection();
    } finally {
      syncingSelection = false;
    }
  }

  private void updateTagCheckStates() {
    List<SampleTrack> selected = taggingTargets();
    boolean hasSelection = !selected.isEmpty();
    if (!hasSelection) {
      tagTargetLabel.setText("Select a sample to edit tags");
    } else if (selected.size() == 1) {
      tagTargetLabel.setText("Tags for “" + selected.get(0).getDisplayName() + "”");
    } else {
      tagTargetLabel.setText("Tags for " + selected.size() + " selected samples");
    }

    updatingTagChecks = true;
    try {
      for (Map.Entry<SampleTag, ToggleButton> entry : tagToggles.entrySet()) {
        SampleTag tag = entry.getKey();
        ToggleButton toggle = entry.getValue();
        toggle.setDisable(!hasSelection);
        if (!hasSelection) {
          toggle.setSelected(false);
          styleTagToggle(toggle, tag, false);
          continue;
        }
        // Single sample: exact tags. Multi: on only if every selected sample has it.
        boolean on;
        if (selected.size() == 1) {
          on = selected.get(0).hasTag(tag);
        } else {
          on = true;
          for (SampleTrack track : selected) {
            if (!track.hasTag(tag)) {
              on = false;
              break;
            }
          }
        }
        toggle.setSelected(on);
        styleTagToggle(toggle, tag, on);
      }
    } finally {
      updatingTagChecks = false;
    }
  }

  /**
   * Tracks to tag: the list the user last selected in (Members vs All tracks).
   */
  private List<SampleTrack> taggingTargets() {
    List<SampleTrack> fromTracks = selectedTracks();
    List<SampleTrack> fromMembers =
        new ArrayList<>(memberList.getSelectionModel().getSelectedItems());
    if (preferMemberSelection) {
      return !fromMembers.isEmpty() ? fromMembers : fromTracks;
    }
    return !fromTracks.isEmpty() ? fromTracks : fromMembers;
  }

  private List<SampleTrack> selectedTracks() {
    return new ArrayList<>(trackList.getSelectionModel().getSelectedItems());
  }

  private void applyTagToSelectedTracks(SampleTag tag, boolean wantOn) {
    List<SampleTrack> tracks = taggingTargets();
    if (tracks.isEmpty()) {
      statusLabel.setText("Select one or more tracks in All tracks or Members to tag.");
      updateTagCheckStates();
      return;
    }
    // Keep current list selection; only refresh cell graphics / tag toggles.
    boolean wasPreferMembers = preferMemberSelection;
    if (wantOn) {
      registry.addTagToTracks(tracks, tag);
      statusLabel.setText(
          "Added tag " + tag.displayName() + " to " + tracks.size() + " track(s).");
    } else {
      for (SampleTrack track : tracks) {
        if (track.hasTag(tag)) {
          registry.toggleTrackTag(track, tag);
        }
      }
      statusLabel.setText(
          "Removed tag " + tag.displayName() + " from " + tracks.size() + " track(s).");
    }
    preferMemberSelection = wasPreferMembers;
    refreshTrackCells();
    updateTagCheckStates();
  }

  private void clearSelectedTrackTags() {
    List<SampleTrack> tracks = taggingTargets();
    if (tracks.isEmpty()) {
      statusLabel.setText("Select one or more tracks to clear tags.");
      return;
    }
    boolean wasPreferMembers = preferMemberSelection;
    registry.clearTagsFromTracks(tracks);
    statusLabel.setText("Cleared tags from " + tracks.size() + " track(s).");
    preferMemberSelection = wasPreferMembers;
    refreshTrackCells();
    updateTagCheckStates();
  }

  private void refreshTrackCells() {
    trackList.refresh();
    memberList.refresh();
  }

  /** Soft refresh when tags/groups change — keep the selected sample(s). */
  private void onSampleGroupsRevision() {
    Integer groupId = preserveSelection();
    boolean wasPreferMembers = preferMemberSelection;
    List<SampleTrack> memberKeep =
        new ArrayList<>(memberList.getSelectionModel().getSelectedItems());
    List<SampleTrack> trackKeep =
        new ArrayList<>(trackList.getSelectionModel().getSelectedItems());
    refreshAll(groupId);
    preferMemberSelection = wasPreferMembers;
    restoreListSelection(memberList, memberItems, memberKeep);
    if (!wasPreferMembers) {
      restoreListSelection(trackList, trackItems, trackKeep);
    }
    updateTagCheckStates();
  }

  private void restoreListSelection(
      ListView<SampleTrack> list,
      ObservableList<SampleTrack> items,
      List<SampleTrack> keep) {
    if (keep == null || keep.isEmpty()) {
      return;
    }
    syncingSelection = true;
    try {
      for (SampleTrack track : keep) {
        int index = items.indexOf(track);
        if (index >= 0) {
          list.getSelectionModel().select(index);
        }
      }
    } finally {
      syncingSelection = false;
    }
  }

  private void commitRename() {
    SampleGroup selected = selectedGroup();
    if (selected == null) {
      return;
    }
    String name = renameField.getText() == null ? "" : renameField.getText().trim();
    if (name.isEmpty() || name.equals(selected.getName())) {
      renameField.setText(selected.getName());
      return;
    }
    int id = selected.getId();
    registry.renameSampleGroup(id, name);
    statusLabel.setText("Renamed group to \"" + name + "\".");
    refreshAll(id);
  }

  private void createEmptyGroup() {
    Stage dialog = new Stage(StageStyle.UTILITY);
    dialog.initModality(Modality.WINDOW_MODAL);
    dialog.initOwner(stage);
    dialog.setTitle("New group");

    TextField nameField = new TextField(registry.suggestNextGroupName());
    nameField.getStyleClass().add("filter-field");
    ColorPicker colorPicker = new ColorPicker(
        DrawColors.SAMPLE_GROUP_COLORS[
            registry.getSampleGroups().size() % DrawColors.SAMPLE_GROUP_COLORS.length]);
    colorPicker.setPrefWidth(140);

    final boolean[] created = {false};
    Button cancel = secondaryButton("Cancel");
    cancel.setCancelButton(true);
    cancel.setOnAction(e -> dialog.close());
    Button create = secondaryButton("Create");
    create.setDefaultButton(true);
    create.setOnAction(e -> {
      created[0] = true;
      dialog.close();
    });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(8, spacer, cancel, create);
    Label nameLabel = new Label("Name");
    nameLabel.getStyleClass().add("section-header");
    Label colorLabel = new Label("Color");
    colorLabel.getStyleClass().add("section-header");
    VBox root = new VBox(10,
        new Label("Create group") {{ getStyleClass().add("panel-title"); }},
        new VBox(2, nameLabel, nameField),
        new VBox(2, colorLabel, colorPicker),
        buttons);
    root.setPadding(new Insets(14));
    root.getStyleClass().add("filter-panel");

    Scene scene = new Scene(root, 340, 200);
    applyTheme(scene);
    dialog.setScene(scene);
    dialog.setOnShown(e -> {
      nameField.requestFocus();
      nameField.selectAll();
    });
    dialog.showAndWait();
    if (!created[0]) {
      return;
    }
    String name = nameField.getText() == null ? "" : nameField.getText().trim();
    Color color = colorPicker.getValue() != null
        ? colorPicker.getValue()
        : Color.web("#4db8ff");
    SampleGroup group = registry.createSampleGroup(name, color);
    statusLabel.setText("Created group \"" + group.getName() + "\".");
    refreshAll(group.getId());
  }

  private void deleteSelectedGroup() {
    SampleGroup selected = selectedGroup();
    if (selected == null) {
      statusLabel.setText("Select a group to delete.");
      return;
    }
    String name = selected.getName();
    registry.removeSampleGroup(selected.getId());
    statusLabel.setText("Deleted group \"" + name + "\".");
    refreshAll(null);
  }

  private void createFromParentFolders() {
    int touched = registry.createGroupsFromDirectory(DirectorySegmentMode.PARENT_FOLDER, null);
    statusLabel.setText(
        touched == 0
            ? "No groups created (no usable sample paths)."
            : "Created/updated " + touched + " group(s) from parent folders.");
    refreshAll(preserveSelection());
  }

  private void addSelectedTracks() {
    SampleGroup selected = selectedGroup();
    if (selected == null) {
      statusLabel.setText("Select a group first.");
      return;
    }
    List<SampleTrack> tracks = selectedTracks();
    if (tracks.isEmpty()) {
      statusLabel.setText("Select one or more tracks to add.");
      return;
    }
    registry.assignTracksToGroup(tracks, selected.getId());
    statusLabel.setText("Added " + tracks.size() + " track(s) to \"" + selected.getName() + "\".");
    refreshAll(selected.getId());
  }

  private void removeSelectedMembers() {
    SampleGroup selected = selectedGroup();
    if (selected == null) {
      statusLabel.setText("Select a group first.");
      return;
    }
    List<SampleTrack> members = new ArrayList<>(memberList.getSelectionModel().getSelectedItems());
    if (members.isEmpty()) {
      statusLabel.setText("Select members to remove.");
      return;
    }
    for (SampleTrack track : members) {
      registry.removeTrackFromGroup(track, selected.getId());
    }
    statusLabel.setText("Removed " + members.size() + " track(s) from \"" + selected.getName() + "\".");
    refreshAll(selected.getId());
  }

  private static Label sectionLabel(String text) {
    Label label = new Label(text);
    label.getStyleClass().add("section-header");
    return label;
  }

  private static Button secondaryButton(String text) {
    Button button = new Button(text);
    button.getStyleClass().add("secondary-button");
    return button;
  }

  private static ListCell<SampleTrack> trackCell() {
    return new ListCell<>() {
      @Override
      protected void updateItem(SampleTrack track, boolean empty) {
        super.updateItem(track, empty);
        if (empty || track == null) {
          setText(null);
          setGraphic(null);
          return;
        }
        setText(null);

        HBox tags = new HBox(4);
        tags.setAlignment(Pos.CENTER_LEFT);
        for (SampleTag tag : track.getTags()) {
          Label chip = new Label(tag.shortLabel());
          chip.setMinSize(18, 18);
          chip.setAlignment(Pos.CENTER);
          chip.setStyle(
              "-fx-background-color: " + tag.toCssHex() + ";"
                  + "-fx-text-fill: #111111;"
                  + "-fx-font-size: 10px; -fx-font-weight: bold;"
                  + "-fx-padding: 1 5 1 5; -fx-background-radius: 9;");
          chip.setTooltip(new Tooltip(tag.displayName()));
          tags.getChildren().add(chip);
        }

        Label name = new Label(track.getDisplayName());
        name.setStyle("-fx-font-size: 12px;");
        HBox.setHgrow(name, Priority.ALWAYS);

        HBox row = new HBox(8, tags, name);
        row.setAlignment(Pos.CENTER_LEFT);
        setGraphic(row);
      }
    };
  }
}

package org.baseplayer.components.sidebars;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.baseplayer.MainApp;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.SampleRegistry.BulkSubgroupResult;
import org.baseplayer.services.SampleRegistry.DirectorySegmentMode;
import org.baseplayer.services.SampleRegistry.ParentalNameMatchMode;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.ui.theme.AppTheme;
import org.baseplayer.utils.DrawColors;

import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Tool window for creating named sample groups (and one-level subgroups)
 * and assigning tracks. Use Sample Comparison to assign roles afterward.
 */
public final class SampleOrganizationWindow {

  private static SampleOrganizationWindow openInstance;

  private final Stage stage;
  private final SampleRegistry registry;
  private final ObservableList<SampleTrack> memberItems = FXCollections.observableArrayList();
  private final ObservableList<SampleTrack> trackItems = FXCollections.observableArrayList();
  private final TreeView<SampleGroup> groupTree = new TreeView<>();
  private final ListView<SampleTrack> memberList = new ListView<>(memberItems);
  private final ListView<SampleTrack> trackList = new ListView<>(trackItems);
  private final TextField trackFilterField = new TextField();
  private final TextField renameField = new TextField();
  private final Button newSubgroupButton = secondaryButton("New subgroup");
  private final Label statusLabel = new Label();
  private final ChangeListener<Number> revisionListener =
      (obs, oldVal, newVal) -> refreshAll(preserveSelection());

  private Integer pendingSelectGroupId;

  private SampleOrganizationWindow(Window owner) {
    registry = ServiceRegistry.getInstance().getSampleRegistry();
    stage = new Stage(StageStyle.DECORATED);
    stage.initModality(Modality.NONE);
    Window resolved = owner != null ? owner : MainApp.stage;
    if (resolved != null) {
      stage.initOwner(resolved);
    }
    stage.setTitle("Sample Groups");
    stage.setMinWidth(780);
    stage.setMinHeight(480);

    BorderPane root = new BorderPane();
    root.setPadding(new Insets(12));
    root.getStyleClass().add("filter-panel");

    Label title = new Label("Sample Groups");
    title.getStyleClass().add("panel-title");
    Label hint = new Label(
        "Create named groups and subgroups, then add samples. "
            + "Assign roles in Sample Comparison.");
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
    root.setBottom(buildFooter());

    Scene scene = new Scene(root, 920, 560);
    applyTheme(scene);
    stage.setScene(scene);

    groupTree.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
      refreshMembers();
      updateSubgroupButton();
      SampleGroup group = n != null ? n.getValue() : null;
      if (group != null) {
        renameField.setText(group.getName());
      } else {
        renameField.clear();
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

    groupTree.setShowRoot(false);
    groupTree.setCellFactory(tree -> new TreeCell<>() {
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

    Button newGroup = secondaryButton("New group");
    newGroup.setOnAction(e -> createEmptyGroup(SampleGroup.NO_PARENT));
    newSubgroupButton.setOnAction(e -> {
      SampleGroup selected = selectedGroup();
      if (selected != null && selected.isRoot()) {
        createEmptyGroup(selected.getId());
      }
    });
    newSubgroupButton.setDisable(true);

    Button deleteGroup = secondaryButton("Delete");
    deleteGroup.setOnAction(e -> deleteSelectedGroup());
    Button fromFolders = secondaryButton("Create from parent folders");
    fromFolders.setOnAction(e -> createFromParentFolders());
    Button addSubgroupAll = secondaryButton("Add subgroup to all groups…");
    addSubgroupAll.setOnAction(e -> showAddSubgroupToAllDialog());

    HBox row1 = new HBox(8, newGroup, newSubgroupButton);
    HBox row2 = new HBox(8, deleteGroup);
    VBox pane = new VBox(8, heading, groupTree, renameField, row1, row2, fromFolders, addSubgroupAll);
    VBox.setVgrow(groupTree, Priority.ALWAYS);
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
    updateSubgroupButton();
  }

  private Integer preserveSelection() {
    SampleGroup selected = selectedGroup();
    return selected != null ? selected.getId() : null;
  }

  private SampleGroup selectedGroup() {
    TreeItem<SampleGroup> item = groupTree.getSelectionModel().getSelectedItem();
    return item != null ? item.getValue() : null;
  }

  private void updateSubgroupButton() {
    SampleGroup selected = selectedGroup();
    newSubgroupButton.setDisable(selected == null || !selected.isRoot());
  }

  private void refreshGroups() {
    Integer keepId = pendingSelectGroupId != null
        ? pendingSelectGroupId
        : preserveSelection();
    pendingSelectGroupId = null;

    TreeItem<SampleGroup> root = new TreeItem<>(null);
    root.setExpanded(true);
    TreeItem<SampleGroup> toSelect = null;
    for (SampleGroup group : registry.getRootGroups()) {
      TreeItem<SampleGroup> groupItem = new TreeItem<>(group);
      groupItem.setExpanded(true);
      if (keepId != null && group.getId() == keepId) {
        toSelect = groupItem;
      }
      for (SampleGroup child : registry.getChildGroups(group.getId())) {
        TreeItem<SampleGroup> childItem = new TreeItem<>(child);
        groupItem.getChildren().add(childItem);
        if (keepId != null && child.getId() == keepId) {
          toSelect = childItem;
        }
      }
      root.getChildren().add(groupItem);
    }
    groupTree.setRoot(root);
    if (toSelect != null) {
      groupTree.getSelectionModel().select(toSelect);
    } else if (!root.getChildren().isEmpty()
        && groupTree.getSelectionModel().getSelectedItem() == null) {
      groupTree.getSelectionModel().select(root.getChildren().get(0));
    }
  }

  private void refreshMembers() {
    SampleGroup selected = selectedGroup();
    memberItems.clear();
    if (selected != null) {
      memberItems.addAll(registry.getTracksInGroup(selected.getId()));
    }
  }

  private void refreshTrackList() {
    String filter = trackFilterField.getText() == null
        ? ""
        : trackFilterField.getText().trim().toLowerCase(Locale.ROOT);
    List<SampleTrack> selected = new ArrayList<>(trackList.getSelectionModel().getSelectedItems());
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
    for (SampleTrack track : selected) {
      int index = trackItems.indexOf(track);
      if (index >= 0) {
        trackList.getSelectionModel().select(index);
      }
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

  private void createEmptyGroup(int parentGroupId) {
    boolean subgroup = parentGroupId >= 0;
    Stage dialog = new Stage(StageStyle.UTILITY);
    dialog.initModality(Modality.WINDOW_MODAL);
    dialog.initOwner(stage);
    dialog.setTitle(subgroup ? "New subgroup" : "New group");

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
    String heading = subgroup ? "Create subgroup" : "Create group";
    VBox root = new VBox(10,
        new Label(heading) {{ getStyleClass().add("panel-title"); }},
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
    SampleGroup group = registry.createSampleGroup(name, color, parentGroupId);
    if (group == null) {
      statusLabel.setText("Could not create subgroup (select a top-level group).");
      return;
    }
    statusLabel.setText(
        (subgroup ? "Created subgroup \"" : "Created group \"") + group.getName() + "\".");
    refreshAll(group.getId());
  }

  private void deleteSelectedGroup() {
    SampleGroup selected = selectedGroup();
    if (selected == null) {
      statusLabel.setText("Select a group to delete.");
      return;
    }
    String name = selected.getName();
    boolean hadChildren = selected.isRoot() && !registry.getChildGroups(selected.getId()).isEmpty();
    registry.removeSampleGroup(selected.getId());
    statusLabel.setText(
        hadChildren
            ? "Deleted group \"" + name + "\" and its subgroups."
            : "Deleted group \"" + name + "\".");
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

  private void showAddSubgroupToAllDialog() {
    if (registry.getRootGroups().isEmpty()) {
      statusLabel.setText("Create top-level groups first (e.g. from parent folders).");
      return;
    }

    Stage dialog = new Stage(StageStyle.UTILITY);
    dialog.initModality(Modality.WINDOW_MODAL);
    dialog.initOwner(stage);
    dialog.setTitle("Add subgroup to all groups");

    TextField nameField = new TextField("Parental");
    nameField.getStyleClass().add("filter-field");
    TextField patternField = new TextField("BG");
    patternField.getStyleClass().add("filter-field");

    ComboBox<ParentalNameMatchMode> matchModeBox = new ComboBox<>();
    matchModeBox.getItems().setAll(ParentalNameMatchMode.values());
    matchModeBox.setValue(ParentalNameMatchMode.CONTAINS);
    matchModeBox.setCellFactory(list -> matchModeCell());
    matchModeBox.setButtonCell(matchModeCell());

    Label hint = new Label(
        "Creates the subgroup under every top-level group and moves matching members into it.");
    hint.getStyleClass().add("value-label");
    hint.setWrapText(true);
    hint.setMaxWidth(360);

    final boolean[] applied = {false};
    Button cancel = secondaryButton("Cancel");
    cancel.setCancelButton(true);
    cancel.setOnAction(e -> dialog.close());
    Button apply = secondaryButton("Apply");
    apply.setDefaultButton(true);
    apply.setOnAction(e -> {
      applied[0] = true;
      dialog.close();
    });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(8, spacer, cancel, apply);

    Label nameLabel = new Label("Subgroup name");
    nameLabel.getStyleClass().add("section-header");
    Label patternLabel = new Label("Name pattern");
    patternLabel.getStyleClass().add("section-header");
    Label modeLabel = new Label("Match");
    modeLabel.getStyleClass().add("section-header");

    VBox root = new VBox(10,
        new Label("Add subgroup to all groups") {{ getStyleClass().add("panel-title"); }},
        hint,
        new VBox(2, nameLabel, nameField),
        new VBox(2, patternLabel, patternField),
        new VBox(2, modeLabel, matchModeBox),
        buttons);
    root.setPadding(new Insets(14));
    root.getStyleClass().add("filter-panel");

    Scene scene = new Scene(root, 400, 280);
    applyTheme(scene);
    dialog.setScene(scene);
    dialog.setOnShown(e -> {
      nameField.requestFocus();
      nameField.selectAll();
    });
    dialog.showAndWait();
    if (!applied[0]) {
      return;
    }

    BulkSubgroupResult result = registry.addSubgroupToAllRoots(
        nameField.getText(),
        patternField.getText(),
        matchModeBox.getValue());
    String subgroupLabel = nameField.getText() == null || nameField.getText().isBlank()
        ? "Parental"
        : nameField.getText().trim();
    if (result.subgroupsCreated() == 0 && result.tracksMoved() == 0) {
      statusLabel.setText("No subgroups created and no tracks moved (check pattern / groups).");
    } else {
      statusLabel.setText(
          "Created " + result.subgroupsCreated() + " " + subgroupLabel
              + " subgroup(s); moved " + result.tracksMoved() + " track(s).");
    }
    refreshAll(preserveSelection());
  }

  private static ListCell<ParentalNameMatchMode> matchModeCell() {
    return new ListCell<>() {
      @Override
      protected void updateItem(ParentalNameMatchMode item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
          setText(null);
          return;
        }
        setText(switch (item) {
          case CONTAINS -> "Contains";
          case STARTS_WITH -> "Starts with";
          case REGEX -> "Regex";
        });
      }
    };
  }

  private void addSelectedTracks() {
    SampleGroup selected = selectedGroup();
    if (selected == null) {
      statusLabel.setText("Select a group first.");
      return;
    }
    List<SampleTrack> tracks = new ArrayList<>(trackList.getSelectionModel().getSelectedItems());
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
          return;
        }
        Path path = SampleRegistry.primaryPathOf(track);
        String pathText = path != null ? path.getFileName().toString() : "";
        setText(pathText.isBlank()
            ? track.getDisplayName()
            : track.getDisplayName() + "  ·  " + pathText);
      }
    };
  }
}

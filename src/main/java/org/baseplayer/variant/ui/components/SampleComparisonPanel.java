package org.baseplayer.variant.ui.components;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.VariantFilter;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Sample Comparison tab: shared-sample range, gene/window mode, and group roles.
 */
public class SampleComparisonPanel {

  public record Nodes(
      IntegerRangeSlider sharedSampleRangeSlider,
      CheckBox geneLevelComparisonCheckBox,
      TextField comparisonWindowField,
      Label commonVariantsHelpLabel,
      VBox comparisonGroupsContainer,
      Label groupComparisonSummaryLabel,
      Button refreshComparisonGroupsButton,
      RadioButton presentMatchAllRadio,
      RadioButton presentMatchAnyRadio) {}

  private Nodes nodes;
  private Runnable onDebouncedChange;
  private Runnable onImmediateChange;
  private BooleanSupplier isSuppressing;

  private ToggleGroup presentMatchModeGroup;
  private final Map<Integer, VariantFilter.GroupRole> comparisonGroupRoles = new HashMap<>();

  public void install(
      Nodes nodes,
      Runnable onDebouncedChange,
      Runnable onImmediateChange,
      BooleanSupplier isSuppressing) {
    this.nodes = nodes;
    this.onDebouncedChange = onDebouncedChange;
    this.onImmediateChange = onImmediateChange;
    this.isSuppressing = isSuppressing;
    setupBindings();
    if (nodes.refreshComparisonGroupsButton() != null) {
      nodes.refreshComparisonGroupsButton().setOnAction(e -> refreshGroups());
    }
    refreshGroups();
  }

  public void writeTo(VariantFilter filter) {
    if (nodes == null) return;

    if (nodes.sharedSampleRangeSlider() != null) {
      filter.setMinSharedSamples(nodes.sharedSampleRangeSlider().getLowValue());
      int high = nodes.sharedSampleRangeSlider().getHighValue();
      int total = nodes.sharedSampleRangeSlider().getAbsoluteMax();
      filter.setMaxSharedSamples(high >= total ? Integer.MAX_VALUE : high);
    }

    filter.setGeneLevel(
        nodes.geneLevelComparisonCheckBox() != null && nodes.geneLevelComparisonCheckBox().isSelected());
    filter.setComparisonWindowBp(readComparisonWindowBp());

    Map<Integer, VariantFilter.GroupRole> roles = new HashMap<>();
    for (Map.Entry<Integer, VariantFilter.GroupRole> entry : comparisonGroupRoles.entrySet()) {
      if (entry.getValue() != null && entry.getValue() != VariantFilter.GroupRole.IGNORE) {
        roles.put(entry.getKey(), entry.getValue());
      }
    }
    filter.setGroupRoles(roles);
    filter.setGroupTrackIndices(resolveGroupTrackIndices(roles.keySet()));
    filter.setPresentMatchMode(selectedPresentMatchMode());
  }

  public void loadFrom(VariantFilter filter) {
    if (nodes == null || filter == null) return;

    if (nodes.sharedSampleRangeSlider() != null) {
      syncSharedSampleRangeBounds();
      int maxShare = filter.getMaxSharedSamples();
      if (maxShare == Integer.MAX_VALUE) {
        maxShare = nodes.sharedSampleRangeSlider().getAbsoluteMax();
      }
      nodes.sharedSampleRangeSlider().setRange(filter.getMinSharedSamples(), maxShare);
    }

    if (nodes.geneLevelComparisonCheckBox() != null) {
      nodes.geneLevelComparisonCheckBox().setSelected(filter.isGeneLevel());
      updateComparisonWindowEnabled();
      updateCommonVariantsHelpLabel();
    }
    if (nodes.comparisonWindowField() != null) {
      nodes.comparisonWindowField().setText(Integer.toString(Math.max(0, filter.getComparisonWindowBp())));
    }

    comparisonGroupRoles.clear();
    comparisonGroupRoles.putAll(filter.getGroupRoles());
    applyPresentMatchModeToRadios(filter.getPresentMatchMode());
    refreshGroups();
  }

  public void reset() {
    if (nodes == null) return;
    if (nodes.sharedSampleRangeSlider() != null) {
      syncSharedSampleRangeBounds();
      nodes.sharedSampleRangeSlider().resetToFullRange();
    }
    if (nodes.geneLevelComparisonCheckBox() != null) {
      nodes.geneLevelComparisonCheckBox().setSelected(false);
      updateComparisonWindowEnabled();
      updateCommonVariantsHelpLabel();
    }
    if (nodes.comparisonWindowField() != null) {
      nodes.comparisonWindowField().setText("0");
    }
    comparisonGroupRoles.clear();
    applyPresentMatchModeToRadios(VariantFilter.PresentMatchMode.ALL);
    refreshGroups();
  }

  public void syncSharedSampleRangeBounds() {
    if (nodes == null || nodes.sharedSampleRangeSlider() == null) return;
    int sampleCount = Math.max(1, ServiceRegistry.getInstance().getSampleRegistry().getSampleTracks().size());
    nodes.sharedSampleRangeSlider().setAbsoluteMax(sampleCount);
  }

  public void refreshGroups() {
    if (nodes == null || nodes.comparisonGroupsContainer() == null) return;
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    nodes.comparisonGroupsContainer().getChildren().clear();

    Set<Integer> validIds = new HashSet<>();
    validIds.add(VariantFilter.UNGROUPED_COHORT_ID);
    for (SampleGroup group : registry.getSampleGroups()) {
      validIds.add(group.getId());
    }
    comparisonGroupRoles.keySet().removeIf(id -> !validIds.contains(id));

    int ungroupedCount = 0;
    for (SampleTrack track : registry.getSampleTracks()) {
      if (!track.hasGroup()) {
        ungroupedCount++;
      }
    }
    nodes.comparisonGroupsContainer().getChildren().add(
        buildComparisonGroupRow(
            VariantFilter.UNGROUPED_COHORT_ID,
            "Ungrouped",
            ungroupedCount,
            Color.web("#888888")));

    for (SampleGroup group : registry.getSampleGroups()) {
      nodes.comparisonGroupsContainer().getChildren().add(
          buildComparisonGroupRow(
              group.getId(),
              group.getName(),
              registry.countTracksInGroup(group.getId()),
              group.getColor()));
    }

    if (registry.getSampleGroups().isEmpty() && ungroupedCount == 0) {
      Label empty = new Label("No samples loaded yet.");
      empty.getStyleClass().add("subsection-label");
      nodes.comparisonGroupsContainer().getChildren().add(empty);
    }

    updateGroupComparisonSummaryLabel();
  }

  public void setControlsLocked(boolean locked) {
    if (nodes == null) return;
    if (nodes.sharedSampleRangeSlider() != null) nodes.sharedSampleRangeSlider().setDisable(locked);
    if (nodes.geneLevelComparisonCheckBox() != null) nodes.geneLevelComparisonCheckBox().setDisable(locked);
    if (nodes.comparisonWindowField() != null) {
      nodes.comparisonWindowField().setDisable(locked
          || (nodes.geneLevelComparisonCheckBox() != null && nodes.geneLevelComparisonCheckBox().isSelected()));
    }
    if (nodes.refreshComparisonGroupsButton() != null) nodes.refreshComparisonGroupsButton().setDisable(locked);
    if (nodes.presentMatchAllRadio() != null) nodes.presentMatchAllRadio().setDisable(locked);
    if (nodes.presentMatchAnyRadio() != null) nodes.presentMatchAnyRadio().setDisable(locked);
    if (nodes.comparisonGroupsContainer() != null) nodes.comparisonGroupsContainer().setDisable(locked);
  }

  private void setupBindings() {
    presentMatchModeGroup = new ToggleGroup();
    if (nodes.presentMatchAllRadio() != null) {
      nodes.presentMatchAllRadio().setToggleGroup(presentMatchModeGroup);
      nodes.presentMatchAnyRadio().setToggleGroup(presentMatchModeGroup);
      presentMatchModeGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
        updateGroupComparisonSummaryLabel();
        if (!isSuppressing()) {
          fireImmediate();
        }
      });
    }

    if (nodes.geneLevelComparisonCheckBox() != null) {
      nodes.geneLevelComparisonCheckBox().selectedProperty().addListener((obs, oldVal, newVal) -> {
        updateComparisonWindowEnabled();
        updateCommonVariantsHelpLabel();
        if (!isSuppressing()) {
          fireImmediate();
        }
      });
      updateComparisonWindowEnabled();
      updateCommonVariantsHelpLabel();
    }

    if (nodes.comparisonWindowField() != null) {
      nodes.comparisonWindowField().textProperty().addListener((obs, oldVal, newVal) -> {
        updateCommonVariantsHelpLabel();
        if (isSuppressing()) return;
        fireDebounced();
      });
    }

    if (nodes.sharedSampleRangeSlider() == null) return;

    ChangeListener<Number> rangeListener = (obs, oldVal, newVal) -> {
      if (isSuppressing()) return;
      fireDebounced();
    };
    nodes.sharedSampleRangeSlider().lowValueProperty().addListener(rangeListener);
    nodes.sharedSampleRangeSlider().highValueProperty().addListener(rangeListener);
  }

  private void updateCommonVariantsHelpLabel() {
    if (nodes.commonVariantsHelpLabel() == null) return;
    boolean geneLevel = nodes.geneLevelComparisonCheckBox() != null
        && nodes.geneLevelComparisonCheckBox().isSelected();
    int windowBp = readComparisonWindowBp();
    if (nodes.sharedSampleRangeSlider() != null) {
      if (geneLevel) {
        nodes.sharedSampleRangeSlider().setSummaryUnit("samples (gene)");
      } else if (windowBp > 0) {
        nodes.sharedSampleRangeSlider().setSummaryUnit("samples (window)");
      } else {
        nodes.sharedSampleRangeSlider().setSummaryUnit("samples");
      }
    }
    if (geneLevel) {
      nodes.commonVariantsHelpLabel().setText(
          "Keep genes mutated in at least this many samples (left) and at most this many (right). All variants in a matching gene are shown.");
    } else if (windowBp > 0) {
      nodes.commonVariantsHelpLabel().setText(
          "Counts samples with a soft-matching call within " + windowBp
              + " bp (same type family; indels/SVs may match across types). "
              + "Use the right thumb to drop hotspot / fragile / repeat clusters shared by too many samples.");
    } else {
      nodes.commonVariantsHelpLabel().setText(
          "Keep variants shared by at least this many samples (left) and at most this many (right). Use this to drop private variants or those shared by everyone.");
    }
  }

  private void updateComparisonWindowEnabled() {
    if (nodes.comparisonWindowField() == null) return;
    boolean geneLevel = nodes.geneLevelComparisonCheckBox() != null
        && nodes.geneLevelComparisonCheckBox().isSelected();
    nodes.comparisonWindowField().setDisable(geneLevel);
  }

  private int readComparisonWindowBp() {
    if (nodes.comparisonWindowField() == null) return 0;
    try {
      return Math.max(0, Integer.parseInt(nodes.comparisonWindowField().getText().trim()));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private HBox buildComparisonGroupRow(int groupId, String name, int memberCount, Color color) {
    HBox row = new HBox(8);
    row.setAlignment(Pos.CENTER_LEFT);

    Label swatch = new Label("  ");
    String hex = String.format("#%02x%02x%02x",
        (int) Math.round(color.getRed() * 255),
        (int) Math.round(color.getGreen() * 255),
        (int) Math.round(color.getBlue() * 255));
    swatch.setStyle(
        "-fx-background-color: " + hex + "; -fx-background-radius: 2;"
            + "-fx-min-width: 12; -fx-min-height: 12;");

    Label nameLabel = new Label(name + " (" + memberCount + ")");
    nameLabel.getStyleClass().add("subsection-label");
    nameLabel.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(nameLabel, Priority.ALWAYS);

    ComboBox<String> roleBox = new ComboBox<>();
    roleBox.getItems().addAll("Ignore", "Must be present", "Must be absent");
    VariantFilter.GroupRole currentRole =
        comparisonGroupRoles.getOrDefault(groupId, VariantFilter.GroupRole.IGNORE);
    roleBox.setValue(roleLabel(currentRole));
    roleBox.setPrefWidth(140);
    roleBox.valueProperty().addListener((obs, oldVal, newVal) -> {
      VariantFilter.GroupRole role = roleFromLabel(newVal);
      if (role == VariantFilter.GroupRole.IGNORE) {
        comparisonGroupRoles.remove(groupId);
      } else {
        comparisonGroupRoles.put(groupId, role);
      }
      updateGroupComparisonSummaryLabel();
      if (!isSuppressing()) {
        fireImmediate();
      }
    });

    row.getChildren().addAll(swatch, nameLabel, roleBox);
    return row;
  }

  private static String roleLabel(VariantFilter.GroupRole role) {
    if (role == null) return "Ignore";
    return switch (role) {
      case PRESENT -> "Must be present";
      case ABSENT -> "Must be absent";
      default -> "Ignore";
    };
  }

  private static VariantFilter.GroupRole roleFromLabel(String label) {
    if ("Must be present".equals(label)) return VariantFilter.GroupRole.PRESENT;
    if ("Must be absent".equals(label)) return VariantFilter.GroupRole.ABSENT;
    return VariantFilter.GroupRole.IGNORE;
  }

  private void updateGroupComparisonSummaryLabel() {
    if (nodes.groupComparisonSummaryLabel() == null) return;
    List<String> present = new ArrayList<>();
    List<String> absent = new ArrayList<>();
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    for (Map.Entry<Integer, VariantFilter.GroupRole> entry : comparisonGroupRoles.entrySet()) {
      String label = groupDisplayName(registry, entry.getKey());
      if (entry.getValue() == VariantFilter.GroupRole.PRESENT) {
        present.add(label);
      } else if (entry.getValue() == VariantFilter.GroupRole.ABSENT) {
        absent.add(label);
      }
    }
    if (present.isEmpty() && absent.isEmpty()) {
      nodes.groupComparisonSummaryLabel().setText("No group constraints");
      return;
    }
    StringBuilder sb = new StringBuilder();
    if (!present.isEmpty()) {
      String joiner = selectedPresentMatchMode() == VariantFilter.PresentMatchMode.ANY ? " or " : " and ";
      sb.append("Present: ").append(String.join(joiner, present));
    }
    if (!absent.isEmpty()) {
      if (sb.length() > 0) sb.append("  ·  ");
      sb.append("Absent: ").append(String.join(" and ", absent));
    }
    nodes.groupComparisonSummaryLabel().setText(sb.toString());
  }

  private static String groupDisplayName(SampleRegistry registry, int groupId) {
    if (groupId == VariantFilter.UNGROUPED_COHORT_ID) return "Ungrouped";
    SampleGroup group = registry.getSampleGroup(groupId);
    return group != null ? group.getName() : ("Group " + groupId);
  }

  private VariantFilter.PresentMatchMode selectedPresentMatchMode() {
    if (nodes.presentMatchAnyRadio() != null && nodes.presentMatchAnyRadio().isSelected()) {
      return VariantFilter.PresentMatchMode.ANY;
    }
    return VariantFilter.PresentMatchMode.ALL;
  }

  private void applyPresentMatchModeToRadios(VariantFilter.PresentMatchMode mode) {
    if (nodes.presentMatchAllRadio() == null) return;
    if (mode == VariantFilter.PresentMatchMode.ANY) {
      nodes.presentMatchAnyRadio().setSelected(true);
    } else {
      nodes.presentMatchAllRadio().setSelected(true);
    }
  }

  private Map<Integer, Set<Integer>> resolveGroupTrackIndices(Set<Integer> groupIds) {
    Map<Integer, Set<Integer>> byGroup = new HashMap<>();
    if (groupIds == null || groupIds.isEmpty()) return byGroup;
    for (Integer id : groupIds) {
      byGroup.put(id, new HashSet<>());
    }
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    List<SampleTrack> tracks = registry.getSampleTracks();
    for (int i = 0; i < tracks.size(); i++) {
      SampleTrack track = tracks.get(i);
      int cohortId = track.hasGroup() ? track.getGroupId() : VariantFilter.UNGROUPED_COHORT_ID;
      Set<Integer> indices = byGroup.get(cohortId);
      if (indices != null) {
        indices.add(i);
      }
    }
    return byGroup;
  }

  private boolean isSuppressing() {
    return isSuppressing != null && isSuppressing.getAsBoolean();
  }

  private void fireDebounced() {
    if (onDebouncedChange != null) onDebouncedChange.run();
  }

  private void fireImmediate() {
    if (onImmediateChange != null) onImmediateChange.run();
  }
}

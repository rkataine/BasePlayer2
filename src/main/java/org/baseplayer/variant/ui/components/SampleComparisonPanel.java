package org.baseplayer.variant.ui.components;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTag;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.ui.controls.AppComboBox;
import org.baseplayer.variant.VariantFilter;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Pair;

/**
 * Sample Comparison tab: shared-sample range, gene/window mode, and fixed tag roles.
 */
public class SampleComparisonPanel {

  public record Nodes(
      IntegerRangeSlider sharedSampleRangeSlider,
      CheckBox geneLevelComparisonCheckBox,
      TextField comparisonWindowField,
      Button comparisonWindowApplyButton,
      Label commonVariantsHelpLabel,
      VBox comparisonGroupsContainer,
      Label groupComparisonSummaryLabel,
      Button refreshComparisonGroupsButton,
      RadioButton presentMatchAllRadio,
      RadioButton presentMatchAnyRadio,
      Button calculateLohButton) {}

  private Nodes nodes;
  private Runnable onDebouncedChange;
  private Runnable onImmediateChange;
  private BooleanSupplier isSuppressing;

  private ToggleGroup presentMatchModeGroup;
  private final Map<SampleTag, VariantFilter.GroupRole> comparisonTagRoles =
      new EnumMap<>(SampleTag.class);
  /** Window bp last committed via Apply / load — not live field text. */
  private int appliedComparisonWindowBp = 0;

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
    // Only the applied window drives clustering — typing alone does not.
    filter.setComparisonWindowBp(appliedComparisonWindowBp);

    Map<SampleTag, VariantFilter.GroupRole> roles = new EnumMap<>(SampleTag.class);
    for (Map.Entry<SampleTag, VariantFilter.GroupRole> entry : comparisonTagRoles.entrySet()) {
      if (entry.getValue() != null && entry.getValue() != VariantFilter.GroupRole.IGNORE) {
        roles.put(entry.getKey(), entry.getValue());
      }
    }
    filter.setTagRoles(roles);
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
    }
    appliedComparisonWindowBp = Math.max(0, filter.getComparisonWindowBp());
    if (nodes.comparisonWindowField() != null) {
      nodes.comparisonWindowField().setText(Integer.toString(appliedComparisonWindowBp));
    }
    updateComparisonWindowEnabled();
    updateCommonVariantsHelpLabel();

    comparisonTagRoles.clear();
    if (filter.getTagRoles() != null) {
      comparisonTagRoles.putAll(filter.getTagRoles());
    }
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
    }
    appliedComparisonWindowBp = 0;
    if (nodes.comparisonWindowField() != null) {
      nodes.comparisonWindowField().setText("0");
    }
    updateComparisonWindowEnabled();
    updateCommonVariantsHelpLabel();
    comparisonTagRoles.clear();
    applyPresentMatchModeToRadios(VariantFilter.PresentMatchMode.ALL);
    refreshGroups();
  }

  public void syncSharedSampleRangeBounds() {
    if (nodes == null || nodes.sharedSampleRangeSlider() == null) return;
    nodes.sharedSampleRangeSlider().setAbsoluteMax(countTracksWithVisibleVcf());
  }

  /**
   * Upper bound for the common-variant slider: tracks that currently have at least
   * one UI-visible VCF file (same set that can contribute to shared-sample counts).
   */
  private static int countTracksWithVisibleVcf() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    List<SampleTrack> tracks = snapshotTracks(registry);
    int count = 0;
    for (SampleTrack track : tracks) {
      if (track == null) {
        continue;
      }
      for (Sample sample : track.getSamples()) {
        if (sample.getDataType() == Sample.DataType.VCF && sample.visible) {
          count++;
          break;
        }
      }
    }
    // Fallback when no VCF file entries exist yet (legacy / mid-load).
    if (count == 0) {
      count = tracks.size();
    }
    return Math.max(1, count);
  }

  public void refreshGroups() {
    if (nodes == null || nodes.comparisonGroupsContainer() == null) return;
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    nodes.comparisonGroupsContainer().getChildren().clear();

    nodes.comparisonGroupsContainer().getChildren().add(buildPresetRow());

    boolean anyTagged = false;
    for (SampleTag tag : SampleTag.values()) {
      int count = countTracksWithTag(registry, tag);
      if (count > 0) {
        anyTagged = true;
      }
      nodes.comparisonGroupsContainer().getChildren().add(
          buildComparisonTagRow(tag, count));
    }

    if (!anyTagged && registry.getSampleTracks().isEmpty()) {
      Label empty = new Label("No samples loaded yet.");
      empty.getStyleClass().add("subsection-label");
      nodes.comparisonGroupsContainer().getChildren().add(empty);
    }

    updateGroupComparisonSummaryLabel();
    updateCalculateLohButton();
  }

  public void setControlsLocked(boolean locked) {
    if (nodes == null) return;
    if (nodes.sharedSampleRangeSlider() != null) nodes.sharedSampleRangeSlider().setDisable(locked);
    if (nodes.geneLevelComparisonCheckBox() != null) nodes.geneLevelComparisonCheckBox().setDisable(locked);
    boolean windowLocked = locked
        || (nodes.geneLevelComparisonCheckBox() != null && nodes.geneLevelComparisonCheckBox().isSelected());
    if (nodes.comparisonWindowField() != null) {
      nodes.comparisonWindowField().setDisable(windowLocked);
    }
    if (nodes.comparisonWindowApplyButton() != null) {
      nodes.comparisonWindowApplyButton().setDisable(windowLocked);
    }
    if (nodes.refreshComparisonGroupsButton() != null) nodes.refreshComparisonGroupsButton().setDisable(locked);
    if (nodes.presentMatchAllRadio() != null) nodes.presentMatchAllRadio().setDisable(locked);
    if (nodes.presentMatchAnyRadio() != null) nodes.presentMatchAnyRadio().setDisable(locked);
    if (nodes.comparisonGroupsContainer() != null) nodes.comparisonGroupsContainer().setDisable(locked);
    updateCalculateLohButton(locked);
  }

  /** Enable Calculate LOH when Parental/Marker HET + Child HOM roles are set. */
  public void updateCalculateLohButton() {
    updateCalculateLohButton(false);
  }

  private void updateCalculateLohButton(boolean controlsLocked) {
    if (nodes == null || nodes.calculateLohButton() == null) {
      return;
    }
    VariantFilter probe = new VariantFilter();
    writeTo(probe);
    boolean lohReady = probe.isLohMode();
    nodes.calculateLohButton().setDisable(controlsLocked || !lohReady);
    nodes.calculateLohButton().setTooltip(new Tooltip(
        lohReady
            ? "Build LOH AA/BB region spans from parental/marker hets (on demand)"
            : "Set Parental/Marker = heterozygous and Child = homozygous, then Calculate"));
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

    // Window field: typing does not rebuild clusters — only Apply commits.
    if (nodes.comparisonWindowApplyButton() != null) {
      nodes.comparisonWindowApplyButton().setOnAction(e -> applyComparisonWindow());
    }
    if (nodes.comparisonWindowField() != null) {
      nodes.comparisonWindowField().setOnAction(e -> applyComparisonWindow());
    }

    if (nodes.sharedSampleRangeSlider() == null) return;

    ChangeListener<Number> rangeListener = (obs, oldVal, newVal) -> {
      if (isSuppressing()) return;
      fireDebounced();
    };
    nodes.sharedSampleRangeSlider().lowValueProperty().addListener(rangeListener);
    nodes.sharedSampleRangeSlider().highValueProperty().addListener(rangeListener);
  }

  private void applyComparisonWindow() {
    if (isSuppressing()) {
      return;
    }
    appliedComparisonWindowBp = readComparisonWindowFieldBp();
    updateCommonVariantsHelpLabel();
    fireImmediate();
  }

  private void updateCommonVariantsHelpLabel() {
    if (nodes.commonVariantsHelpLabel() == null) return;
    boolean geneLevel = nodes.geneLevelComparisonCheckBox() != null
        && nodes.geneLevelComparisonCheckBox().isSelected();
    int windowBp = appliedComparisonWindowBp;
    if (nodes.sharedSampleRangeSlider() != null) {
      if (geneLevel) {
        nodes.sharedSampleRangeSlider().setSummaryUnit("samples (gene)");
      } else if (windowBp > 0) {
        nodes.sharedSampleRangeSlider().setSummaryUnit("samples (cluster)");
      } else {
        nodes.sharedSampleRangeSlider().setSummaryUnit("samples");
      }
    }
    if (geneLevel) {
      nodes.commonVariantsHelpLabel().setText("Count samples mutated anywhere in the gene.");
    } else if (windowBp > 0) {
      nodes.commonVariantsHelpLabel().setText(
          "Clusters within " + windowBp + " bp; singletons hidden. Slider = samples in cluster.");
    } else {
      nodes.commonVariantsHelpLabel().setText("Shared-sample range (min–max).");
    }
  }

  private void updateComparisonWindowEnabled() {
    boolean geneLevel = nodes.geneLevelComparisonCheckBox() != null
        && nodes.geneLevelComparisonCheckBox().isSelected();
    if (nodes.comparisonWindowField() != null) {
      nodes.comparisonWindowField().setDisable(geneLevel);
    }
    if (nodes.comparisonWindowApplyButton() != null) {
      nodes.comparisonWindowApplyButton().setDisable(geneLevel);
    }
  }

  private int readComparisonWindowFieldBp() {
    if (nodes.comparisonWindowField() == null) return 0;
    try {
      return Math.max(0, Integer.parseInt(nodes.comparisonWindowField().getText().trim()));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private HBox buildPresetRow() {
    HBox row = new HBox(8);
    row.setAlignment(Pos.CENTER_LEFT);

    Label label = new Label("Preset");
    label.getStyleClass().add("subsection-label");

    ComboBox<String> presetBox = AppComboBox.create(
        "—",
        "De novo",
        "Maternal",
        "Paternal",
        "LOH / markers");
    presetBox.setValue("—");
    presetBox.setPrefWidth(140);
    presetBox.valueProperty().addListener((obs, oldVal, newVal) -> {
      if (newVal == null || "—".equals(newVal) || isSuppressing()) {
        return;
      }
      applyRolePreset(newVal);
      presetBox.setValue("—");
    });

    row.getChildren().addAll(label, presetBox);
    return row;
  }

  private void applyRolePreset(String preset) {
    comparisonTagRoles.clear();
    switch (preset) {
      case "De novo" -> {
        comparisonTagRoles.put(SampleTag.CHILD, VariantFilter.GroupRole.PRESENT);
        comparisonTagRoles.put(SampleTag.MOTHER, VariantFilter.GroupRole.ABSENT);
        comparisonTagRoles.put(SampleTag.FATHER, VariantFilter.GroupRole.ABSENT);
      }
      case "Maternal" -> {
        comparisonTagRoles.put(SampleTag.CHILD, VariantFilter.GroupRole.PRESENT);
        comparisonTagRoles.put(SampleTag.MOTHER, VariantFilter.GroupRole.PRESENT);
        comparisonTagRoles.put(SampleTag.FATHER, VariantFilter.GroupRole.ABSENT);
      }
      case "Paternal" -> {
        comparisonTagRoles.put(SampleTag.CHILD, VariantFilter.GroupRole.PRESENT);
        comparisonTagRoles.put(SampleTag.FATHER, VariantFilter.GroupRole.PRESENT);
        comparisonTagRoles.put(SampleTag.MOTHER, VariantFilter.GroupRole.ABSENT);
      }
      case "LOH / markers" -> {
        // Parental het + Child hom is enough for LOH. Marker is optional and only
        // applied when the project actually has Marker-tagged tracks — an empty
        // Marker cohort would otherwise fail PresentMatchMode.ALL and hide all sites.
        comparisonTagRoles.put(SampleTag.PARENTAL, VariantFilter.GroupRole.HETEROZYGOUS);
        comparisonTagRoles.put(SampleTag.CHILD, VariantFilter.GroupRole.HOMOZYGOUS);
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        if (countTracksWithTag(registry, SampleTag.MARKER) > 0) {
          comparisonTagRoles.put(SampleTag.MARKER, VariantFilter.GroupRole.HETEROZYGOUS);
        }
      }
      default -> { /* no-op */ }
    }
    refreshGroups();
    if (!isSuppressing()) {
      fireImmediate();
    }
  }

  private HBox buildComparisonTagRow(SampleTag tag, int memberCount) {
    HBox row = new HBox(8);
    row.setAlignment(Pos.CENTER_LEFT);

    Color color = tag.color();
    Label swatch = new Label("  ");
    String hex = String.format("#%02x%02x%02x",
        (int) Math.round(color.getRed() * 255),
        (int) Math.round(color.getGreen() * 255),
        (int) Math.round(color.getBlue() * 255));
    swatch.setStyle(
        "-fx-background-color: " + hex + "; -fx-background-radius: 2;"
            + "-fx-min-width: 12; -fx-min-height: 12;");

    Label nameLabel = new Label(tag.displayName() + " (" + memberCount + ")");
    nameLabel.getStyleClass().add("subsection-label");
    nameLabel.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(nameLabel, Priority.ALWAYS);

    ComboBox<String> roleBox = AppComboBox.create(
        "Ignore",
        "Must be present",
        "Must be absent",
        "Must be heterozygous",
        "Must be homozygous");
    VariantFilter.GroupRole currentRole =
        comparisonTagRoles.getOrDefault(tag, VariantFilter.GroupRole.IGNORE);
    roleBox.setValue(roleLabel(currentRole));
    roleBox.setPrefWidth(180);
    roleBox.valueProperty().addListener((obs, oldVal, newVal) -> {
      VariantFilter.GroupRole role = roleFromLabel(newVal);
      if (role == VariantFilter.GroupRole.IGNORE) {
        comparisonTagRoles.remove(tag);
      } else {
        comparisonTagRoles.put(tag, role);
      }
      updateGroupComparisonSummaryLabel();
      updateCalculateLohButton();
      if (!isSuppressing()) {
        fireImmediate();
      }
    });

    row.getChildren().addAll(swatch, nameLabel, roleBox);
    return row;
  }

  private static int countTracksWithTag(SampleRegistry registry, SampleTag tag) {
    int count = 0;
    for (SampleTrack track : snapshotTracks(registry)) {
      if (track != null && track.hasTag(tag)) {
        count++;
      }
    }
    return count;
  }

  /** Copy away from the live ObservableList so New Project clears cannot CME mid-refresh. */
  private static List<SampleTrack> snapshotTracks(SampleRegistry registry) {
    try {
      return new ArrayList<>(registry.getSampleTracks());
    } catch (ConcurrentModificationException e) {
      return new ArrayList<>(registry.getSampleTracks());
    }
  }

  private static String roleLabel(VariantFilter.GroupRole role) {
    if (role == null) return "Ignore";
    return switch (role) {
      case PRESENT -> "Must be present";
      case ABSENT -> "Must be absent";
      case HETEROZYGOUS -> "Must be heterozygous";
      case HOMOZYGOUS -> "Must be homozygous";
      case IGNORE -> "Ignore";
    };
  }

  private static VariantFilter.GroupRole roleFromLabel(String label) {
    if ("Must be present".equals(label)) return VariantFilter.GroupRole.PRESENT;
    if ("Must be absent".equals(label)) return VariantFilter.GroupRole.ABSENT;
    if ("Must be heterozygous".equals(label)
        || "Reference / parental / markers".equals(label)) {
      return VariantFilter.GroupRole.HETEROZYGOUS;
    }
    if ("Must be homozygous".equals(label)
        || "Must be homozygous (ALT)".equals(label)
        || "Must be homozygous (REF)".equals(label)) {
      return VariantFilter.GroupRole.HOMOZYGOUS;
    }
    return VariantFilter.GroupRole.IGNORE;
  }

  private void updateGroupComparisonSummaryLabel() {
    if (nodes.groupComparisonSummaryLabel() == null) return;
    List<String> present = new ArrayList<>();
    List<String> absent = new ArrayList<>();
    List<String> heterozygous = new ArrayList<>();
    List<String> homozygous = new ArrayList<>();
    for (Map.Entry<SampleTag, VariantFilter.GroupRole> entry : comparisonTagRoles.entrySet()) {
      String label = entry.getKey().displayName();
      VariantFilter.GroupRole role = entry.getValue();
      if (role == null) {
        continue;
      }
      switch (role) {
        case PRESENT -> present.add(label);
        case ABSENT -> absent.add(label);
        case HETEROZYGOUS -> heterozygous.add(label);
        case HOMOZYGOUS -> homozygous.add(label);
        case IGNORE -> { /* not shown in summary */ }
      }
    }
    if (present.isEmpty() && absent.isEmpty()
        && heterozygous.isEmpty() && homozygous.isEmpty()) {
      nodes.groupComparisonSummaryLabel().setText("No tag constraints");
      return;
    }
    boolean lohMode = !heterozygous.isEmpty() && !homozygous.isEmpty();
    StringBuilder sb = new StringBuilder();
    String presentJoiner =
        selectedPresentMatchMode() == VariantFilter.PresentMatchMode.ANY ? " or " : " and ";
    if (lohMode) {
      sb.append("LOH: het ").append(String.join(presentJoiner, heterozygous))
          .append(" → hom AA/BB ").append(String.join(presentJoiner, homozygous));
    } else {
      if (!heterozygous.isEmpty()) {
        sb.append("Het: ").append(String.join(presentJoiner, heterozygous));
      }
      if (!homozygous.isEmpty()) {
        if (sb.length() > 0) sb.append("  ·  ");
        sb.append("Hom ALT: ").append(String.join(presentJoiner, homozygous));
      }
    }
    if (!present.isEmpty()) {
      if (sb.length() > 0) sb.append("  ·  ");
      sb.append("Present: ").append(String.join(presentJoiner, present));
    }
    if (!absent.isEmpty()) {
      if (sb.length() > 0) sb.append("  ·  ");
      sb.append("Absent: ").append(String.join(" and ", absent));
    }
    nodes.groupComparisonSummaryLabel().setText(sb.toString());
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

  private boolean isSuppressing() {
    return isSuppressing != null && isSuppressing.getAsBoolean();
  }

  private void fireDebounced() {
    if (onDebouncedChange != null) onDebouncedChange.run();
  }

  private void fireImmediate() {
    if (onImmediateChange != null) onImmediateChange.run();
  }

  /**
   * Build a Sample Comparison UI tree programmatically (for the SV mode workspace).
   */
  public static Pair<javafx.scene.Node, Nodes> buildUi() {
    Label help = new Label("Shared-sample range (min–max).");
    help.getStyleClass().add("subsection-label");
    help.setWrapText(true);

    CheckBox geneLevel = new CheckBox("Gene level");
    geneLevel.getStyleClass().add("filter-checkbox");

    TextField windowField = new TextField("0");
    windowField.setPrefWidth(72);
    windowField.setMaxWidth(90);
    windowField.getStyleClass().add("filter-field");
    Button windowApply = new Button("Apply");
    windowApply.setMinWidth(64);
    windowApply.getStyleClass().add("secondary-button");

    IntegerRangeSlider rangeSlider = new IntegerRangeSlider();

    VBox left = new VBox(12);
    left.getStyleClass().add("filter-panel");
    left.setPadding(new Insets(16));
    Label commonTitle = new Label("Common variants");
    commonTitle.getStyleClass().add("section-header");
    Label windowLabel = new Label("Window (bp)");
    windowLabel.getStyleClass().add("subsection-label");
    windowLabel.setMinWidth(80);
    HBox windowRow = new HBox(8, windowLabel, windowField, windowApply);
    windowRow.setAlignment(Pos.CENTER_LEFT);
    left.getChildren().addAll(commonTitle, help, geneLevel, windowRow, rangeSlider);

    VBox groupsContainer = new VBox(6);
    VBox.setVgrow(groupsContainer, Priority.ALWAYS);
    Label summary = new Label("No tag constraints");
    summary.getStyleClass().add("value-label");
    summary.setWrapText(true);
    Button refresh = new Button("Refresh");
    refresh.setMinWidth(72);
    refresh.getStyleClass().add("secondary-button");

    RadioButton matchAll = new RadioButton("All (AND)");
    matchAll.getStyleClass().add("filter-radio");
    matchAll.setSelected(true);
    RadioButton matchAny = new RadioButton("Any (OR)");
    matchAny.getStyleClass().add("filter-radio");

    Button calculateLoh = new Button("Calculate LOH");
    calculateLoh.setMinWidth(110);
    calculateLoh.getStyleClass().add("secondary-button");
    calculateLoh.setDisable(true);
    Button apply = new Button("Apply");
    apply.setMinWidth(64);
    apply.getStyleClass().add("primary-button");

    VBox right = new VBox(12);
    right.getStyleClass().add("filter-panel");
    right.setPadding(new Insets(16));
    Label groupsTitle = new Label("Sample tags");
    groupsTitle.getStyleClass().add("section-header");
    Region groupsSpacer = new Region();
    HBox groupsHeader = new HBox(8, groupsTitle, groupsSpacer, refresh);
    HBox.setHgrow(groupsSpacer, Priority.ALWAYS);
    groupsHeader.setAlignment(Pos.CENTER_LEFT);
    Label presentTitle = new Label("Multi-tag match");
    presentTitle.getStyleClass().add("section-header");
    HBox presentRow = new HBox(16, matchAll, matchAny);
    presentRow.setAlignment(Pos.CENTER_LEFT);
    HBox applyRow = new HBox(12, calculateLoh, apply);
    applyRow.setAlignment(Pos.CENTER_RIGHT);
    right.getChildren().addAll(
        groupsHeader,
        groupsContainer,
        summary,
        presentTitle,
        presentRow,
        applyRow);

    ScrollPane leftScroll = new ScrollPane(left);
    leftScroll.setFitToWidth(true);
    leftScroll.getStyleClass().add("filter-scroll");
    leftScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    leftScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

    ScrollPane rightScroll = new ScrollPane(right);
    rightScroll.setFitToWidth(true);
    rightScroll.getStyleClass().add("filter-scroll");
    rightScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    rightScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

    SplitPane split = new SplitPane(leftScroll, rightScroll);
    split.setDividerPositions(0.5);
    split.getStyleClass().add("tab-content-split");

    Nodes nodes = new Nodes(
        rangeSlider,
        geneLevel,
        windowField,
        windowApply,
        help,
        groupsContainer,
        summary,
        refresh,
        matchAll,
        matchAny,
        calculateLoh);
    apply.setOnAction(e -> {
      // Wired by controller after install if needed; default no-op here.
    });
    return new Pair<>(split, nodes);
  }

  /** Optional: attach Apply button action after {@link #buildUi()}. */
  public static void wireApplyButton(javafx.scene.Node root, Runnable onApply) {
    if (root == null || onApply == null) {
      return;
    }
    // Right-pane primary Apply (not the window Apply secondary button).
    findButtonByStyleClass(root, "primary-button")
        .ifPresent(btn -> btn.setOnAction(e -> onApply.run()));
  }

  /** Optional: attach Calculate LOH button action after {@link #buildUi()}. */
  public static void wireCalculateLohButton(javafx.scene.Node root, Runnable onCalculate) {
    if (root == null || onCalculate == null) {
      return;
    }
    findButtonByText(root, "Calculate LOH")
        .ifPresent(btn -> btn.setOnAction(e -> onCalculate.run()));
  }

  private static java.util.Optional<Button> findButtonByText(javafx.scene.Node node, String text) {
    if (node instanceof Button button && text.equals(button.getText())) {
      return java.util.Optional.of(button);
    }
    if (node instanceof javafx.scene.Parent parent) {
      for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
        java.util.Optional<Button> found = findButtonByText(child, text);
        if (found.isPresent()) {
          return found;
        }
      }
    }
    return java.util.Optional.empty();
  }

  private static java.util.Optional<Button> findButtonByStyleClass(
      javafx.scene.Node node, String styleClass) {
    if (node instanceof Button button && button.getStyleClass().contains(styleClass)) {
      return java.util.Optional.of(button);
    }
    if (node instanceof javafx.scene.Parent parent) {
      for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
        java.util.Optional<Button> found = findButtonByStyleClass(child, styleClass);
        if (found.isPresent()) {
          return found;
        }
      }
    }
    return java.util.Optional.empty();
  }
}

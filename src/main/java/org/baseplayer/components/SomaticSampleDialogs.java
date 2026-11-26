package org.baseplayer.components;

import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.variant.VariantLoader;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Modal prompts for somatic 2-column VCF sample selection.
 */
public final class SomaticSampleDialogs {

  private static final String FIELD_STYLE =
      "-fx-background-color: #333; -fx-text-fill: #ddd; -fx-border-color: #555; -fx-font-size: 12px;"
          + "-fx-prompt-text-fill: #777777;";

  private SomaticSampleDialogs() {}

  /**
   * Ask whether two-column VCFs in this project should be treated as somatic
   * (tumor + matched normal). Primary = Yes (somatic), secondary = No (keep both).
   * Saves the tumor-name pattern from the dialog when confirmed Yes.
   */
  public static boolean askSomaticMode(
      Window owner, String fileName, String sample1, String sample2) {
    ProjectSessionState session = ProjectSessionState.get();
    AppDialog dialog = new AppDialog(owner, "Somatic VCF?");
    dialog.addTitle("Treat as somatic (tumor + normal)?");
    dialog.addMessage(
        "\"" + fileName + "\" has two sample columns:\n\n"
            + "  • " + sample1 + "\n"
            + "  • " + sample2 + "\n\n"
            + "For somatic Mutect2-style VCFs, only the tumor column becomes a sample track.");

    dialog.addHint(
        "How the tumor column is chosen (in order):\n"
            + "1. Your tumor-name pattern below (same * wildcards as sample filter)\n"
            + "2. Built-in name cues: tumor / somatic / _t_ / tk vs normal / nk / germline\n"
            + "3. First variant GT: the 0/0 column is treated as normal\n"
            + "4. If still unclear, you are asked to pick");

    TextField patternField = tumorPatternField(session.getSomaticTumorNamePattern());
    Label preview = AppDialog.hintLabel("");
    preview.setStyle(AppDialog.MESSAGE_STYLE);
    Runnable refreshPreview = () -> preview.setText(
        previewForSamples(sample1, sample2, patternField.getText()));
    patternField.textProperty().addListener((obs, o, n) -> refreshPreview.run());
    refreshPreview.run();

    VBox patternBox = new VBox(6,
        AppDialog.hintLabel("Tumor-name pattern (optional)"),
        patternField,
        preview);
    patternBox.setPadding(new Insets(4, 0, 0, 0));
    dialog.addContent(patternBox);

    dialog.addSecondaryButton("No, keep both");
    dialog.addPrimaryButton("Yes, somatic");
    AppDialog.focusWhenShown(dialog.stage, patternField, false);
    if (!dialog.showAndWaitConfirmed()) {
      return false;
    }
    session.setSomaticTumorNamePattern(patternField.getText());
    return true;
  }

  public record TumorPickResult(String tumorSample, boolean applyToRestOfBatch) {}

  /**
   * Ask which of two columns is the tumor. Returns null if cancelled
   * (secondary button) — caller leaves mapping unchanged.
   * Saves an updated tumor-name pattern when confirmed.
   */
  public static TumorPickResult askTumorSample(
      Window owner,
      String sample1,
      String sample2,
      String defaultTumor) {
    ProjectSessionState session = ProjectSessionState.get();
    AppDialog dialog = new AppDialog(owner, "Select tumor sample");
    dialog.addTitle("Which sample is the tumor?");
    dialog.addMessage(
        "Could not tell tumor from normal by name alone for this file. "
            + "Pick the tumor column, or refine the tumor-name pattern.");

    dialog.addHint(
        "Selection order: your pattern → built-in name cues (tumor/tk vs normal/nk) "
            + "→ first-variant 0/0 GT → this dialog.");

    TextField patternField = tumorPatternField(session.getSomaticTumorNamePattern());

    ToggleGroup group = new ToggleGroup();
    RadioButton r1 = tumorRadio(sample1);
    RadioButton r2 = tumorRadio(sample2);
    r1.setToggleGroup(group);
    r2.setToggleGroup(group);
    r1.setUserData(sample1);
    r2.setUserData(sample2);

    Label preview = AppDialog.hintLabel("");
    Runnable applyPatternToRadios = () -> {
      String pattern = patternField.getText();
      String fromPattern = VariantLoader.tumorFromCustomPattern(sample1, sample2, pattern);
      String fromNames = VariantLoader.guessTumorFromNames(sample1, sample2, pattern);
      String suggested = fromPattern != null ? fromPattern
          : fromNames != null ? fromNames
          : defaultTumor;
      if (suggested != null && suggested.equals(sample2)) {
        r2.setSelected(true);
      } else {
        r1.setSelected(true);
      }
      preview.setText(previewForSamples(sample1, sample2, pattern));
    };
    patternField.textProperty().addListener((obs, o, n) -> applyPatternToRadios.run());

    if (defaultTumor != null && defaultTumor.equals(sample2)) {
      r2.setSelected(true);
    } else {
      r1.setSelected(true);
    }
    applyPatternToRadios.run();

    ColoredToggle applyRest =
        new ColoredToggle("Use the same rule for other ambiguous files in this open");
    applyRest.setSelected(true);
    applyRest.setWrapText(true);

    VBox choices = new VBox(8,
        AppDialog.hintLabel("Tumor-name pattern (optional)"),
        patternField,
        preview,
        r1,
        r2,
        applyRest);
    choices.setPadding(new Insets(4, 0, 0, 0));
    dialog.addContent(choices);

    dialog.addSecondaryButton("Cancel");
    dialog.addPrimaryButton("Use tumor");
    AppDialog.focusWhenShown(dialog.stage, patternField, false);
    if (!dialog.showAndWaitConfirmed()) {
      return null;
    }
    session.setSomaticTumorNamePattern(patternField.getText());
    Object selected = group.getSelectedToggle() != null
        ? group.getSelectedToggle().getUserData()
        : null;
    if (!(selected instanceof String tumor) || tumor.isBlank()) {
      return null;
    }
    return new TumorPickResult(tumor, applyRest.isSelected());
  }

  private static TextField tumorPatternField(String initial) {
    TextField field = new TextField(initial != null ? initial : "");
    field.setPromptText("e.g. *tk* or *_T or tumor");
    field.setStyle(FIELD_STYLE);
    field.setTooltip(new Tooltip(
        "Match the tumor sample name. Use * as a wildcard (same as sample filter), "
            + "e.g. *tk*, *_tum_*, or a letter/token like T."));
    field.setMaxWidth(Double.MAX_VALUE);
    return field;
  }

  private static RadioButton tumorRadio(String sample) {
    RadioButton radio = new RadioButton(sample);
    radio.setWrapText(true);
    radio.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12px;");
    return radio;
  }

  private static String previewForSamples(String sample1, String sample2, String pattern) {
    String trimmed = pattern != null ? pattern.trim() : "";
    boolean m1 = VariantLoader.nameMatchesTumorPattern(sample1, trimmed);
    boolean m2 = VariantLoader.nameMatchesTumorPattern(sample2, trimmed);
    StringBuilder sb = new StringBuilder();
    if (!trimmed.isEmpty()) {
      sb.append("Pattern \"").append(trimmed).append("\": ");
      if (m1 && !m2) {
        sb.append(sample1).append(" matches → tumor");
      } else if (m2 && !m1) {
        sb.append(sample2).append(" matches → tumor");
      } else if (m1 && m2) {
        sb.append("both match (ambiguous)");
      } else {
        sb.append("no match on either column");
      }
      sb.append('\n');
    }
    String tumor = VariantLoader.guessTumorFromNames(sample1, sample2, trimmed);
    if (tumor != null) {
      String normal = tumor.equals(sample1) ? sample2 : sample1;
      String via = VariantLoader.tumorFromCustomPattern(sample1, sample2, trimmed) != null
          ? "your pattern"
          : "built-in name cues";
      sb.append("Would select tumor: ").append(tumor)
          .append(" (normal: ").append(normal).append(", via ").append(via).append(')');
    } else {
      sb.append("Names alone are ambiguous → first-variant GT, then pick dialog");
    }
    return sb.toString();
  }
}

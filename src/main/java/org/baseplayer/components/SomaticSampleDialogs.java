package org.baseplayer.components;

import java.util.Map;

import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Modal prompts for somatic 2-column VCF sample selection.
 */
public final class SomaticSampleDialogs {

  private SomaticSampleDialogs() {}

  /**
   * Ask whether two-column VCFs in this project should be treated as somatic
   * (tumor + matched normal). Primary = Yes (somatic), secondary = No (keep both).
   */
  public static boolean askSomaticMode(
      Window owner, String fileName, String sample1, String sample2) {
    AppDialog dialog = new AppDialog(owner, "Somatic VCF?");
    dialog.addTitle("Treat as somatic (tumor + normal)?");
    dialog.addMessage(
        "\"" + fileName + "\" has two sample columns:\n\n"
            + "  • " + sample1 + "\n"
            + "  • " + sample2 + "\n\n"
            + "For somatic Mutect2-style VCFs, only the tumor column should become a sample track. "
            + "Choose Yes to apply this for other two-column VCFs in this project.");
    dialog.addHint("You can still be asked to pick the tumor when names are ambiguous.");
    dialog.addSecondaryButton("No, keep both");
    dialog.addPrimaryButton("Yes, somatic");
    return dialog.showAndWaitConfirmed();
  }

  public record TumorPickResult(String tumorSample, boolean applyToRestOfBatch) {}

  /**
   * Ask which of two columns is the tumor. Returns null if cancelled
   * (secondary button) — caller leaves mapping unchanged.
   */
  public static TumorPickResult askTumorSample(
      Window owner,
      String sample1,
      String sample2,
      String defaultTumor,
      Map<String, Integer> callCounts) {
    AppDialog dialog = new AppDialog(owner, "Select tumor sample");
    dialog.addTitle("Which sample is the tumor?");
    dialog.addMessage(
        "Could not tell tumor from normal by name alone. "
            + "Pick the tumor column to keep as a sample track.");

    ToggleGroup group = new ToggleGroup();
    RadioButton r1 = new RadioButton(labelWithCalls(sample1, callCounts));
    RadioButton r2 = new RadioButton(labelWithCalls(sample2, callCounts));
    r1.setToggleGroup(group);
    r2.setToggleGroup(group);
    r1.setUserData(sample1);
    r2.setUserData(sample2);
    r1.setWrapText(true);
    r2.setWrapText(true);
    r1.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12px;");
    r2.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12px;");

    if (defaultTumor != null && defaultTumor.equals(sample2)) {
      r2.setSelected(true);
    } else {
      r1.setSelected(true);
    }

    CheckBox applyRest = new CheckBox("Use the same rule for other ambiguous files in this open");
    applyRest.setStyle("-fx-text-fill: #b0b0b0; -fx-font-size: 11px;");
    applyRest.setSelected(true);
    applyRest.setWrapText(true);

    VBox choices = new VBox(8, r1, r2, applyRest);
    choices.setPadding(new Insets(4, 0, 0, 0));
    dialog.addContent(choices);

    dialog.addSecondaryButton("Cancel");
    dialog.addPrimaryButton("Use tumor");
    if (!dialog.showAndWaitConfirmed()) {
      return null;
    }
    Object selected = group.getSelectedToggle() != null
        ? group.getSelectedToggle().getUserData()
        : null;
    if (!(selected instanceof String tumor) || tumor.isBlank()) {
      return null;
    }
    return new TumorPickResult(tumor, applyRest.isSelected());
  }

  private static String labelWithCalls(String sample, Map<String, Integer> callCounts) {
    if (callCounts == null || !callCounts.containsKey(sample)) {
      return sample;
    }
    int n = callCounts.getOrDefault(sample, 0);
    return sample + "  (" + n + " non-ref calls in probe)";
  }
}

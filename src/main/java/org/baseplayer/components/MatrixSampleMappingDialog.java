package org.baseplayer.components;

import java.util.Optional;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Ask how to attach matrix sample columns when sample tracks already exist.
 */
public final class MatrixSampleMappingDialog {

  public enum Policy {
    /** Attach columns that match existing tracks; skip the rest. */
    MAP_EXISTING,
    /** Attach matches and create new tracks for unmatched columns. */
    MERGE
  }

  private MatrixSampleMappingDialog() {}

  /**
   * @param matchedCount columns that match an existing track by name
   * @param unmatchedCount columns with no matching track
   * @return chosen policy, or empty if cancelled
   */
  public static Optional<Policy> choose(Window owner, int matchedCount, int unmatchedCount) {
    return choose(owner, matchedCount, unmatchedCount, null, null);
  }

  /**
   * @param exampleMatrixNames optional matrix column examples (for zero-match hint)
   * @param exampleTrackNames optional existing track name examples
   */
  public static Optional<Policy> choose(
      Window owner,
      int matchedCount,
      int unmatchedCount,
      java.util.List<String> exampleMatrixNames,
      java.util.List<String> exampleTrackNames) {
    AppDialog dialog = new AppDialog(owner, "Map matrix samples");
    dialog.addTitle("Map matrix columns to existing samples?");

    StringBuilder msg = new StringBuilder();
    msg.append(matchedCount).append(" column")
        .append(matchedCount == 1 ? "" : "s")
        .append(" match existing sample tracks by name.\n")
        .append(unmatchedCount).append(" column")
        .append(unmatchedCount == 1 ? "" : "s")
        .append(" have no matching track.");
    if (matchedCount == 0) {
      msg.append("\n\nMap existing only would load nothing — prefer Merge unless you cancel.");
      if (exampleMatrixNames != null && !exampleMatrixNames.isEmpty()) {
        msg.append("\n\nMatrix columns e.g. ").append(String.join(", ", exampleMatrixNames));
      }
      if (exampleTrackNames != null && !exampleTrackNames.isEmpty()) {
        msg.append("\nTracks e.g. ").append(String.join(", ", exampleTrackNames));
      }
    }
    dialog.addMessage(msg.toString());

    ToggleGroup group = new ToggleGroup();
    RadioButton mapOnly = new RadioButton(
        "Map existing only — attach matched columns; skip unmatched");
    RadioButton merge = new RadioButton(
        "Merge — attach matched columns and add unmatched as new samples");
    mapOnly.setToggleGroup(group);
    merge.setToggleGroup(group);
    mapOnly.setUserData(Policy.MAP_EXISTING);
    merge.setUserData(Policy.MERGE);
    mapOnly.setWrapText(true);
    merge.setWrapText(true);
    mapOnly.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12px;");
    merge.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12px;");
    // Default Merge (safe when zero matches; still preferred when some match).
    merge.setSelected(true);

    Label hint = new Label(
        "Matching uses the same name rules as BAM/VCF (case-insensitive / stem).");
    hint.setStyle(AppDialog.HINT_STYLE);
    hint.setWrapText(true);

    VBox choices = new VBox(8, mapOnly, merge, hint);
    choices.setPadding(new Insets(4, 0, 0, 0));
    dialog.addContent(choices);

    dialog.addSecondaryButton("Cancel");
    dialog.addPrimaryButton("Continue");
    if (!dialog.showAndWaitConfirmed()) {
      return Optional.empty();
    }
    Object selected = group.getSelectedToggle() != null
        ? group.getSelectedToggle().getUserData()
        : null;
    if (selected instanceof Policy policy) {
      return Optional.of(policy);
    }
    return Optional.of(Policy.MERGE);
  }
}

package org.baseplayer.analysis.ui;

import org.baseplayer.analysis.AnalysisMode;
import org.baseplayer.analysis.AnalysisSession;
import org.baseplayer.analysis.ComparisonContext;
import org.baseplayer.analysis.GroupStatResult;
import org.baseplayer.analysis.GroupStatTest;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;

/**
 * Shared test-type ChoiceBox for Signal and mutation group-comparison panels.
 */
public final class GroupStatTestControls {

  private GroupStatTestControls() {}

  public static ChoiceBox<GroupStatTest> createChoiceBox(Runnable onChanged) {
    ChoiceBox<GroupStatTest> box = new ChoiceBox<>(FXCollections.observableArrayList(GroupStatTest.values()));
    box.setConverter(new StringConverter<>() {
      @Override
      public String toString(GroupStatTest test) {
        return test != null ? test.displayName() : "";
      }

      @Override
      public GroupStatTest fromString(String string) {
        return GroupStatTest.fromPersisted(string);
      }
    });
    box.setValue(AnalysisSession.get().getGroupStatTest());
    box.setPrefWidth(220);
    Tooltip.install(box, new Tooltip(
        "Hypothesis test for group differences. Auto picks Fisher for sparse "
            + "carrier-like matrices (2 groups), Welch for dense continuous (2 groups), "
            + "Kruskal–Wallis for N>2. Changing test recomputes from the last matrix."));
    box.valueProperty().addListener((o, a, b) -> {
      if (b == null) {
        return;
      }
      AnalysisSession.get().setGroupStatTest(b);
      ComparisonContext.get().writeToDocument(ProjectSessionState.get().getDocument());
      ProjectSessionState.get().markDirty();
      if (onChanged != null) {
        onChanged.run();
      }
    });
    return box;
  }

  public static HBox labeledChoice(ChoiceBox<GroupStatTest> box) {
    HBox row = new HBox(8, new Label("Test"), box);
    row.setAlignment(Pos.CENTER_LEFT);
    return row;
  }

  /**
   * Recompute stats for {@code mode} from the stored matrix when possible.
   * Returns the filtered result, or null if a full re-scan is required.
   */
  public static GroupStatResult recomputeIfPossible(AnalysisMode mode, String scoreLabel) {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    ComparisonContext ctx = ComparisonContext.get();
    return AnalysisSession.get().recomputeFromMatrix(
        mode,
        registry,
        ctx.getParticipatingGroupIds(),
        ctx.getBaselineGroupId(),
        scoreLabel);
  }
}

package org.baseplayer.analysis.ui;

import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Legacy monolithic host — prefer {@link SignalAnalysisWorkspace} pieces
 * (filters / group comparison / results table) for the Signal mode workspace.
 */
public final class SignalAnalysisPanel extends VBox {

  private final SignalAnalysisWorkspace workspace = new SignalAnalysisWorkspace();

  public SignalAnalysisPanel() {
    setSpacing(0);
    VBox.setVgrow(this, Priority.ALWAYS);
    getChildren().addAll(workspace.createFiltersPane(), workspace.createComparisonPane());
    VBox.setVgrow(workspace.getResultTable(), Priority.ALWAYS);
    getChildren().add(workspace.getResultTable());
  }

  public SignalAnalysisWorkspace workspace() {
    return workspace;
  }
}

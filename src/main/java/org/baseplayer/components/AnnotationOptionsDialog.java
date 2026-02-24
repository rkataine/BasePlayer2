package org.baseplayer.components;

import org.baseplayer.controllers.MainController;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.ServiceRegistry;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Modal dialog for gene display options (cancer genes filter, MANE transcripts).
 * Extracted from {@link MainController#showAnnotationOptions()}.
 */
public final class AnnotationOptionsDialog {

  private static final DrawStackManager stackManager =
      ServiceRegistry.getInstance().getDrawStackManager();

  private AnnotationOptionsDialog() {}

  /**
   * Show the dialog. Calls to Apply update {@link MainController#showOnlyCancerGenes}
   * and the MANE-only setting on every DrawStack.
   *
   * @param owner the window that owns the dialog (for modality)
   */
  public static void show(Window owner) {
    Stage dialog = new Stage();
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.initOwner(owner);
    dialog.setTitle("Gene Display Options");
    dialog.setResizable(false);

    VBox content = new VBox(15);
    content.setPadding(new Insets(20));
    content.setStyle("-fx-background-color: #2b2b2b;");

    Label title = new Label("Gene Display Settings");
    title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: white;");

    CheckBox cancerGenesCheckBox = new CheckBox("Show only cancer genes (COSMIC)");
    cancerGenesCheckBox.setSelected(MainController.showOnlyCancerGenes);
    cancerGenesCheckBox.setStyle("-fx-text-fill: white;");

    CheckBox maneCheckBox = new CheckBox("Show only MANE transcripts");
    var stacks = stackManager.getStacks();
    if (!stacks.isEmpty() && stacks.get(0).chromosomeCanvas != null) {
      maneCheckBox.setSelected(stacks.get(0).chromosomeCanvas.isShowManeOnly());
    }
    maneCheckBox.setStyle("-fx-text-fill: white;");

    Label infoLabel = new Label("""
        Cancer genes are from the COSMIC Cancer Gene Census.
        MANE transcripts are the authoritative reference transcripts.""");
    infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #999999; -fx-wrap-text: true;");
    infoLabel.setMaxWidth(300);

    HBox buttonBox = new HBox(10);
    buttonBox.setAlignment(Pos.CENTER_RIGHT);

    Button applyButton = new Button("Apply");
    applyButton.setStyle("-fx-background-color: #0078d4; -fx-text-fill: white; -fx-cursor: hand;");
    applyButton.setOnAction(e -> {
      MainController.showOnlyCancerGenes = cancerGenesCheckBox.isSelected();
      boolean maneOnly = maneCheckBox.isSelected();
      for (DrawStack stack : stacks) {
        if (stack.chromosomeCanvas != null) {
          stack.chromosomeCanvas.setShowManeOnly(maneOnly);
        }
      }
      GenomicCanvas.update.set(!GenomicCanvas.update.get());
      dialog.close();
    });

    Button cancelButton = new Button("Cancel");
    cancelButton.setStyle("-fx-background-color: #3c3c3c; -fx-text-fill: white; -fx-cursor: hand;");
    cancelButton.setOnAction(e -> dialog.close());

    buttonBox.getChildren().addAll(cancelButton, applyButton);
    content.getChildren().addAll(title, cancerGenesCheckBox, maneCheckBox, infoLabel, buttonBox);

    dialog.setScene(new Scene(content));
    dialog.show();
  }
}

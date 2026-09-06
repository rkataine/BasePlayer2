package org.baseplayer.components;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.input.MouseEvent;

public final class PopupComboBoxStyler {

  private PopupComboBoxStyler() {
  }

  public static <T> void styleDarkComboBox(ComboBox<T> comboBox, ContextMenu parentMenu) {
    comboBox.setStyle(
        "-fx-background-color: #333333;"
            + "-fx-control-inner-background: #333333;"
            + "-fx-text-fill: #dddddd;"
            + "-fx-prompt-text-fill: #bbbbbb;"
            + "-fx-mark-color: #dddddd;");

    comboBox.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
      parentMenu.setAutoHide(false);
      if (!comboBox.isShowing()) {
        comboBox.show();
      }
      e.consume();
    });
    comboBox.setOnShowing(e -> parentMenu.setAutoHide(false));
    comboBox.setOnHidden(e -> Platform.runLater(() -> parentMenu.setAutoHide(true)));
    comboBox.addEventFilter(ActionEvent.ACTION, ActionEvent::consume);

    comboBox.setButtonCell(createDarkComboCell());
    comboBox.setCellFactory(listView -> createDarkComboCell());
  }

  private static <T> ListCell<T> createDarkComboCell() {
    return new ListCell<>() {
      @Override
      protected void updateItem(T item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
          setText(null);
          setStyle("-fx-text-fill: #dddddd;");
          return;
        }
        setText(item.toString());
        setStyle("-fx-text-fill: #dddddd;");
      }
    };
  }
}
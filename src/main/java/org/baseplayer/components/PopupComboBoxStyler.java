package org.baseplayer.components;

import org.baseplayer.ui.controls.AppComboBox;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.input.MouseEvent;

/**
 * Styles ComboBoxes embedded in ContextMenus and keeps the menu open while
 * the popup list is shown.
 */
public final class PopupComboBoxStyler {

  private PopupComboBoxStyler() {
  }

  public static <T> void styleDarkComboBox(ComboBox<T> comboBox, ContextMenu parentMenu) {
    AppComboBox.style(comboBox);

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
  }
}

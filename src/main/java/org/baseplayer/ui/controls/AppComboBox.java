package org.baseplayer.ui.controls;

import java.util.Collection;

import org.baseplayer.ui.theme.AppTheme;

import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;

/**
 * Shared ComboBox factory with theme-aware panel styling.
 *
 * <p>Use {@link #create(Collection)} for filter/dialog dropdowns.
 * Genome chrome (transparent) still uses CSS class {@code minimal-combo-box}.
 */
public final class AppComboBox {

  public static final String STYLE_CLASS = "app-combo-box";

  private AppComboBox() {}

  public static <T> ComboBox<T> create(Collection<T> items) {
    ComboBox<T> combo = new ComboBox<>();
    if (items != null) {
      combo.setItems(FXCollections.observableArrayList(items));
    }
    style(combo);
    return combo;
  }

  @SafeVarargs
  public static <T> ComboBox<T> create(T... items) {
    ComboBox<T> combo = new ComboBox<>();
    if (items != null && items.length > 0) {
      combo.getItems().addAll(items);
    }
    style(combo);
    return combo;
  }

  /** Apply panel chrome styling to an existing ComboBox. */
  public static <T> void style(ComboBox<T> combo) {
    if (combo == null) {
      return;
    }
    if (!combo.getStyleClass().contains(STYLE_CLASS)) {
      combo.getStyleClass().add(STYLE_CLASS);
    }
    // Cell text reads AppTheme live; closed control chrome comes from CSS -bp-* tokens.
    combo.setButtonCell(themedCell());
    combo.setCellFactory(listView -> themedCell());
  }

  private static <T> ListCell<T> themedCell() {
    return new ListCell<>() {
      @Override
      protected void updateItem(T item, boolean empty) {
        super.updateItem(item, empty);
        String textColor = AppTheme.chrome().textHex();
        if (empty || item == null) {
          setText(null);
          setStyle("-fx-text-fill: " + textColor + ";");
          return;
        }
        setText(item.toString());
        setStyle("-fx-text-fill: " + textColor + ";");
      }
    };
  }
}

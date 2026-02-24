package org.baseplayer.features;

import org.baseplayer.components.InfoPopup;
import org.baseplayer.components.PopupContent;
import org.baseplayer.components.PopupContent.ActionButton;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.paint.Color;
import javafx.stage.Window;

public final class TrackSettingsPopup {

  private final InfoPopup infoPopup = new InfoPopup(280, 300, false);

  public void show(Track track, Runnable onApply, Window owner, double x, double y) {
    infoPopup.show(buildContent(track, onApply), owner, x, y);
  }

  public void hide() { infoPopup.hide(); }
  public boolean isShowing() { return infoPopup.isShowing(); }

  private PopupContent buildContent(Track track, Runnable onApply) {
    PopupContent c = new PopupContent()
        .title(track.getName() + " Settings", Color.LIGHTGRAY)
        .separator();

    BooleanProperty autoScale = c.checkbox("Auto-scale", track.isAutoScale());
    String initMin = track.getMinValue() != null ? String.format("%.2f", track.getMinValue()) : "";
    String initMax = track.getMaxValue() != null ? String.format("%.2f", track.getMaxValue()) : "";
    StringProperty minProp = c.input("Min", initMin, autoScale);
    StringProperty maxProp = c.input("Max", initMax, autoScale);

    autoScale.addListener((obs, old, newVal) -> {
      if (newVal) { minProp.set(""); maxProp.set(""); }
    });

    c.actions(
        new ActionButton("Cancel", false, () -> {}),
        new ActionButton("Apply", true, () -> {
          if (autoScale.get()) {
            track.setMinValue(null);
            track.setMaxValue(null);
          } else {
            try {
              String min = minProp.get().trim();
              String max = maxProp.get().trim();
              track.setMinValue(min.isEmpty() ? null : Double.valueOf(min));
              track.setMaxValue(max.isEmpty() ? null : Double.valueOf(max));
            } catch (NumberFormatException ex) { /* ignore invalid input */ }
          }
          onApply.run();
        })
    );
    return c;
  }
}

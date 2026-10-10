package org.baseplayer.features;

import java.util.Optional;
import java.util.Set;

import org.baseplayer.MainApp;
import org.baseplayer.components.InfoPopup;
import org.baseplayer.components.PopupContent;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.project.SessionDocumentSync;
import org.baseplayer.utils.AppFonts;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;

public final class TrackSettingsPopup {

  private final InfoPopup infoPopup = new InfoPopup(300, 360, false);

  private Track pendingTrack;
  private StringProperty pendingBarHeight;
  private StringProperty pendingMin;
  private StringProperty pendingMax;
  private BooleanProperty pendingAutoScale;
  private Runnable pendingOnApply;

  public void show(Track track, Runnable onApply, Window owner, double x, double y) {
    pendingOnApply = onApply;
    PopupContent content = buildContent(track, onApply);
    infoPopup.setOnHidden(() -> flushTextFields());
    infoPopup.show(content, owner, x, y);
  }

  public void hide() { infoPopup.hide(); }
  public boolean isShowing() { return infoPopup.isShowing(); }

  private PopupContent buildContent(Track track, Runnable onApply) {
    PopupContent c = new PopupContent()
        .title(track.getName() + " Settings", Color.LIGHTGRAY);

    pendingTrack = track;
    pendingBarHeight = null;
    pendingMin = null;
    pendingMax = null;
    pendingAutoScale = null;

    if (track instanceof BedTrack bed) {
      pendingBarHeight = c.input(
          "Bar height (px)",
          String.format("%.0f", bed.getBarHeightPixels()),
          null);
      pendingBarHeight.addListener((obs, o, n) -> applyBarHeight(bed, n, onApply));

      if (bed.hasScores()) {
        BooleanProperty showScores = c.checkbox("Show scores", bed.isShowScores());
        showScores.addListener((obs, o, on) -> {
          bed.setShowScores(Boolean.TRUE.equals(on));
          notifyChanged(onApply);
        });
      }
      c.separator();

      BedVariantAnnotation.Mode currentMode = bed.getVariantAnnotationMode();
      BooleanProperty useAsAnnotation = c.checkbox(
          "Use as annotation for variants", currentMode.isActive());
      BooleanProperty intersectMode = c.checkbox(
          "Intersect (keep overlapping)", currentMode == BedVariantAnnotation.Mode.INTERSECT);
      BooleanProperty subtractMode = c.checkbox(
          "Subtract (hide overlapping)", currentMode == BedVariantAnnotation.Mode.SUBTRACT);

      final boolean[] syncing = { false };
      Runnable applyAnnotation = () -> {
        if (syncing[0]) {
          return;
        }
        BedVariantAnnotation.Mode mode = BedVariantAnnotation.Mode.OFF;
        if (useAsAnnotation.get()) {
          if (subtractMode.get()) {
            mode = BedVariantAnnotation.Mode.SUBTRACT;
          } else if (intersectMode.get()) {
            mode = BedVariantAnnotation.Mode.INTERSECT;
          } else {
            mode = BedVariantAnnotation.Mode.ANNOTATE;
          }
        }
        BedVariantAnnotation.setMode(bed, mode);
        notifyChanged(onApply);
      };

      useAsAnnotation.addListener((obs, o, enabled) -> {
        if (syncing[0]) {
          return;
        }
        if (Boolean.FALSE.equals(enabled)) {
          syncing[0] = true;
          intersectMode.set(false);
          subtractMode.set(false);
          syncing[0] = false;
        }
        applyAnnotation.run();
      });
      intersectMode.addListener((obs, o, on) -> {
        if (syncing[0]) {
          return;
        }
        if (Boolean.TRUE.equals(on)) {
          syncing[0] = true;
          subtractMode.set(false);
          if (!useAsAnnotation.get()) {
            useAsAnnotation.set(true);
          }
          syncing[0] = false;
        }
        applyAnnotation.run();
      });
      subtractMode.addListener((obs, o, on) -> {
        if (syncing[0]) {
          return;
        }
        if (Boolean.TRUE.equals(on)) {
          syncing[0] = true;
          intersectMode.set(false);
          if (!useAsAnnotation.get()) {
            useAsAnnotation.set(true);
          }
          syncing[0] = false;
        }
        applyAnnotation.run();
      });

      Label hint = new Label(
          "Adds an Excel column with overlapping feature names. "
              + "Intersect/Subtract optionally hide variants. Multiple tracks allowed. "
              + "Use play/stop on the feature track to pause filters.");
      hint.setFont(AppFonts.getUIFont(10));
      hint.setTextFill(Color.GRAY);
      hint.setWrapText(true);
      hint.setMaxWidth(260);
      HBox hintRow = new HBox(hint);
      hintRow.setAlignment(Pos.CENTER_LEFT);
      c.node(hintRow);
      c.separator();
    }

    if (track instanceof MotifTrack motif) {
      int selected = motif.getSelectedMotifIds().size();
      int total = motif.getAllMatrices().size();
      c.row("Motifs", selected + " / " + total + " selected");
      c.actions(new PopupContent.ActionButton(
          "Select motifs…",
          true,
          () -> openMotifPicker(motif, onApply)));
      StringProperty pvalueField = c.input(
          "p-value",
          String.format("%.1e", motif.getPvalue()),
          null);
      pvalueField.addListener((obs, o, n) -> applyMotifPvalue(motif, n, onApply));
      c.separator();
    }

    pendingAutoScale = c.checkbox("Auto-scale", track.isAutoScale());
    String initMin = track.getMinValue() != null ? String.format("%.2f", track.getMinValue()) : "";
    String initMax = track.getMaxValue() != null ? String.format("%.2f", track.getMaxValue()) : "";
    pendingMin = c.input("Min", initMin, pendingAutoScale);
    pendingMax = c.input("Max", initMax, pendingAutoScale);
    BooleanProperty inAggregate = c.checkbox(
        "Include in aggregate", !track.isAggregateDisabled());

    pendingAutoScale.addListener((obs, old, newVal) -> {
      if (Boolean.TRUE.equals(newVal)) {
        pendingMin.set("");
        pendingMax.set("");
        track.setMinValue(null);
        track.setMaxValue(null);
        notifyChanged(onApply);
      } else {
        applyScale(track, pendingMin.get(), pendingMax.get(), onApply);
      }
    });
    pendingMin.addListener((obs, o, n) -> {
      if (!pendingAutoScale.get()) {
        applyScale(track, n, pendingMax.get(), onApply);
      }
    });
    pendingMax.addListener((obs, o, n) -> {
      if (!pendingAutoScale.get()) {
        applyScale(track, pendingMin.get(), n, onApply);
      }
    });
    inAggregate.addListener((obs, o, on) -> {
      track.setAggregateDisabled(!Boolean.TRUE.equals(on));
      notifyChanged(onApply);
    });

    return c;
  }

  private void flushTextFields() {
    Track track = pendingTrack;
    Runnable onApply = pendingOnApply;
    if (track instanceof BedTrack bed && pendingBarHeight != null) {
      applyBarHeight(bed, pendingBarHeight.get(), onApply);
    }
    if (track != null && pendingAutoScale != null && !pendingAutoScale.get()
        && pendingMin != null && pendingMax != null) {
      applyScale(track, pendingMin.get(), pendingMax.get(), onApply);
    }
    pendingTrack = null;
    pendingBarHeight = null;
    pendingMin = null;
    pendingMax = null;
    pendingAutoScale = null;
    pendingOnApply = null;
  }

  private static void openMotifPicker(MotifTrack motif, Runnable onApply) {
    if (motif == null) {
      return;
    }
    Window owner = MainApp.stage;
    Set<String> current = motif.getSelectedMotifIds();
    Optional<MotifFilterDialog.Outcome> choice = MotifFilterDialog.show(
        owner,
        motif.getName(),
        motif.getAllMatrices(),
        current,
        "Apply");
    if (choice.isEmpty()) {
      return;
    }
    motif.setSelectedMotifs(choice.get().selectedIds());
    persistFeatureTracks();
    notifyChanged(onApply);
  }

  private static void applyMotifPvalue(MotifTrack motif, String text, Runnable onApply) {
    if (motif == null || text == null) {
      return;
    }
    try {
      String trimmed = text.trim();
      if (trimmed.isEmpty()) {
        return;
      }
      double next = Double.parseDouble(trimmed);
      if (next <= 0 || next >= 1) {
        return;
      }
      if (Math.abs(motif.getPvalue() - next) < 1e-15) {
        return;
      }
      motif.setPvalue(next);
      persistFeatureTracks();
      notifyChanged(onApply);
    } catch (NumberFormatException ignored) {
    }
  }

  private static void persistFeatureTracks() {
    ProjectSessionState.get().markDirty();
    SessionDocumentSync.writeFeatureTracksFromRuntime(ProjectSessionState.get().getFile());
  }

  private static void applyBarHeight(BedTrack bed, String text, Runnable onApply) {
    if (text == null) {
      return;
    }
    try {
      String h = text.trim();
      if (!h.isEmpty()) {
        double next = Double.parseDouble(h);
        if (Math.abs(bed.getBarHeightPixels() - next) > 0.01) {
          bed.setBarHeightPixels(next);
          notifyChanged(onApply);
        }
      }
    } catch (NumberFormatException ignored) {
    }
  }

  private static void applyScale(Track track, String minText, String maxText, Runnable onApply) {
    try {
      String min = minText != null ? minText.trim() : "";
      String max = maxText != null ? maxText.trim() : "";
      Double nextMin = min.isEmpty() ? null : Double.valueOf(min);
      Double nextMax = max.isEmpty() ? null : Double.valueOf(max);
      boolean changed = !java.util.Objects.equals(track.getMinValue(), nextMin)
          || !java.util.Objects.equals(track.getMaxValue(), nextMax);
      if (changed) {
        track.setMinValue(nextMin);
        track.setMaxValue(nextMax);
        notifyChanged(onApply);
      }
    } catch (NumberFormatException ignored) {
    }
  }

  private static void notifyChanged(Runnable onApply) {
    if (onApply != null) {
      onApply.run();
    }
  }
}

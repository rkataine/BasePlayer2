package org.baseplayer.features;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.baseplayer.features.motif.MotifMatrix;
import org.baseplayer.utils.FeatureNameColors;

import javafx.stage.Window;

/**
 * Motif picker for JASPAR/PFM tracks — searchable via {@link FeatureSelectionDialog}.
 */
public final class MotifFilterDialog {

  public record Outcome(Set<String> selectedIds) {}

  private MotifFilterDialog() {}

  public static Optional<Outcome> show(
      Window owner, String trackTitle, List<MotifMatrix> matrices) {
    return show(owner, trackTitle, matrices, null, "Load");
  }

  public static Optional<Outcome> show(
      Window owner,
      String trackTitle,
      List<MotifMatrix> matrices,
      Set<String> initiallySelected,
      String confirmLabel) {
    if (matrices == null || matrices.isEmpty()) {
      return Optional.empty();
    }

    Map<String, MotifMatrix> byId = new HashMap<>();
    List<FeatureSelectionDialog.Item> items = new ArrayList<>(matrices.size());
    for (MotifMatrix matrix : matrices) {
      byId.put(matrix.id(), matrix);
      items.add(new FeatureSelectionDialog.Item(
          matrix.id(),
          matrix.id() + " " + matrix.name(),
          matrix.displayLabel() + "  (" + matrix.length() + " bp)",
          FeatureNameColors.colorForName(matrix.name())));
    }

    FeatureSelectionDialog.HeaderAction representatives = new FeatureSelectionDialog.HeaderAction(
        "Representatives only",
        "Select one motif per name (longest matrix; ties keep higher JASPAR version)",
        ctx -> {
          List<MotifMatrix> candidates = new ArrayList<>();
          for (String id : ctx.visibleIds()) {
            MotifMatrix matrix = byId.get(id);
            if (matrix != null) {
              candidates.add(matrix);
            }
          }
          Set<String> reps = representativeIds(candidates);
          LinkedHashSet<String> next = new LinkedHashSet<>(ctx.selected());
          next.removeAll(ctx.visibleIds());
          next.addAll(reps);
          return next;
        });

    FeatureSelectionDialog.Config config = new FeatureSelectionDialog.Config(
        "Select motifs",
        trackTitle == null || trackTitle.isBlank()
            ? "Which motifs to scan?"
            : "Motifs in " + trackTitle,
        matrices.size()
            + " matrices. Search and select a subset — scanning hundreds at once is slow.",
        "Filter by ID or name…",
        confirmLabel,
        false,
        List.of(representatives));

    return FeatureSelectionDialog.show(owner, config, items, initiallySelected)
        .map(o -> new Outcome(o.selectedIds()));
  }

  /**
   * One motif per TF name (case-insensitive): longest matrix wins; ties prefer
   * higher JASPAR version suffix ({@code MA0004.2} over {@code MA0004.1}).
   */
  static Set<String> representativeIds(List<MotifMatrix> matrices) {
    Map<String, MotifMatrix> bestByName = new HashMap<>();
    for (MotifMatrix matrix : matrices) {
      if (matrix == null) {
        continue;
      }
      String key = matrix.name().trim().toLowerCase(Locale.ROOT);
      MotifMatrix prev = bestByName.get(key);
      if (prev == null || isBetterRepresentative(matrix, prev)) {
        bestByName.put(key, matrix);
      }
    }
    Set<String> ids = new LinkedHashSet<>();
    for (MotifMatrix matrix : matrices) {
      if (matrix == null) {
        continue;
      }
      MotifMatrix best = bestByName.get(matrix.name().trim().toLowerCase(Locale.ROOT));
      if (best != null && best.id().equals(matrix.id())) {
        ids.add(matrix.id());
      }
    }
    return ids;
  }

  private static boolean isBetterRepresentative(MotifMatrix candidate, MotifMatrix incumbent) {
    int lenCmp = Integer.compare(candidate.length(), incumbent.length());
    if (lenCmp != 0) {
      return lenCmp > 0;
    }
    return jasparVersion(candidate.id()) > jasparVersion(incumbent.id());
  }

  /** Parses trailing {@code .N} from JASPAR IDs; {@code 0} when absent. */
  private static int jasparVersion(String id) {
    if (id == null) {
      return 0;
    }
    int dot = id.lastIndexOf('.');
    if (dot < 0 || dot + 1 >= id.length()) {
      return 0;
    }
    try {
      return Integer.parseInt(id.substring(dot + 1));
    } catch (NumberFormatException ex) {
      return 0;
    }
  }
}

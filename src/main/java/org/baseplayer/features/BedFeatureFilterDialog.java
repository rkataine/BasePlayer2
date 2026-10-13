package org.baseplayer.features;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.baseplayer.io.readers.BedFeatureNameCatalog;
import org.baseplayer.io.readers.BedFeatureNameDiscovery;

import javafx.stage.Window;

/**
 * Pre-load filter for BED feature names — searchable via {@link FeatureSelectionDialog}.
 * When a source path is provided, discovery continues while the dialog is open and
 * newly found types are appended to the list in realtime.
 */
public final class BedFeatureFilterDialog {

  /**
   * @param selectedKeys selected entry keys (feature names and/or
   *        {@link BedFeatureNameCatalog#OTHER_NAME})
   * @param listedNames names shown as individual rows (excludes Other)
   * @param loadAll true when every row including Other (if present) is selected
   * @param catalog final catalog at confirm time (includes live discoveries)
   */
  public record Outcome(
      Set<String> selectedKeys,
      Set<String> listedNames,
      boolean loadAll,
      BedFeatureNameCatalog.Catalog catalog) {
  }

  private BedFeatureFilterDialog() {}

  public static Optional<Outcome> show(
      Window owner, String trackTitle, BedFeatureNameCatalog.Catalog catalog) {
    return show(owner, trackTitle, catalog, null, "Load", null);
  }

  public static Optional<Outcome> show(
      Window owner,
      String trackTitle,
      BedFeatureNameCatalog.Catalog catalog,
      Set<String> initiallySelected,
      String confirmLabel) {
    return show(owner, trackTitle, catalog, initiallySelected, confirmLabel, null);
  }

  /**
   * @param discoverFrom when non-null and catalog still has an Other bucket,
   *        keep scanning this file while the dialog is open (no idle stop)
   */
  public static Optional<Outcome> show(
      Window owner,
      String trackTitle,
      BedFeatureNameCatalog.Catalog catalog,
      Set<String> initiallySelected,
      String confirmLabel,
      Path discoverFrom) {
    if (catalog == null || catalog.entries().isEmpty()) {
      return Optional.of(new Outcome(null, Set.of(), true, catalog));
    }

    AtomicReference<BedFeatureNameCatalog.Catalog> latest =
        new AtomicReference<>(catalog);
    List<FeatureSelectionDialog.Item> items = toItems(catalog);

    String hintText = hintFor(catalog, discoverFrom != null && catalog.hasOtherBucket());
    FeatureSelectionDialog.Config config = new FeatureSelectionDialog.Config(
        "Select features",
        trackTitle == null || trackTitle.isBlank()
            ? "Which features to load?"
            : "Features in " + trackTitle,
        hintText,
        "Filter by feature name…",
        confirmLabel,
        false,
        List.of());

    BedFeatureNameDiscovery[] discovery = { null };
    Optional<FeatureSelectionDialog.Outcome> choice = FeatureSelectionDialog.show(
        owner,
        config,
        items,
        initiallySelected,
        BedFeatureNameCatalog.OTHER_NAME,
        live -> {
          if (discoverFrom == null || !catalog.hasOtherBucket()) {
            return;
          }
          discovery[0] = BedFeatureNameDiscovery.start(
              discoverFrom,
              catalog,
              false,
              100L,
              next -> {
                latest.set(next);
                live.mergeItems(toItems(next));
                live.setHint(hintFor(next, next.hasOtherBucket()));
              },
              null);
        });

    if (discovery[0] != null) {
      discovery[0].cancel();
    }
    if (choice.isEmpty()) {
      return Optional.empty();
    }

    BedFeatureNameCatalog.Catalog finalCatalog = latest.get();
    if (finalCatalog == null) {
      finalCatalog = catalog;
    }
    Set<String> selected = choice.get().selectedIds();
    boolean loadAll = selected.containsAll(
        finalCatalog.entries().stream().map(BedFeatureNameCatalog.Entry::name).toList());
    return Optional.of(new Outcome(
        loadAll ? null : Set.copyOf(selected),
        finalCatalog.listedNames(),
        loadAll,
        finalCatalog));
  }

  private static String hintFor(BedFeatureNameCatalog.Catalog catalog, boolean scanning) {
    if (scanning) {
      return "Scanning for more types… "
          + catalog.listedNames().size()
          + " found so far. % is share of the first "
          + catalog.sampleRows()
          + " rows. “Other types” covers names not listed yet.";
    }
    if (catalog.hasOtherBucket()) {
      return "Types from the file sample. % is share of the first "
          + catalog.sampleRows()
          + " rows. “Other types” covers names not listed yet.";
    }
    return "Deselect types you do not want. % is share of the scanned sample.";
  }

  private static List<FeatureSelectionDialog.Item> toItems(
      BedFeatureNameCatalog.Catalog catalog) {
    List<BedFeatureNameCatalog.Entry> entries = catalog.entries();
    int sampleRows = Math.max(1, catalog.sampleRows());
    List<FeatureSelectionDialog.Item> items = new ArrayList<>(entries.size());
    for (BedFeatureNameCatalog.Entry entry : entries) {
      String label = entry.displayLabel();
      if (!entry.otherBucket() && entry.count() > 0 && sampleRows > 0) {
        label = label + "  (" + formatSamplePercent(entry.count(), sampleRows) + ")";
      }
      String searchText = entry.otherBucket()
          ? "other types"
          : (entry.name() == null ? "" : entry.name());
      String tooltip = entry.otherBucket()
          ? "Feature names not yet listed; discovery may still be scanning"
          : null;
      items.add(new FeatureSelectionDialog.Item(
          entry.name(), searchText, label, entry.color(), tooltip));
    }
    return items;
  }

  /** Share of the scanned sample rows (e.g. of the first 1000). */
  private static String formatSamplePercent(long count, int sampleRows) {
    double pct = 100.0 * count / sampleRows;
    if (pct >= 10) {
      return String.format("%.0f%%", pct);
    }
    if (pct >= 1) {
      return String.format("%.1f%%", pct);
    }
    if (pct > 0) {
      return String.format("%.2f%%", pct);
    }
    return "0%";
  }
}

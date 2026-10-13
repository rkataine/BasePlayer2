package org.baseplayer.variant.ui.components;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.baseplayer.components.ColoredToggle;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.io.VcfManager;
import org.baseplayer.project.ProjectDocument;
import org.baseplayer.project.SessionDocumentSync;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator.ControlFileStats;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator.PopulationAnStats;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator.PopulationOption;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Pair;

/**
 * Control Files tab: load a local gnomAD-style sites VCF, pick population AF fields,
 * and optionally filter sample variants by control allele frequency.
 * Opening a file only loads metadata — click Apply to annotate.
 */
public class ControlFilesPanel {

    private static final int STATS_SAMPLE_ROWS = 10_000;

    /** Distinct swatches for population checkboxes (gene-type style). */
    private static final Color[] POPULATION_COLORS = {
        Color.web("#4A90E2"),
        Color.web("#7ED321"),
        Color.web("#F5A623"),
        Color.web("#BD10E0"),
        Color.web("#50E3C2"),
        Color.web("#D0021B"),
        Color.web("#9013FE"),
        Color.web("#8B572A"),
        Color.web("#417505"),
        Color.web("#B8E986"),
    };

    public record Nodes(
        Label controlFilePathLabel,
        Button addControlVcfButton,
        Button removeControlFileButton,
        ColoredToggle filterByPopFreqCheckBox,
        TextField maxPopFreqField,
        ColoredToggle selectAllPopulationsCheckBox,
        VBox populationCheckBoxContainer
    ) {}

    private Nodes nodes;
    private final ControlFrequencyAnnotator annotator = new ControlFrequencyAnnotator();
    private final Map<String, ColoredToggle> populationCheckBoxes = new LinkedHashMap<>();
    private final Map<String, Label> populationStatsLabels = new LinkedHashMap<>();

    private Supplier<Window> windowSupplier = () -> null;
    private Supplier<List<VcfManager.CachedChromosomeVariants>> sourcesSupplier = List::of;
    /** Called after annotate/clear so the controller can rebuild tables / apply filters. */
    private Consumer<Boolean> afterAnnotate = ignored -> {};
    /** Pop-freq checkbox / max-freq field changed — apply filter without re-annotating. */
    private Runnable onFilterSettingsChanged = () -> {};
    /**
     * Optional: keep a sibling Control Files panel (Point ↔ SV) in sync so both
     * modes share the same durable settings.
     */
    private Consumer<ControlFilesPanel> onSettingsMirrored = ignored -> {};
    /** Suppress mirror/listener side-effects while applying programmatic UI updates. */
    private boolean suppressSettingsPersist;
    /**
     * When set, annotation work is handed to the controller (background + loading modal)
     * instead of running on the FX thread.
     */
    /**
     * Schedules annotation: {@code starter.accept(body)} where
     * {@code body.accept(progressSuffix)} runs the blocking annotate work.
     */
    private Consumer<Consumer<Consumer<String>>> annotateExecutor;

    public void install(Nodes nodes) {
        this.nodes = nodes;
        wireButtons();
        bindImmediateFilterApply();
        refreshFileUi();
    }

    public void setWindowSupplier(Supplier<Window> windowSupplier) {
        this.windowSupplier = windowSupplier != null ? windowSupplier : () -> null;
    }

    public void setSourcesSupplier(
            Supplier<List<VcfManager.CachedChromosomeVariants>> sourcesSupplier) {
        this.sourcesSupplier = sourcesSupplier != null ? sourcesSupplier : List::of;
    }

    /**
     * @param afterAnnotate receives {@code true} when annotation ran (or cleared) and
     *        the controller should apply filters / rebuild
     */
    public void setAfterAnnotate(Consumer<Boolean> afterAnnotate) {
        this.afterAnnotate = afterAnnotate != null ? afterAnnotate : ignored -> {};
    }

    public void setOnFilterSettingsChanged(Runnable onFilterSettingsChanged) {
        this.onFilterSettingsChanged =
            onFilterSettingsChanged != null ? onFilterSettingsChanged : () -> {};
    }

    public void setOnSettingsMirrored(Consumer<ControlFilesPanel> onSettingsMirrored) {
        this.onSettingsMirrored = onSettingsMirrored != null ? onSettingsMirrored : ignored -> {};
    }

    /**
     * Run heavy annotate off the FX thread. {@code starter.accept(body)} should submit
     * {@code body} to a background executor; {@code body.accept(progress)} runs annotate.
     */
    public void setAnnotateExecutor(Consumer<Consumer<Consumer<String>>> annotateExecutor) {
        this.annotateExecutor = annotateExecutor;
    }

    public ControlFrequencyAnnotator annotator() {
        return annotator;
    }

    public Nodes getNodes() {
        return nodes;
    }

    public void writeTo(VariantFilter filter) {
        if (filter == null || nodes == null) {
            return;
        }
        filter.setFilterByControlAf(isFilterByPopFreqSelected());
        filter.setMaxControlAf(readMaxPopFreq());
    }

    public void loadFrom(VariantFilter filter) {
        if (filter == null || nodes == null) {
            return;
        }
        boolean previous = suppressSettingsPersist;
        suppressSettingsPersist = true;
        try {
            if (nodes.filterByPopFreqCheckBox() != null) {
                nodes.filterByPopFreqCheckBox().setSelected(filter.isFilterByControlAf());
            }
            if (nodes.maxPopFreqField() != null) {
                nodes.maxPopFreqField().setText(String.valueOf(filter.getMaxControlAf()));
            }
        } finally {
            suppressSettingsPersist = previous;
        }
    }

    /** Persist path + pop-freq toggles + populations into the project document. */
    public void writeToDocument(ProjectDocument.ControlFilesSpec spec) {
        if (spec == null) {
            return;
        }
        Path open = annotator.path();
        spec.controlVcfPath = open != null ? open.toAbsolutePath().normalize().toString() : null;
        spec.filterByControlAf = isFilterByPopFreqSelected();
        spec.maxControlAf = readMaxPopFreq();
        String pops = annotator.selectedPopulationsPersistString();
        spec.controlPopulation = pops == null || pops.isBlank() ? null : pops;
    }

    /**
     * Restore control VCF from a saved project. Opens the reader when the file exists;
     * does <b>not</b> re-annotate — chromosome caches already hold {@code controlAf}
     * when {@link VariantList#getControlAfSourcePath()} matches.
     *
     * @return true when a control file was opened
     */
    public boolean restoreFromDocument(ProjectDocument.ControlFilesSpec spec) {
        if (spec == null || nodes == null) {
            return false;
        }
        boolean previous = suppressSettingsPersist;
        suppressSettingsPersist = true;
        try {
            if (nodes.filterByPopFreqCheckBox() != null) {
                nodes.filterByPopFreqCheckBox().setSelected(spec.filterByControlAf);
            }
            if (nodes.maxPopFreqField() != null) {
                nodes.maxPopFreqField().setText(String.valueOf(
                    spec.maxControlAf > 0 ? spec.maxControlAf : 0.01));
            }
            String pathText = spec.controlVcfPath;
            if (pathText == null || pathText.isBlank()) {
                refreshPopulations(null, false);
                refreshFileUi();
                return false;
            }
            Path path = Path.of(pathText);
            if (!java.nio.file.Files.isRegularFile(path)) {
                refreshPopulations(null, false);
                refreshFileUi();
                return false;
            }
            try {
                annotator.open(path);
            } catch (IOException e) {
                System.err.println("Could not restore control VCF: " + e.getMessage());
                refreshFileUi();
                return false;
            }
            List<String> preferred =
                ControlFrequencyAnnotator.parsePopulationsPersistString(spec.controlPopulation);
            refreshPopulations(preferred, false);
            refreshFileUi();
            refreshStatsAsync();
            return true;
        } finally {
            suppressSettingsPersist = previous;
        }
    }

    /** Copy pop-freq toggles and populations from another panel without persisting. */
    public void mirrorFilterSettingsFrom(ControlFilesPanel other) {
        if (other == null || other == this || nodes == null) {
            return;
        }
        boolean previous = suppressSettingsPersist;
        suppressSettingsPersist = true;
        try {
            if (nodes.filterByPopFreqCheckBox() != null) {
                nodes.filterByPopFreqCheckBox().setSelected(other.isFilterByPopFreqSelected());
            }
            if (nodes.maxPopFreqField() != null) {
                nodes.maxPopFreqField().setText(String.valueOf(other.readMaxPopFreq()));
            }
            Set<String> pops = other.annotator.selectedPopulations();
            annotator.setSelectedPopulations(pops);
            if (other.annotator.isOpen() && other.annotator.path() != null) {
                Path path = other.annotator.path();
                if (!annotator.isOpen() || !path.equals(annotator.path())) {
                    try {
                        annotator.open(path);
                    } catch (IOException e) {
                        System.err.println("Could not mirror control VCF: " + e.getMessage());
                    }
                }
            } else if (!other.annotator.isOpen() && annotator.isOpen()) {
                annotator.close();
            }
            refreshPopulations(new ArrayList<>(pops), false);
            refreshFileUi();
            if (annotator.isOpen()) {
                // Copy already-sampled counts from the sibling when available.
                copyPopulationStatsFrom(other);
            }
        } finally {
            suppressSettingsPersist = previous;
        }
    }

    private void copyPopulationStatsFrom(ControlFilesPanel other) {
        if (other == null) {
            return;
        }
        for (Map.Entry<String, Label> e : populationStatsLabels.entrySet()) {
            Label src = other.populationStatsLabels.get(e.getKey());
            if (src != null && e.getValue() != null) {
                e.getValue().setText(src.getText());
            }
        }
    }

    private boolean isFilterByPopFreqSelected() {
        return nodes != null
            && nodes.filterByPopFreqCheckBox() != null
            && nodes.filterByPopFreqCheckBox().isSelected();
    }

    private double readMaxPopFreq() {
        double maxAf = 0.01;
        if (nodes != null && nodes.maxPopFreqField() != null) {
            String text = nodes.maxPopFreqField().getText();
            if (text != null && !text.isBlank()) {
                try {
                    maxAf = Double.parseDouble(text.trim());
                } catch (NumberFormatException ignored) {
                    maxAf = 0.01;
                }
            }
        }
        return maxAf;
    }

    public void handleApplyControlSettings() {
        syncSelectedPopulationsFromUi();
        runAnnotateLoadedSources(true);
    }

    public void handleAddControlVcf() {
        if (nodes == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add control VCF (gnomAD sites)");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Compressed VCF", "*.vcf.gz"),
            new FileChooser.ExtensionFilter("VCF", "*.vcf", "*.vcf.gz"));
        File initialDir = UserPreferences.getLastDirectory("CONTROL_VCF");
        if (initialDir == null) {
            Path open = annotator.path();
            if (open != null && open.getParent() != null) {
                initialDir = open.getParent().toFile();
            }
        }
        if (initialDir == null) {
            initialDir = UserPreferences.getLastDirectory("VCF");
        }
        if (initialDir != null) {
            chooser.setInitialDirectory(initialDir);
        }
        Window window = windowSupplier.get();
        File chosen = window != null ? chooser.showOpenDialog(window) : chooser.showOpenDialog(null);
        if (chosen == null) {
            return;
        }
        UserPreferences.setLastDirectory("CONTROL_VCF", chosen.getParentFile());
        Path path = chosen.toPath();
        try {
            annotator.open(path);
        } catch (IOException e) {
            showError("Could not open control VCF", e.getMessage());
            return;
        }
        // Select all populations by default; do not annotate until Apply.
        refreshPopulations(null, true);
        refreshFileUi();
        SessionDocumentSync.writeControlVcfPath(path.toAbsolutePath().normalize().toString());
        persistFilterSettingsToDocument();
        refreshStatsAsync();
        onSettingsMirrored.accept(this);
    }

    public void handleRemoveControlFile() {
        annotator.close();
        clearControlAfOnSources();
        refreshPopulations(null, false);
        refreshFileUi();
        SessionDocumentSync.writeControlVcfPath(null);
        persistFilterSettingsToDocument();
        onSettingsMirrored.accept(this);
        afterAnnotate.accept(true);
    }

    private void wireButtons() {
        if (nodes == null) {
            return;
        }
        if (nodes.addControlVcfButton() != null) {
            nodes.addControlVcfButton().setOnAction(e -> handleAddControlVcf());
        }
        if (nodes.removeControlFileButton() != null) {
            nodes.removeControlFileButton().setOnAction(e -> handleRemoveControlFile());
        }
        if (nodes.selectAllPopulationsCheckBox() != null) {
            nodes.selectAllPopulationsCheckBox().setOnAction(e -> {
                if (suppressSettingsPersist) {
                    return;
                }
                boolean selectAll = nodes.selectAllPopulationsCheckBox().isSelected();
                boolean previous = suppressSettingsPersist;
                suppressSettingsPersist = true;
                try {
                    for (ColoredToggle cb : populationCheckBoxes.values()) {
                        cb.setSelected(selectAll);
                    }
                } finally {
                    suppressSettingsPersist = previous;
                }
                onPopulationSelectionChanged();
            });
        }
    }

    private void bindImmediateFilterApply() {
        if (nodes == null) {
            return;
        }
        if (nodes.filterByPopFreqCheckBox() != null) {
            nodes.filterByPopFreqCheckBox().selectedProperty().addListener(
                (obs, was, is) -> onControlFilterUiChanged());
        }
        if (nodes.maxPopFreqField() != null) {
            nodes.maxPopFreqField().textProperty().addListener(
                (obs, oldText, newText) -> onControlFilterUiChanged());
        }
    }

    private void onControlFilterUiChanged() {
        if (suppressSettingsPersist) {
            return;
        }
        persistFilterSettingsToDocument();
        onSettingsMirrored.accept(this);
        onFilterSettingsChanged.run();
    }

    private void onPopulationSelectionChanged() {
        if (suppressSettingsPersist) {
            return;
        }
        syncSelectedPopulationsFromUi();
        syncSelectAllCheckbox();
        SessionDocumentSync.writeControlPopulation(annotator.selectedPopulationsPersistString());
        persistFilterSettingsToDocument();
        onSettingsMirrored.accept(this);
        // Do not annotate here — Apply commits AF fields.
    }

    private void syncSelectedPopulationsFromUi() {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (Map.Entry<String, ColoredToggle> e : populationCheckBoxes.entrySet()) {
            if (e.getValue() != null && e.getValue().isSelected()) {
                selected.add(e.getKey());
            }
        }
        annotator.setSelectedPopulations(selected);
    }

    private void syncSelectAllCheckbox() {
        if (nodes == null || nodes.selectAllPopulationsCheckBox() == null) {
            return;
        }
        boolean allSelected = !populationCheckBoxes.isEmpty()
            && populationCheckBoxes.values().stream().allMatch(ColoredToggle::isSelected);
        boolean previous = suppressSettingsPersist;
        suppressSettingsPersist = true;
        try {
            nodes.selectAllPopulationsCheckBox().setSelected(allSelected);
        } finally {
            suppressSettingsPersist = previous;
        }
    }

    /** Write pop-freq toggles + populations into the live project document immediately. */
    public void persistFilterSettingsToDocument() {
        if (nodes == null) {
            return;
        }
        SessionDocumentSync.writeControlFilterSettings(
            isFilterByPopFreqSelected(),
            readMaxPopFreq(),
            annotator.selectedPopulationsPersistString());
    }

    private void runAnnotateLoadedSources(boolean notify) {
        if (annotateExecutor != null) {
            annotateExecutor.accept(progress -> annotateLoadedSources(false, progress));
        } else {
            annotateLoadedSources(notify, null);
        }
    }

    /** Annotate every loaded chromosome list with the current control VCF. */
    public void annotateLoadedSources(boolean notify) {
        annotateLoadedSources(notify, null);
    }

    /**
     * @param progressSuffix optional UI updater (e.g. ThreadRunner task suffix); may be null
     */
    public void annotateLoadedSources(boolean notify, Consumer<String> progressSuffix) {
        if (!annotator.isOpen()) {
            if (notify) {
                afterAnnotate.accept(true);
            }
            return;
        }
        List<VcfManager.CachedChromosomeVariants> sources = sourcesSupplier.get();
        if (sources == null || sources.isEmpty()) {
            if (notify) {
                afterAnnotate.accept(true);
            }
            return;
        }
        int total = sources.size();
        int index = 0;
        for (VcfManager.CachedChromosomeVariants cached : sources) {
            index++;
            if (cached == null || cached.variants() == null) {
                continue;
            }
            final int chromIndex = index;
            final String chrom = cached.chromosome() != null ? cached.chromosome() : "?";
            if (progressSuffix != null) {
                progressSuffix.accept("(" + chromIndex + "/" + total + " chr" + chrom + ")");
            }
            try {
                annotator.annotate(cached.variants(), (c, cur, tot, matched) -> {
                    org.baseplayer.services.LoadingManager.get()
                        .setProgress(cur, Math.max(1, tot));
                    if (progressSuffix == null) {
                        return;
                    }
                    progressSuffix.accept(
                        "(" + chromIndex + "/" + total
                            + " chr" + c
                            + ", " + matched + " AF)");
                });
            } catch (IOException e) {
                // May run on ThreadRunner loader-pool — never build Alert here.
                showError("Control annotation failed", chrom + ": " + e.getMessage());
                break;
            }
        }
        if (notify) {
            afterAnnotate.accept(true);
        }
    }

    /** Annotate one list quietly (e.g. after gene annotation finishes). */
    public void annotateIfOpen(VariantList list) {
        annotateIfOpen(list, null);
    }

    public void annotateIfOpen(VariantList list, Consumer<String> progressSuffix) {
        if (!annotator.isOpen() || list == null) {
            return;
        }
        try {
            annotator.annotate(list, (c, cur, tot, matched) -> {
                org.baseplayer.services.LoadingManager.get()
                    .setProgress(cur, Math.max(1, tot));
                if (progressSuffix == null) {
                    return;
                }
                progressSuffix.accept(
                    "(chr" + c + ", " + matched + " AF)");
            });
        } catch (IOException e) {
            System.err.println("Control AF annotation failed for "
                + list.getChromosome() + ": " + e.getMessage());
        }
    }

    private void clearControlAfOnSources() {
        List<VcfManager.CachedChromosomeVariants> sources = sourcesSupplier.get();
        if (sources == null) {
            return;
        }
        for (VcfManager.CachedChromosomeVariants cached : sources) {
            if (cached != null) {
                ControlFrequencyAnnotator.clear(cached.variants());
            }
        }
    }

    private void refreshFileUi() {
        if (nodes == null) {
            return;
        }
        Path open = annotator.path();
        if (nodes.controlFilePathLabel() != null) {
            if (open != null) {
                nodes.controlFilePathLabel().setText(open.toAbsolutePath().normalize().toString());
            } else {
                nodes.controlFilePathLabel().setText("No control file loaded");
            }
        }
        if (nodes.removeControlFileButton() != null) {
            nodes.removeControlFileButton().setDisable(open == null);
        }
        boolean hasFile = open != null;
        if (nodes.selectAllPopulationsCheckBox() != null) {
            nodes.selectAllPopulationsCheckBox().setDisable(!hasFile || populationCheckBoxes.isEmpty());
        }
        if (nodes.populationCheckBoxContainer() != null) {
            nodes.populationCheckBoxContainer().setDisable(!hasFile);
        }
    }

    /**
     * Rebuild population rows (toggle + sample-count stats).
     *
     * @param preferredIds selected ids when non-null; when null and {@code selectAllDefault},
     *        all populations are selected; when null and not select-all, keep annotator set
     * @param selectAllDefault used only when {@code preferredIds} is null and a file is open
     */
    private void refreshPopulations(List<String> preferredIds, boolean selectAllDefault) {
        if (nodes == null || nodes.populationCheckBoxContainer() == null) {
            return;
        }
        boolean previous = suppressSettingsPersist;
        suppressSettingsPersist = true;
        try {
            populationCheckBoxes.clear();
            populationStatsLabels.clear();
            nodes.populationCheckBoxContainer().getChildren().clear();

            if (!annotator.isOpen()) {
                annotator.setSelectedPopulations(List.of(ControlFrequencyAnnotator.GLOBAL_POPULATION));
                syncSelectAllCheckbox();
                return;
            }

            List<PopulationOption> options = annotator.listPopulations();
            Set<String> want;
            if (preferredIds != null) {
                want = new LinkedHashSet<>(preferredIds);
            } else if (selectAllDefault) {
                want = new LinkedHashSet<>();
                for (PopulationOption opt : options) {
                    want.add(opt.id());
                }
            } else {
                want = new LinkedHashSet<>(annotator.selectedPopulations());
            }
            if (want.isEmpty()) {
                want.add(ControlFrequencyAnnotator.GLOBAL_POPULATION);
            }

            int colorIndex = 0;
            for (PopulationOption opt : options) {
                Color color = POPULATION_COLORS[colorIndex % POPULATION_COLORS.length];
                colorIndex++;
                ColoredToggle cb = new ColoredToggle(opt.label(), color);
                cb.setSelected(want.contains(opt.id()));
                cb.setTooltip(new Tooltip(ControlFrequencyAnnotator.populationFieldHint(opt.id())));
                cb.setOnAction(e -> {
                    if (!suppressSettingsPersist) {
                        onPopulationSelectionChanged();
                    }
                });
                // Keep the toggle from stretching; stats sit on the right.
                cb.setMaxWidth(Region.USE_PREF_SIZE);
                HBox.setHgrow(cb, Priority.NEVER);

                Label stats = new Label("…");
                stats.getStyleClass().add("subsection-label");
                stats.setMinWidth(Region.USE_PREF_SIZE);

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                HBox row = new HBox(8, cb, spacer, stats);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setMaxWidth(Double.MAX_VALUE);

                populationCheckBoxes.put(opt.id(), cb);
                populationStatsLabels.put(opt.id(), stats);
                nodes.populationCheckBoxContainer().getChildren().add(row);
            }
            syncSelectedPopulationsFromUi();
            syncSelectAllCheckbox();
        } finally {
            suppressSettingsPersist = previous;
        }
    }

    private void refreshStatsAsync() {
        if (!annotator.isOpen()) {
            setAllPopulationStatsText("—");
            return;
        }
        setAllPopulationStatsText("…");
        Path path = annotator.path();
        Thread t = new Thread(() -> {
            ControlFileStats stats;
            try (ControlFrequencyAnnotator peek = new ControlFrequencyAnnotator()) {
                peek.open(path);
                stats = peek.sampleStats(STATS_SAMPLE_ROWS);
            } catch (IOException e) {
                Platform.runLater(() -> setAllPopulationStatsText("n/a"));
                return;
            }
            ControlFileStats finalStats = stats;
            Platform.runLater(() -> {
                if (!annotator.isOpen() || path == null || !path.equals(annotator.path())) {
                    return;
                }
                applyPopulationStats(finalStats);
            });
        }, "control-vcf-stats");
        t.setDaemon(true);
        t.start();
    }

    private void applyPopulationStats(ControlFileStats stats) {
        if (stats == null) {
            setAllPopulationStatsText("—");
            return;
        }
        for (Map.Entry<String, Label> e : populationStatsLabels.entrySet()) {
            PopulationAnStats pop = stats.forPopulation(e.getKey());
            e.getValue().setText(pop != null ? pop.summaryBesideLabel() : "—");
        }
    }

    private void setAllPopulationStatsText(String text) {
        String value = text != null ? text : "";
        for (Label label : populationStatsLabels.values()) {
            if (label != null) {
                label.setText(value);
            }
        }
    }

    private void showError(String title, String detail) {
        Runnable show = () -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(title);
            alert.setContentText(detail != null ? detail : "");
            Window window = windowSupplier.get();
            if (window != null) {
                alert.initOwner(window);
            }
            alert.showAndWait();
        };
        if (Platform.isFxApplicationThread()) {
            show.run();
        } else {
            Platform.runLater(show);
        }
    }

    /**
     * Build a Control Files UI tree programmatically (for the SV mode workspace).
     */
    public static Pair<Node, Nodes> buildUi() {
        Label pathLabel = new Label("No control file loaded");
        pathLabel.getStyleClass().add("value-label");
        pathLabel.setWrapText(true);

        Button addVcf = new Button("Add VCF");
        addVcf.getStyleClass().add("secondary-button");
        Button remove = new Button("Remove");
        remove.getStyleClass().add("secondary-button");
        remove.setDisable(true);

        ColoredToggle filterByPop = new ColoredToggle("Filter by control allele frequency");
        TextField maxFreq = new TextField("0.01");
        maxFreq.setPrefWidth(80);
        maxFreq.getStyleClass().add("filter-field");

        ColoredToggle selectAllPops = new ColoredToggle();
        selectAllPops.setSelected(true);
        selectAllPops.setDisable(true);
        Label popsLabel = new Label("Populations");
        popsLabel.getStyleClass().add("section-header");
        HBox popsHeader = new HBox(6, selectAllPops, popsLabel);
        popsHeader.setAlignment(Pos.CENTER_LEFT);

        VBox popBox = new VBox(4);
        popBox.setDisable(true);

        VBox left = new VBox(12);
        left.getStyleClass().add("filter-panel");
        left.setPadding(new Insets(16));

        Label fileTitle = new Label("Control file");
        fileTitle.getStyleClass().add("section-header");
        HBox fileButtons = new HBox(8, addVcf, remove);
        fileButtons.setAlignment(Pos.CENTER_LEFT);

        Label filterTitle = new Label("Allele frequency filter");
        filterTitle.getStyleClass().add("section-header");
        HBox filterRow = new HBox(8, filterByPop,
            new Label("Max AF:") {{ getStyleClass().add("subsection-label"); }},
            maxFreq);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        left.getChildren().addAll(
            fileTitle, pathLabel, fileButtons,
            filterTitle, filterRow);

        VBox right = new VBox(12);
        right.getStyleClass().add("filter-panel");
        right.setPadding(new Insets(16));
        Label popHint = new Label(
            "Only checked populations contribute AF. Apply to annotate with their max AF.");
        popHint.getStyleClass().add("subsection-label");
        popHint.setWrapText(true);
        right.getChildren().addAll(popsHeader, popHint, popBox);

        ScrollPane leftScroll = new ScrollPane(left);
        leftScroll.setFitToWidth(true);
        leftScroll.getStyleClass().add("filter-scroll");
        ScrollPane rightScroll = new ScrollPane(right);
        rightScroll.setFitToWidth(true);
        rightScroll.getStyleClass().add("filter-scroll");

        SplitPane split = new SplitPane(leftScroll, rightScroll);
        split.setDividerPositions(0.55);
        split.getStyleClass().add("tab-content-split");
        VBox.setVgrow(split, Priority.ALWAYS);

        Button apply = new Button("Apply");
        apply.getStyleClass().add("primary-button");
        HBox applyRow = new HBox(apply);
        applyRow.setAlignment(Pos.CENTER_RIGHT);
        applyRow.getStyleClass().add("filter-panel");
        applyRow.setPadding(new Insets(8, 16, 12, 16));

        VBox root = new VBox(split, applyRow);

        Nodes built = new Nodes(
            pathLabel, addVcf, remove, filterByPop, maxFreq, selectAllPops, popBox);

        apply.setOnAction(e -> {
            Object panel = root.getUserData();
            if (panel instanceof ControlFilesPanel c) {
                c.handleApplyControlSettings();
            }
        });

        return new Pair<>(root, built);
    }
}

package org.baseplayer.variant.ui.components;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.baseplayer.components.ColoredToggle;
import org.baseplayer.components.InfoPopup;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.io.VcfManager;
import org.baseplayer.project.ProjectDocument;
import org.baseplayer.project.SessionDocumentSync;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.annotation.ClinVarAnnotator;
import org.baseplayer.variant.annotation.ClinVarAnnotator.SignificanceClass;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator.ControlFileStats;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator.PopulationAnStats;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator.PopulationOption;
import org.baseplayer.variant.annotation.SvControlAnnotator;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
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
 * Control Files tab: control AF (left) and ClinVar annotation (right).
 * Control VCF drives population AF filtering; ClinVar primarily annotates
 * {@code CLNSIG} (optional significance filter). Opening a file only loads
 * metadata; annotation runs automatically when a file is added or populations change,
 * and during chromosome annotate/load.
 */
public class ControlFilesPanel {

    private static final int STATS_SAMPLE_ROWS = 10_000;
    private static final String CLINVAR_DOWNLOAD_URL =
        "https://www.ncbi.nlm.nih.gov/clinvar/docs/downloads/";

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

    /**
     * @param controlOptionsSection AF filter + populations; shown only while a control VCF is open
     * @param clinVarOptionsSection significance filter; shown only while a ClinVar VCF is open
     * @param svBreakpointWindowField SV-only breakpoint window (bp); null on Point panel
     * @param svRequireSameTypeCheckBox SV-only same-type toggle; null on Point panel
     */
    public record Nodes(
        Label controlFilePathLabel,
        Button addControlVcfButton,
        Button removeControlFileButton,
        Node controlOptionsSection,
        ColoredToggle filterByPopFreqCheckBox,
        TextField maxPopFreqField,
        ColoredToggle selectAllPopulationsCheckBox,
        VBox populationCheckBoxContainer,
        Label clinVarPathLabel,
        Button addClinVarButton,
        Button removeClinVarButton,
        Hyperlink clinVarDownloadLink,
        Node clinVarOptionsSection,
        ColoredToggle filterByClinVarSigCheckBox,
        ColoredToggle clinVarPathogenicCheckBox,
        ColoredToggle clinVarVusCheckBox,
        ColoredToggle clinVarBenignCheckBox,
        ColoredToggle clinVarOtherCheckBox,
        TextField svBreakpointWindowField,
        ColoredToggle svRequireSameTypeCheckBox
    ) {
        /** Point-panel convenience: SV-only nodes are null. */
        public Nodes(
            Label controlFilePathLabel,
            Button addControlVcfButton,
            Button removeControlFileButton,
            Node controlOptionsSection,
            ColoredToggle filterByPopFreqCheckBox,
            TextField maxPopFreqField,
            ColoredToggle selectAllPopulationsCheckBox,
            VBox populationCheckBoxContainer,
            Label clinVarPathLabel,
            Button addClinVarButton,
            Button removeClinVarButton,
            Hyperlink clinVarDownloadLink,
            Node clinVarOptionsSection,
            ColoredToggle filterByClinVarSigCheckBox,
            ColoredToggle clinVarPathogenicCheckBox,
            ColoredToggle clinVarVusCheckBox,
            ColoredToggle clinVarBenignCheckBox,
            ColoredToggle clinVarOtherCheckBox) {
            this(
                controlFilePathLabel, addControlVcfButton, removeControlFileButton,
                controlOptionsSection, filterByPopFreqCheckBox, maxPopFreqField,
                selectAllPopulationsCheckBox, populationCheckBoxContainer,
                clinVarPathLabel, addClinVarButton, removeClinVarButton, clinVarDownloadLink,
                clinVarOptionsSection, filterByClinVarSigCheckBox, clinVarPathogenicCheckBox,
                clinVarVusCheckBox, clinVarBenignCheckBox, clinVarOtherCheckBox,
                null, null);
        }
    }

    private Nodes nodes;
    private final ControlFrequencyAnnotator annotator = new ControlFrequencyAnnotator();
    private final SvControlAnnotator svAnnotator = new SvControlAnnotator();
    private final ClinVarAnnotator clinVarAnnotator = new ClinVarAnnotator();
    private final Map<String, ColoredToggle> populationCheckBoxes = new LinkedHashMap<>();
    private final Map<String, Label> populationStatsLabels = new LinkedHashMap<>();
    /** When true, this panel owns an SV control VCF via {@link #svAnnotator}. */
    private boolean svMode;

    private Supplier<Window> windowSupplier = () -> null;
    private Supplier<List<VcfManager.CachedChromosomeVariants>> sourcesSupplier = List::of;
    /** Called after annotate/clear so the controller can rebuild tables / apply filters. */
    private Consumer<Boolean> afterAnnotate = ignored -> {};
    /** Pop-freq checkbox / max-freq field changed — apply filter without re-annotating. */
    private Runnable onFilterSettingsChanged = () -> {};
    /**
     * Optional: keep ClinVar settings on a sibling Control Files panel in sync
     * (Point ↔ SV). Control VCF path and AF settings are not mirrored.
     */
    private Consumer<ControlFilesPanel> onSettingsMirrored = ignored -> {};
    /** Suppress mirror/listener side-effects while applying programmatic UI updates. */
    private boolean suppressSettingsPersist;
    /**
     * Schedules annotation: {@code starter.accept(body)} where
     * {@code body.accept(progressSuffix)} runs the blocking annotate work.
     * When set, work is handed to the controller (background + loading modal)
     * instead of running on the FX thread.
     */
    private Consumer<Consumer<Consumer<String>>> annotateExecutor;

    public void install(Nodes nodes) {
        install(nodes, false);
    }

    public void install(Nodes nodes, boolean svMode) {
        this.nodes = nodes;
        this.svMode = svMode;
        wireButtons();
        bindImmediateFilterApply();
        refreshFileUi();
    }

    public boolean isSvMode() {
        return svMode;
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

    public SvControlAnnotator svAnnotator() {
        return svAnnotator;
    }

    public ClinVarAnnotator clinVarAnnotator() {
        return clinVarAnnotator;
    }

    public boolean isControlOpen() {
        return svMode ? svAnnotator.isOpen() : annotator.isOpen();
    }

    public Path controlPath() {
        return svMode ? svAnnotator.path() : annotator.path();
    }

    public boolean needsControlAnnotation(VariantList list) {
        return svMode ? svAnnotator.needsAnnotation(list) : annotator.needsAnnotation(list);
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
        filter.setFilterByClinVarSignificance(isFilterByClinVarSigSelected());
        filter.setAllowedClinVarClasses(selectedClinVarClasses());
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
            applyClinVarFilterUi(
                filter.isFilterByClinVarSignificance(),
                filter.getAllowedClinVarClasses());
        } finally {
            suppressSettingsPersist = previous;
        }
        refreshClinVarFilterUiEnabled();
    }

    /** Persist path + pop-freq toggles + populations into the project document. */
    public void writeToDocument(ProjectDocument.ControlFilesSpec spec) {
        if (spec == null) {
            return;
        }
        if (svMode) {
            Path open = svAnnotator.path();
            spec.svControlVcfPath =
                open != null ? open.toAbsolutePath().normalize().toString() : null;
            spec.svFilterByControlAf = isFilterByPopFreqSelected();
            spec.svMaxControlAf = readMaxPopFreq();
            String pops = svAnnotator.selectedPopulationsPersistString();
            spec.svControlPopulation = pops == null || pops.isBlank() ? null : pops;
            spec.svBreakpointWindowBp = readBreakpointWindowBp();
            spec.svRequireSameType = isRequireSameTypeSelected();
        } else {
            Path open = annotator.path();
            spec.controlVcfPath =
                open != null ? open.toAbsolutePath().normalize().toString() : null;
            spec.filterByControlAf = isFilterByPopFreqSelected();
            spec.maxControlAf = readMaxPopFreq();
            String pops = annotator.selectedPopulationsPersistString();
            spec.controlPopulation = pops == null || pops.isBlank() ? null : pops;
        }
        Path clinVar = clinVarAnnotator.path();
        spec.clinVarVcfPath = clinVar != null ? clinVar.toAbsolutePath().normalize().toString() : null;
        spec.filterByClinVarSignificance = isFilterByClinVarSigSelected();
        spec.clinVarSignificanceClasses = persistClinVarClasses(selectedClinVarClasses());
        // Legacy booleans for older readers.
        org.baseplayer.project.LegacyClinVarCompat.writeControlCompat(
            spec,
            spec.filterByClinVarSignificance,
            selectedClinVarClasses().equals(EnumSet.of(SignificanceClass.PATHOGENIC))
                ? "PATHOGENIC"
                : spec.clinVarSignificanceClasses);
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
            boolean filterBy = svMode ? spec.svFilterByControlAf : spec.filterByControlAf;
            double maxAf = svMode ? spec.svMaxControlAf : spec.maxControlAf;
            if (nodes.filterByPopFreqCheckBox() != null) {
                nodes.filterByPopFreqCheckBox().setSelected(filterBy);
            }
            if (nodes.maxPopFreqField() != null) {
                nodes.maxPopFreqField().setText(String.valueOf(maxAf > 0 ? maxAf : 0.01));
            }
            if (svMode) {
                applySvMatchSettingsUi(
                    spec.svBreakpointWindowBp > 0
                        ? spec.svBreakpointWindowBp
                        : SvControlAnnotator.DEFAULT_BREAKPOINT_WINDOW_BP,
                    spec.svRequireSameType);
            }
            applyClinVarFilterUi(
                resolveFilterByClinVar(spec),
                resolveClinVarClasses(spec));
            restoreClinVarPath(spec.clinVarVcfPath);
            String pathText = svMode ? spec.svControlVcfPath : spec.controlVcfPath;
            if (pathText == null || pathText.isBlank()) {
                refreshPopulations(null, false);
                refreshFileUi();
                return clinVarAnnotator.isOpen();
            }
            Path path = Path.of(pathText);
            if (!java.nio.file.Files.isRegularFile(path)) {
                refreshPopulations(null, false);
                refreshFileUi();
                return clinVarAnnotator.isOpen();
            }
            try {
                if (svMode) {
                    svAnnotator.open(path);
                    syncSvMatchSettingsToAnnotator();
                } else {
                    annotator.open(path);
                }
            } catch (IOException e) {
                System.err.println("Could not restore control VCF: " + e.getMessage());
                refreshFileUi();
                return clinVarAnnotator.isOpen();
            }
            String popPersist = svMode ? spec.svControlPopulation : spec.controlPopulation;
            List<String> preferred =
                ControlFrequencyAnnotator.parsePopulationsPersistString(popPersist);
            refreshPopulations(preferred, false);
            refreshFileUi();
            refreshStatsAsync();
            return true;
        } finally {
            suppressSettingsPersist = previous;
        }
    }

    private void restoreClinVarPath(String pathText) {
        if (pathText == null || pathText.isBlank()) {
            clinVarAnnotator.close();
            return;
        }
        Path path = Path.of(pathText);
        if (!java.nio.file.Files.isRegularFile(path)) {
            clinVarAnnotator.close();
            return;
        }
        try {
            clinVarAnnotator.open(path);
        } catch (IOException e) {
            System.err.println("Could not restore ClinVar VCF: " + e.getMessage());
            clinVarAnnotator.close();
        }
    }

    /**
     * Copy ClinVar path + significance filter from another panel without persisting.
     * Control VCF / AF / SV window settings stay independent per mode.
     */
    public void mirrorClinVarSettingsFrom(ControlFilesPanel other) {
        if (other == null || other == this || nodes == null) {
            return;
        }
        boolean previous = suppressSettingsPersist;
        suppressSettingsPersist = true;
        try {
            applyClinVarFilterUi(
                other.isFilterByClinVarSigSelected(),
                other.selectedClinVarClasses());
            if (other.clinVarAnnotator.isOpen() && other.clinVarAnnotator.path() != null) {
                Path path = other.clinVarAnnotator.path();
                if (!clinVarAnnotator.isOpen() || !path.equals(clinVarAnnotator.path())) {
                    try {
                        clinVarAnnotator.open(path);
                    } catch (IOException e) {
                        System.err.println("Could not mirror ClinVar VCF: " + e.getMessage());
                    }
                }
            } else if (!other.clinVarAnnotator.isOpen() && clinVarAnnotator.isOpen()) {
                clinVarAnnotator.close();
            }
            refreshFileUi();
        } finally {
            suppressSettingsPersist = previous;
        }
    }

    /** @deprecated use {@link #mirrorClinVarSettingsFrom(ControlFilesPanel)} */
    @Deprecated
    public void mirrorFilterSettingsFrom(ControlFilesPanel other) {
        mirrorClinVarSettingsFrom(other);
    }

    private boolean isFilterByPopFreqSelected() {
        return nodes != null
            && nodes.filterByPopFreqCheckBox() != null
            && nodes.filterByPopFreqCheckBox().isSelected();
    }

    private boolean isFilterByClinVarSigSelected() {
        return nodes != null
            && nodes.filterByClinVarSigCheckBox() != null
            && nodes.filterByClinVarSigCheckBox().isSelected();
    }

    private EnumSet<SignificanceClass> selectedClinVarClasses() {
        EnumSet<SignificanceClass> set = EnumSet.noneOf(SignificanceClass.class);
        if (nodes == null) {
            return set;
        }
        if (nodes.clinVarPathogenicCheckBox() != null && nodes.clinVarPathogenicCheckBox().isSelected()) {
            set.add(SignificanceClass.PATHOGENIC);
        }
        if (nodes.clinVarVusCheckBox() != null && nodes.clinVarVusCheckBox().isSelected()) {
            set.add(SignificanceClass.VUS);
        }
        if (nodes.clinVarBenignCheckBox() != null && nodes.clinVarBenignCheckBox().isSelected()) {
            set.add(SignificanceClass.BENIGN);
        }
        if (nodes.clinVarOtherCheckBox() != null && nodes.clinVarOtherCheckBox().isSelected()) {
            set.add(SignificanceClass.OTHER);
        }
        return set;
    }

    private void applyClinVarFilterUi(boolean filterOn, Set<SignificanceClass> classes) {
        if (nodes == null) {
            return;
        }
        if (nodes.filterByClinVarSigCheckBox() != null) {
            nodes.filterByClinVarSigCheckBox().setSelected(filterOn);
        }
        Set<SignificanceClass> want = classes != null ? classes : EnumSet.noneOf(SignificanceClass.class);
        if (want.isEmpty()) {
            want = EnumSet.allOf(SignificanceClass.class);
        }
        if (nodes.clinVarPathogenicCheckBox() != null) {
            nodes.clinVarPathogenicCheckBox().setSelected(want.contains(SignificanceClass.PATHOGENIC));
        }
        if (nodes.clinVarVusCheckBox() != null) {
            nodes.clinVarVusCheckBox().setSelected(want.contains(SignificanceClass.VUS));
        }
        if (nodes.clinVarBenignCheckBox() != null) {
            nodes.clinVarBenignCheckBox().setSelected(want.contains(SignificanceClass.BENIGN));
        }
        if (nodes.clinVarOtherCheckBox() != null) {
            nodes.clinVarOtherCheckBox().setSelected(want.contains(SignificanceClass.OTHER));
        }
        refreshClinVarFilterUiEnabled();
    }

    private void refreshClinVarFilterUiEnabled() {
        if (nodes == null) {
            return;
        }
        boolean on = isFilterByClinVarSigSelected();
        if (nodes.clinVarPathogenicCheckBox() != null) {
            nodes.clinVarPathogenicCheckBox().setDisable(!on);
        }
        if (nodes.clinVarVusCheckBox() != null) {
            nodes.clinVarVusCheckBox().setDisable(!on);
        }
        if (nodes.clinVarBenignCheckBox() != null) {
            nodes.clinVarBenignCheckBox().setDisable(!on);
        }
        if (nodes.clinVarOtherCheckBox() != null) {
            nodes.clinVarOtherCheckBox().setDisable(!on);
        }
    }

    private static boolean resolveFilterByClinVar(ProjectDocument.ControlFilesSpec spec) {
        if (spec == null) {
            return false;
        }
        return spec.filterByClinVarSignificance
            || org.baseplayer.project.LegacyClinVarCompat.legacyRequire(spec)
            || org.baseplayer.project.LegacyClinVarCompat.legacyPathogenic(spec);
    }

    private static EnumSet<SignificanceClass> resolveClinVarClasses(
            ProjectDocument.ControlFilesSpec spec) {
        EnumSet<SignificanceClass> parsed = parseClinVarClasses(
            spec != null ? spec.clinVarSignificanceClasses : null);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        if (spec != null
                && org.baseplayer.project.LegacyClinVarCompat.legacyPathogenic(spec)
                && !org.baseplayer.project.LegacyClinVarCompat.legacyRequire(spec)) {
            return EnumSet.of(SignificanceClass.PATHOGENIC);
        }
        return EnumSet.allOf(SignificanceClass.class);
    }

    public static EnumSet<SignificanceClass> parseClinVarClasses(String persist) {
        EnumSet<SignificanceClass> set = EnumSet.noneOf(SignificanceClass.class);
        if (persist == null || persist.isBlank()) {
            return set;
        }
        for (String part : persist.split(",")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            try {
                set.add(SignificanceClass.valueOf(part.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // skip unknown
            }
        }
        return set;
    }

    static String persistClinVarClasses(Set<SignificanceClass> classes) {
        if (classes == null || classes.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (SignificanceClass c : SignificanceClass.values()) {
            if (!classes.contains(c)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(c.name());
        }
        return sb.length() == 0 ? null : sb.toString();
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

    private int readBreakpointWindowBp() {
        int fallback = SvControlAnnotator.DEFAULT_BREAKPOINT_WINDOW_BP;
        if (nodes == null || nodes.svBreakpointWindowField() == null) {
            return fallback;
        }
        String text = nodes.svBreakpointWindowField().getText();
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean isRequireSameTypeSelected() {
        return nodes == null
            || nodes.svRequireSameTypeCheckBox() == null
            || nodes.svRequireSameTypeCheckBox().isSelected();
    }

    private void applySvMatchSettingsUi(int windowBp, boolean requireSameType) {
        if (nodes == null) {
            return;
        }
        if (nodes.svBreakpointWindowField() != null) {
            nodes.svBreakpointWindowField().setText(String.valueOf(Math.max(0, windowBp)));
        }
        if (nodes.svRequireSameTypeCheckBox() != null) {
            nodes.svRequireSameTypeCheckBox().setSelected(requireSameType);
        }
        syncSvMatchSettingsToAnnotator();
    }

    private void syncSvMatchSettingsToAnnotator() {
        if (!svMode) {
            return;
        }
        svAnnotator.setBreakpointWindowBp(readBreakpointWindowBp());
        svAnnotator.setRequireSameType(isRequireSameTypeSelected());
    }

    /** Re-annotate all loaded chromosome caches (control AF and/or ClinVar). */
    public void annotateNow() {
        syncSelectedPopulationsFromUi();
        syncSvMatchSettingsToAnnotator();
        runAnnotateLoadedSources(true);
    }

    public void handleAddControlVcf() {
        if (nodes == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(svMode
            ? "Add SV control VCF"
            : "Add control VCF (gnomAD sites)");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Compressed VCF", "*.vcf.gz"),
            new FileChooser.ExtensionFilter("VCF", "*.vcf", "*.vcf.gz"));
        String dirKey = svMode ? "SV_CONTROL_VCF" : "CONTROL_VCF";
        File initialDir = UserPreferences.getLastDirectory(dirKey);
        if (initialDir == null) {
            Path open = controlPath();
            if (open != null && open.getParent() != null) {
                initialDir = open.getParent().toFile();
            }
        }
        if (initialDir == null) {
            initialDir = UserPreferences.getLastDirectory(svMode ? "CONTROL_VCF" : "VCF");
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
        UserPreferences.setLastDirectory(dirKey, chosen.getParentFile());
        Path path = chosen.toPath();
        try {
            if (svMode) {
                svAnnotator.open(path);
                syncSvMatchSettingsToAnnotator();
            } else {
                annotator.open(path);
            }
        } catch (IOException e) {
            showError("Could not open control VCF", e.getMessage());
            return;
        }
        // Select all populations by default, then annotate loaded chromosomes.
        refreshPopulations(null, true);
        refreshFileUi();
        String abs = path.toAbsolutePath().normalize().toString();
        if (svMode) {
            SessionDocumentSync.writeSvControlVcfPath(abs);
        } else {
            SessionDocumentSync.writeControlVcfPath(abs);
        }
        persistFilterSettingsToDocument();
        refreshStatsAsync();
        onSettingsMirrored.accept(this);
        annotateNow();
    }

    public void handleRemoveControlFile() {
        if (svMode) {
            svAnnotator.close();
            clearControlAfOnSources();
            SessionDocumentSync.writeSvControlVcfPath(null);
        } else {
            annotator.close();
            clearControlAfOnSources();
            SessionDocumentSync.writeControlVcfPath(null);
        }
        refreshPopulations(null, false);
        refreshFileUi();
        persistFilterSettingsToDocument();
        onSettingsMirrored.accept(this);
        afterAnnotate.accept(true);
    }

    public void handleAddClinVarVcf() {
        if (nodes == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add ClinVar VCF");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Compressed VCF", "*.vcf.gz"),
            new FileChooser.ExtensionFilter("VCF", "*.vcf", "*.vcf.gz"));
        File initialDir = UserPreferences.getLastDirectory("CLINVAR_VCF");
        if (initialDir == null) {
            Path open = clinVarAnnotator.path();
            if (open != null && open.getParent() != null) {
                initialDir = open.getParent().toFile();
            }
        }
        if (initialDir == null) {
            initialDir = UserPreferences.getLastDirectory("CONTROL_VCF");
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
        UserPreferences.setLastDirectory("CLINVAR_VCF", chosen.getParentFile());
        Path path = chosen.toPath();
        try {
            clinVarAnnotator.open(path);
        } catch (IOException e) {
            showError("Could not open ClinVar VCF", e.getMessage());
            return;
        }
        refreshFileUi();
        SessionDocumentSync.writeClinVarVcfPath(path.toAbsolutePath().normalize().toString());
        persistClinVarFilterSettingsToDocument();
        onSettingsMirrored.accept(this);
        annotateNow();
    }

    public void handleRemoveClinVarFile() {
        clinVarAnnotator.close();
        clearClinVarOnSources();
        refreshFileUi();
        SessionDocumentSync.writeClinVarVcfPath(null);
        persistClinVarFilterSettingsToDocument();
        onSettingsMirrored.accept(this);
        afterAnnotate.accept(true);
    }

    public void handleOpenClinVarDownloads() {
        InfoPopup.openUrl(CLINVAR_DOWNLOAD_URL);
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
        if (nodes.addClinVarButton() != null) {
            nodes.addClinVarButton().setOnAction(e -> handleAddClinVarVcf());
        }
        if (nodes.removeClinVarButton() != null) {
            nodes.removeClinVarButton().setOnAction(e -> handleRemoveClinVarFile());
        }
        if (nodes.clinVarDownloadLink() != null) {
            nodes.clinVarDownloadLink().setOnAction(e -> handleOpenClinVarDownloads());
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
        if (nodes.svBreakpointWindowField() != null) {
            nodes.svBreakpointWindowField().textProperty().addListener(
                (obs, oldText, newText) -> onSvMatchSettingsChanged());
        }
        if (nodes.svRequireSameTypeCheckBox() != null) {
            nodes.svRequireSameTypeCheckBox().selectedProperty().addListener(
                (obs, was, is) -> onSvMatchSettingsChanged());
        }
        if (nodes.filterByClinVarSigCheckBox() != null) {
            nodes.filterByClinVarSigCheckBox().selectedProperty().addListener((obs, was, is) -> {
                if (Boolean.TRUE.equals(is) && selectedClinVarClasses().isEmpty()) {
                    boolean previous = suppressSettingsPersist;
                    suppressSettingsPersist = true;
                    try {
                        applyClinVarFilterUi(true, EnumSet.allOf(SignificanceClass.class));
                    } finally {
                        suppressSettingsPersist = previous;
                    }
                }
                refreshClinVarFilterUiEnabled();
                onClinVarFilterUiChanged();
            });
        }
        for (ColoredToggle cb : new ColoredToggle[] {
            nodes.clinVarPathogenicCheckBox(),
            nodes.clinVarVusCheckBox(),
            nodes.clinVarBenignCheckBox(),
            nodes.clinVarOtherCheckBox()
        }) {
            if (cb != null) {
                cb.selectedProperty().addListener((obs, was, is) -> onClinVarFilterUiChanged());
            }
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

    /** Window / same-type changes require re-annotation (they affect who gets AF). */
    private void onSvMatchSettingsChanged() {
        if (suppressSettingsPersist || !svMode) {
            return;
        }
        syncSvMatchSettingsToAnnotator();
        persistFilterSettingsToDocument();
        onSettingsMirrored.accept(this);
        if (svAnnotator.isOpen()) {
            annotateNow();
        }
    }

    private void onClinVarFilterUiChanged() {
        if (suppressSettingsPersist) {
            return;
        }
        persistClinVarFilterSettingsToDocument();
        onSettingsMirrored.accept(this);
        onFilterSettingsChanged.run();
    }

    public void persistClinVarFilterSettingsToDocument() {
        SessionDocumentSync.writeClinVarFilterSettings(
            isFilterByClinVarSigSelected(),
            persistClinVarClasses(selectedClinVarClasses()));
    }

    private void onPopulationSelectionChanged() {
        if (suppressSettingsPersist) {
            return;
        }
        syncSelectedPopulationsFromUi();
        syncSelectAllCheckbox();
        if (svMode) {
            SessionDocumentSync.writeSvControlPopulation(
                svAnnotator.selectedPopulationsPersistString());
        } else {
            SessionDocumentSync.writeControlPopulation(
                annotator.selectedPopulationsPersistString());
        }
        persistFilterSettingsToDocument();
        onSettingsMirrored.accept(this);
        annotateNow();
    }

    private void syncSelectedPopulationsFromUi() {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (Map.Entry<String, ColoredToggle> e : populationCheckBoxes.entrySet()) {
            if (e.getValue() != null && e.getValue().isSelected()) {
                selected.add(e.getKey());
            }
        }
        if (svMode) {
            svAnnotator.setSelectedPopulations(selected);
        } else {
            annotator.setSelectedPopulations(selected);
        }
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
        if (svMode) {
            SessionDocumentSync.writeSvControlFilterSettings(
                isFilterByPopFreqSelected(),
                readMaxPopFreq(),
                svAnnotator.selectedPopulationsPersistString(),
                readBreakpointWindowBp(),
                isRequireSameTypeSelected());
        } else {
            SessionDocumentSync.writeControlFilterSettings(
                isFilterByPopFreqSelected(),
                readMaxPopFreq(),
                annotator.selectedPopulationsPersistString());
        }
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
        boolean hasControl = isControlOpen();
        boolean hasClinVar = clinVarAnnotator.isOpen();
        if (!hasControl && !hasClinVar) {
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
        if (svMode) {
            syncSvMatchSettingsToAnnotator();
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
                // Soft annotate: each source skips when its fingerprint already matches
                // (adding gnomAD must not redo ClinVar, and vice versa).
                if (hasControl) {
                    annotateControlList(cached.variants(), chromIndex, total, progressSuffix);
                }
                if (hasClinVar) {
                    clinVarAnnotator.annotate(cached.variants(), (c, cur, tot, matched) -> {
                        reportControlDetail(
                            "ClinVar", c, cur, tot, matched, chromIndex, total, progressSuffix);
                    }, false);
                }
            } catch (IOException e) {
                // May run on ThreadRunner loader-pool — never build Alert here.
                showError("Control annotation failed", chrom + ": " + e.getMessage());
                break;
            }
        }
        org.baseplayer.services.LoadingManager.get().clearDetail();
        ThreadRunner.RunnerTask active = currentRunnerTask();
        if (active != null) {
            active.setDetailMessage("");
            ThreadRunner.get().notifyDescriptionChanged();
        }
        if (notify) {
            afterAnnotate.accept(true);
        }
    }

    private void annotateControlList(
            VariantList list, int chromIndex, int chromTotal, Consumer<String> progressSuffix)
            throws IOException {
        String kind = svMode ? "SV control AF" : "Control AF";
        ControlFrequencyAnnotator.ProgressListener progress = (c, cur, tot, matched) ->
            reportControlDetail(kind, c, cur, tot, matched, chromIndex, chromTotal, progressSuffix);
        if (svMode) {
            svAnnotator.annotate(list, progress, false);
        } else {
            annotator.annotate(list, progress, false);
        }
    }

    /**
     * Annotate control AF only (no ClinVar). Used when Point and SV panels both run
     * in one job and ClinVar is handled once by the Point panel.
     */
    public void annotateControlAfLoadedSources(Consumer<String> progressSuffix) {
        if (!isControlOpen()) {
            return;
        }
        if (svMode) {
            syncSvMatchSettingsToAnnotator();
        }
        List<VcfManager.CachedChromosomeVariants> sources = sourcesSupplier.get();
        if (sources == null || sources.isEmpty()) {
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
                annotateControlList(cached.variants(), chromIndex, total, progressSuffix);
            } catch (IOException e) {
                showError("Control annotation failed", chrom + ": " + e.getMessage());
                break;
            }
        }
    }

    /** Annotate control AF on one list only (no ClinVar). */
    public void annotateControlAfIfOpen(VariantList list, Consumer<String> progressSuffix) {
        if (list == null || !isControlOpen()) {
            return;
        }
        try {
            if (svMode) {
                syncSvMatchSettingsToAnnotator();
            }
            annotateControlList(list, -1, -1, progressSuffix);
        } catch (IOException e) {
            System.err.println("Control AF annotation failed for "
                + list.getChromosome() + ": " + e.getMessage());
        }
    }

    /** Annotate one list quietly (e.g. after gene annotation finishes). */
    public void annotateIfOpen(VariantList list) {
        annotateIfOpen(list, null);
    }

    public void annotateIfOpen(VariantList list, Consumer<String> progressSuffix) {
        if (list == null) {
            return;
        }
        if (isControlOpen()) {
            try {
                if (svMode) {
                    syncSvMatchSettingsToAnnotator();
                }
                annotateControlList(list, -1, -1, progressSuffix);
            } catch (IOException e) {
                System.err.println("Control AF annotation failed for "
                    + list.getChromosome() + ": " + e.getMessage());
            }
        }
        if (clinVarAnnotator.isOpen()) {
            try {
                clinVarAnnotator.annotate(list, (c, cur, tot, matched) -> {
                    reportControlDetail("ClinVar", c, cur, tot, matched, -1, -1, progressSuffix);
                }, false);
            } catch (IOException e) {
                System.err.println("ClinVar annotation failed for "
                    + list.getChromosome() + ": " + e.getMessage());
            }
        }
        org.baseplayer.services.LoadingManager.get().clearDetail();
        ThreadRunner.RunnerTask active = currentRunnerTask();
        if (active != null) {
            active.setDetailMessage("");
            org.baseplayer.services.ThreadRunner.get().notifyDescriptionChanged();
        }
    }

    private static void reportControlDetail(
            String kind,
            String chrom,
            int cur,
            int tot,
            int matched,
            int chromIndex,
            int chromTotal,
            Consumer<String> progressSuffix) {
        int safeTot = Math.max(1, tot);
        org.baseplayer.services.LoadingManager.get().setDetailProgress(cur, safeTot);
        String detail = kind + " chr" + chrom
            + " — " + matched + " matched (" + cur + "/" + safeTot + ")";
        if (chromIndex > 0 && chromTotal > 0) {
            detail = "(" + chromIndex + "/" + chromTotal + ") " + detail;
        }
        org.baseplayer.services.LoadingManager.get().setDetailMessage(detail);
        ThreadRunner.RunnerTask active = currentRunnerTask();
        if (active != null) {
            active.setDetailMessage(detail);
            org.baseplayer.services.ThreadRunner.get().notifyDescriptionChanged();
        }
        if (progressSuffix != null) {
            progressSuffix.accept(detail);
        }
    }

    private static ThreadRunner.RunnerTask currentRunnerTask() {
        List<ThreadRunner.RunnerTask> tasks =
            org.baseplayer.services.ThreadRunner.get().getActiveTasks();
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }
        return tasks.get(tasks.size() - 1);
    }

    private void clearControlAfOnSources() {
        List<VcfManager.CachedChromosomeVariants> sources = sourcesSupplier.get();
        if (sources == null) {
            return;
        }
        for (VcfManager.CachedChromosomeVariants cached : sources) {
            if (cached == null) {
                continue;
            }
            if (svMode) {
                SvControlAnnotator.clear(cached.variants());
            } else {
                ControlFrequencyAnnotator.clear(cached.variants());
            }
        }
    }

    private void clearClinVarOnSources() {
        List<VcfManager.CachedChromosomeVariants> sources = sourcesSupplier.get();
        if (sources == null) {
            return;
        }
        for (VcfManager.CachedChromosomeVariants cached : sources) {
            if (cached != null) {
                ClinVarAnnotator.clear(cached.variants());
            }
        }
    }

    private void refreshFileUi() {
        if (nodes == null) {
            return;
        }
        Path open = controlPath();
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
        boolean hasControl = open != null;
        setSectionShown(nodes.controlOptionsSection(), hasControl);
        if (nodes.selectAllPopulationsCheckBox() != null) {
            nodes.selectAllPopulationsCheckBox().setDisable(
                !hasControl || populationCheckBoxes.isEmpty());
        }
        if (nodes.populationCheckBoxContainer() != null) {
            nodes.populationCheckBoxContainer().setDisable(!hasControl);
        }
        Path clinVar = clinVarAnnotator.path();
        boolean hasClinVar = clinVar != null;
        if (nodes.clinVarPathLabel() != null) {
            if (hasClinVar) {
                nodes.clinVarPathLabel().setText(clinVar.toAbsolutePath().normalize().toString());
            } else {
                nodes.clinVarPathLabel().setText("No ClinVar file loaded");
            }
        }
        if (nodes.removeClinVarButton() != null) {
            nodes.removeClinVarButton().setDisable(!hasClinVar);
        }
        setSectionShown(nodes.clinVarOptionsSection(), hasClinVar);
        refreshClinVarFilterUiEnabled();
    }

    private static void setSectionShown(Node section, boolean shown) {
        if (section == null) {
            return;
        }
        section.setVisible(shown);
        section.setManaged(shown);
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

            if (!isControlOpen()) {
                if (svMode) {
                    svAnnotator.setSelectedPopulations(
                        List.of(ControlFrequencyAnnotator.GLOBAL_POPULATION));
                } else {
                    annotator.setSelectedPopulations(
                        List.of(ControlFrequencyAnnotator.GLOBAL_POPULATION));
                }
                syncSelectAllCheckbox();
                return;
            }

            List<PopulationOption> options =
                svMode ? svAnnotator.listPopulations() : annotator.listPopulations();
            Set<String> want;
            if (preferredIds != null) {
                want = new LinkedHashSet<>(preferredIds);
            } else if (selectAllDefault) {
                want = new LinkedHashSet<>();
                for (PopulationOption opt : options) {
                    want.add(opt.id());
                }
            } else {
                want = new LinkedHashSet<>(
                    svMode ? svAnnotator.selectedPopulations() : annotator.selectedPopulations());
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
        if (!isControlOpen()) {
            setAllPopulationStatsText("—");
            return;
        }
        setAllPopulationStatsText("…");
        Path path = controlPath();
        final boolean peekSv = svMode;
        Thread t = new Thread(() -> {
            ControlFileStats stats;
            try {
                if (peekSv) {
                    try (SvControlAnnotator peek = new SvControlAnnotator()) {
                        peek.open(path);
                        stats = peek.sampleStats(STATS_SAMPLE_ROWS);
                    }
                } else {
                    try (ControlFrequencyAnnotator peek = new ControlFrequencyAnnotator()) {
                        peek.open(path);
                        stats = peek.sampleStats(STATS_SAMPLE_ROWS);
                    }
                }
            } catch (IOException e) {
                Platform.runLater(() -> setAllPopulationStatsText("n/a"));
                return;
            }
            ControlFileStats finalStats = stats;
            Platform.runLater(() -> {
                if (!isControlOpen() || path == null || !path.equals(controlPath())) {
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

        TextField windowField = new TextField(
            String.valueOf(SvControlAnnotator.DEFAULT_BREAKPOINT_WINDOW_BP));
        windowField.setPrefWidth(80);
        windowField.getStyleClass().add("filter-field");
        windowField.setTooltip(new Tooltip(
            "Both breakpoints must fall within ±window bp to count as the same event "
                + "(INS: start only)."));
        ColoredToggle requireSameType = new ColoredToggle("Require same SV type");
        requireSameType.setSelected(true);
        requireSameType.setTooltip(new Tooltip(
            "When on, DEL only matches DEL (etc.). When off, any SVTYPE counts if "
                + "breakpoints match."));

        ColoredToggle selectAllPops = new ColoredToggle();
        selectAllPops.setSelected(true);
        selectAllPops.setDisable(true);
        Label popsLabel = new Label("Populations");
        popsLabel.getStyleClass().add("section-header");
        HBox popsHeader = new HBox(6, selectAllPops, popsLabel);
        popsHeader.setAlignment(Pos.CENTER_LEFT);

        VBox popBox = new VBox(4);
        popBox.setDisable(true);

        Label clinVarPathLabel = new Label("No ClinVar file loaded");
        clinVarPathLabel.getStyleClass().add("value-label");
        clinVarPathLabel.setWrapText(true);
        Button addClinVar = new Button("Add ClinVar VCF");
        addClinVar.getStyleClass().add("secondary-button");
        Button removeClinVar = new Button("Remove");
        removeClinVar.getStyleClass().add("secondary-button");
        removeClinVar.setDisable(true);
        Hyperlink clinVarDownload = new Hyperlink("ClinVar downloads (NCBI)");
        ColoredToggle filterByClinVarSig = new ColoredToggle("Filter by ClinVar significance");
        ColoredToggle clinVarPathogenic =
            new ColoredToggle(SignificanceClass.PATHOGENIC.label());
        clinVarPathogenic.setSelected(true);
        clinVarPathogenic.setDisable(true);
        ColoredToggle clinVarVus = new ColoredToggle(SignificanceClass.VUS.label());
        clinVarVus.setSelected(true);
        clinVarVus.setDisable(true);
        ColoredToggle clinVarBenign = new ColoredToggle(SignificanceClass.BENIGN.label());
        clinVarBenign.setSelected(true);
        clinVarBenign.setDisable(true);
        ColoredToggle clinVarOther = new ColoredToggle(SignificanceClass.OTHER.label());
        clinVarOther.setSelected(true);
        clinVarOther.setDisable(true);

        VBox left = new VBox(12);
        left.getStyleClass().add("filter-panel");
        left.setPadding(new Insets(16));

        Label fileTitle = new Label("SV control file (allele frequency)");
        fileTitle.getStyleClass().add("section-header");
        HBox fileButtons = new HBox(8, addVcf, remove);
        fileButtons.setAlignment(Pos.CENTER_LEFT);

        Label matchTitle = new Label("Breakpoint matching");
        matchTitle.getStyleClass().add("section-header");
        Label matchHint = new Label(
            "Both breakpoints must match within ±window bp (INS: start only).");
        matchHint.getStyleClass().add("subsection-label");
        matchHint.setWrapText(true);
        Label windowLabel = new Label("Window (bp):");
        windowLabel.getStyleClass().add("subsection-label");
        HBox windowRow = new HBox(8, windowLabel, windowField);
        windowRow.setAlignment(Pos.CENTER_LEFT);

        Label filterTitle = new Label("Allele frequency filter");
        filterTitle.getStyleClass().add("section-header");
        HBox filterRow = new HBox(8, filterByPop,
            new Label("Max AF:") {{ getStyleClass().add("subsection-label"); }},
            maxFreq);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        Label popHint = new Label(
            "Only checked populations contribute AF. Annotation runs automatically.");
        popHint.getStyleClass().add("subsection-label");
        popHint.setWrapText(true);

        VBox controlOptions = new VBox(12,
            new javafx.scene.control.Separator(),
            matchTitle, matchHint, windowRow, requireSameType,
            new javafx.scene.control.Separator(),
            filterTitle, filterRow,
            new javafx.scene.control.Separator(),
            popsHeader, popHint, popBox);
        controlOptions.setVisible(false);
        controlOptions.setManaged(false);

        left.getChildren().addAll(fileTitle, pathLabel, fileButtons, controlOptions);

        VBox right = new VBox(12);
        right.getStyleClass().add("filter-panel");
        right.setPadding(new Insets(16));
        Label clinVarTitle = new Label("ClinVar annotation");
        clinVarTitle.getStyleClass().add("section-header");
        Label clinVarHint = new Label(
            "Load a ClinVar VCF to annotate matches with clinical significance. "
                + "Filtering by significance is optional.");
        clinVarHint.getStyleClass().add("subsection-label");
        clinVarHint.setWrapText(true);
        HBox clinVarButtons = new HBox(8, addClinVar, removeClinVar);
        clinVarButtons.setAlignment(Pos.CENTER_LEFT);
        Label clinVarFilterTitle = new Label("Optional significance filter");
        clinVarFilterTitle.getStyleClass().add("section-header");
        VBox clinVarOptions = new VBox(12,
            new javafx.scene.control.Separator(),
            clinVarFilterTitle, filterByClinVarSig,
            clinVarPathogenic, clinVarVus, clinVarBenign, clinVarOther);
        clinVarOptions.setVisible(false);
        clinVarOptions.setManaged(false);
        right.getChildren().addAll(
            clinVarTitle, clinVarHint, clinVarPathLabel, clinVarButtons, clinVarDownload,
            clinVarOptions);

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

        VBox root = new VBox(split);
        VBox.setVgrow(split, Priority.ALWAYS);

        Nodes built = new Nodes(
            pathLabel, addVcf, remove, controlOptions,
            filterByPop, maxFreq, selectAllPops, popBox,
            clinVarPathLabel, addClinVar, removeClinVar, clinVarDownload, clinVarOptions,
            filterByClinVarSig, clinVarPathogenic, clinVarVus, clinVarBenign, clinVarOther,
            windowField, requireSameType);

        return new Pair<>(root, built);
    }
}

package org.baseplayer.variant.ui.components;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

import org.baseplayer.io.VcfManager;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.annotation.VariantEffect;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Pair;

/**
 * Variant Filters tab UI: type/effect checkboxes, quality/depth/AF sliders,
 * cancer filter, advanced INFO/FILTER rules, and reload banner.
 */
public class VariantFiltersPanel {

    /** Whether a higher or lower current value than the snapshot means a looser filter. */
    public enum LooserWhen {
        /** Min thresholds (Q/DP/AF): lowering admits more variants. */
        CURRENT_LOWER,
        /** Max thresholds: raising admits more variants. */
        CURRENT_HIGHER
    }

    private record ThresholdFilter(
        String key,
        DoubleSupplier currentValue,
        ToDoubleFunction<VariantFilter> snapshotFromLoaded,
        LooserWhen looserWhen
    ) {}

    /**
     * Allowed-set filter (types, effects, …). Looser when the current set contains any
     * value that was not in the load-time snapshot (current ⊈ snapshot).
     */
    private record SetFilter(
        String key,
        Supplier<Set<?>> currentValue,
        Function<VariantFilter, Set<?>> snapshotFromLoaded
    ) {}

    /**
     * Boolean constraint that restricts the set when true (e.g. cancer-genes-only).
     * Looser when snapshot was true and current is false.
     */
    private record FlagFilter(
        String key,
        BooleanSupplier currentValue,
        java.util.function.Predicate<VariantFilter> snapshotFromLoaded
    ) {}

    public record Nodes(
        GridPane variantTypesContainer,
        CheckBox selectAllTypesCheckBox,
        CheckBox selectAllEffectsCheckBox,
        CheckBox missenseCheckBox,
        CheckBox synonymousCheckBox,
        CheckBox stopFrameshiftCheckBox,
        CheckBox spliceSiteCheckBox,
        CheckBox utrCheckBox,
        CheckBox noncodingCheckBox,
        CheckBox intronicCheckBox,
        CheckBox intergenicCheckBox,
        Slider qualitySlider,
        Slider coverageSlider,
        Slider alleleFreqSlider,
        TextField qualityField,
        TextField coverageField,
        TextField alleleFreqField,
        Label qualityValueLabel,
        Label coverageValueLabel,
        Label alleleFreqValueLabel,
        CheckBox cancerOnlyCheckBox,
        VBox advancedFiltersContainer,
        Button addInfoFilterButton,
        Button addFilterFieldButton,
        HBox reloadBanner,
        Label reloadBannerLabel,
        Button reloadBannerButton
    ) {}

    private Nodes nodes;
    private Runnable onDebouncedChange;
    private Runnable onImmediateChange;
    private BooleanSupplier isSuppressing;

    private boolean localSuppress;
    private final Map<VcfVariantType, CheckBox> variantTypeCheckBoxes = new HashMap<>();
    private final List<ThresholdFilter> thresholdFilters = new ArrayList<>();
    private final List<SetFilter> setFilters = new ArrayList<>();
    private final List<FlagFilter> flagFilters = new ArrayList<>();
    private final Map<String, Double> filtersSnapshotHash = new HashMap<>();
    private final Map<String, Set<?>> setFiltersSnapshotHash = new HashMap<>();
    private final Map<String, Boolean> flagFiltersSnapshotHash = new HashMap<>();

    public void install(
            Nodes nodes,
            Runnable onDebouncedChange,
            Runnable onImmediateChange,
            BooleanSupplier isSuppressing) {
        this.nodes = nodes;
        this.onDebouncedChange = onDebouncedChange != null ? onDebouncedChange : () -> {};
        this.onImmediateChange = onImmediateChange != null ? onImmediateChange : () -> {};
        this.isSuppressing = isSuppressing != null ? isSuppressing : () -> false;

        registerDefaultFilters();
        setupSliderBindings();
        setupAutoFilterListeners();

        if (nodes.addInfoFilterButton() != null) {
            nodes.addInfoFilterButton().setOnAction(e -> showInfoFilterDialog());
        }
        if (nodes.addFilterFieldButton() != null) {
            nodes.addFilterFieldButton().setOnAction(e -> showFilterFieldDialog());
        }
    }

    public void registerThresholdFilter(
            String key,
            DoubleSupplier currentValue,
            ToDoubleFunction<VariantFilter> snapshotFromLoaded,
            LooserWhen looserWhen) {
        if (key == null || key.isBlank() || currentValue == null || snapshotFromLoaded == null || looserWhen == null) {
            return;
        }
        thresholdFilters.removeIf(t -> key.equals(t.key()));
        thresholdFilters.add(new ThresholdFilter(key, currentValue, snapshotFromLoaded, looserWhen));
    }

    public void registerSetFilter(
            String key,
            Supplier<Set<?>> currentValue,
            Function<VariantFilter, Set<?>> snapshotFromLoaded) {
        if (key == null || key.isBlank() || currentValue == null || snapshotFromLoaded == null) {
            return;
        }
        setFilters.removeIf(t -> key.equals(t.key()));
        setFilters.add(new SetFilter(key, currentValue, snapshotFromLoaded));
    }

    public void registerFlagFilter(
            String key,
            BooleanSupplier currentValue,
            java.util.function.Predicate<VariantFilter> snapshotFromLoaded) {
        if (key == null || key.isBlank() || currentValue == null || snapshotFromLoaded == null) {
            return;
        }
        flagFilters.removeIf(t -> key.equals(t.key()));
        flagFilters.add(new FlagFilter(key, currentValue, snapshotFromLoaded));
    }

    private void registerDefaultFilters() {
        if (nodes == null) {
            return;
        }
        registerThresholdFilter(
            "minQ",
            () -> nodes.qualitySlider().getValue(),
            VariantFilter::getMinQuality,
            LooserWhen.CURRENT_LOWER);
        registerThresholdFilter(
            "minDP",
            () -> nodes.coverageSlider().getValue(),
            f -> f.getMinDepth(),
            LooserWhen.CURRENT_LOWER);
        registerThresholdFilter(
            "minAF",
            () -> nodes.alleleFreqSlider().getValue(),
            VariantFilter::getMinAlleleFraction,
            LooserWhen.CURRENT_LOWER);

        registerSetFilter(
            "allowedTypes",
            this::currentAllowedTypes,
            f -> f.getAllowedTypes() == null ? Set.of() : Set.copyOf(f.getAllowedTypes()));
        registerSetFilter(
            "allowedEffects",
            this::currentAllowedEffects,
            f -> f.getAllowedEffects() == null ? Set.of() : Set.copyOf(f.getAllowedEffects()));

        registerFlagFilter(
            "cancerOnly",
            () -> nodes.cancerOnlyCheckBox().isSelected(),
            VariantFilter::isCancerGenesOnly);
    }

    /**
     * Load filter UI state. Caller must suppress apply events around this call.
     */
    public void loadFrom(VariantFilter filter) {
        if (nodes == null || filter == null) {
            return;
        }

        Set<VcfVariantType> types = filter.getAllowedTypes();
        for (Map.Entry<VcfVariantType, CheckBox> entry : variantTypeCheckBoxes.entrySet()) {
            entry.getValue().setSelected(types.contains(entry.getKey()));
        }

        Set<VariantEffect> effects = filter.getAllowedEffects();
        nodes.missenseCheckBox().setSelected(effects.contains(VariantEffect.CODING_MISSENSE));
        nodes.synonymousCheckBox().setSelected(effects.contains(VariantEffect.CODING_SYNONYMOUS));
        nodes.stopFrameshiftCheckBox().setSelected(
            effects.contains(VariantEffect.CODING_STOP_GAIN) || effects.contains(VariantEffect.CODING_FRAMESHIFT));
        nodes.spliceSiteCheckBox().setSelected(effects.contains(VariantEffect.SPLICE_SITE));
        nodes.utrCheckBox().setSelected(
            effects.contains(VariantEffect.UTR5) || effects.contains(VariantEffect.UTR3));
        nodes.noncodingCheckBox().setSelected(effects.contains(VariantEffect.NONCODING_GENE));
        nodes.intronicCheckBox().setSelected(effects.contains(VariantEffect.INTRONIC));
        nodes.intergenicCheckBox().setSelected(effects.contains(VariantEffect.INTERGENIC));

        nodes.qualitySlider().setValue(filter.getMinQuality());
        nodes.coverageSlider().setValue(filter.getMinDepth());
        nodes.alleleFreqSlider().setValue(filter.getMinAlleleFraction());
        nodes.cancerOnlyCheckBox().setSelected(filter.isCancerGenesOnly());

        if (nodes.advancedFiltersContainer() != null) {
            nodes.advancedFiltersContainer().getChildren().clear();
            for (Map.Entry<String, String> entry : filter.getInfoFieldFilters().entrySet()) {
                addInfoFilterRule(entry.getKey(), entry.getValue());
            }
            for (String filterValue : filter.getAllowedFilterValues()) {
                addFilterFieldRule(filterValue);
            }
        }

        updateSelectAllEffectsState();
        updateSelectAllTypesState();
    }

    /**
     * Write types, effects, quality/depth/AF, cancer, and advanced INFO/FILTER into {@code filter}.
     */
    public void writeTo(VariantFilter filter) {
        if (nodes == null || filter == null) {
            return;
        }

        filter.setAllowedTypes(currentAllowedTypes());
        filter.setAllowedEffects(currentAllowedEffects());

        try {
            filter.setMinQuality(Double.parseDouble(nodes.qualityField().getText().trim()));
        } catch (NumberFormatException ignored) {}
        try {
            filter.setMinDepth(Integer.parseInt(nodes.coverageField().getText().trim()));
        } catch (NumberFormatException ignored) {}
        try {
            filter.setMinAlleleFraction(Double.parseDouble(nodes.alleleFreqField().getText().trim()));
        } catch (NumberFormatException ignored) {}

        filter.setCancerGenesOnly(nodes.cancerOnlyCheckBox().isSelected());

        Map<String, String> infoFilters = new HashMap<>();
        Set<String> filterValues = new HashSet<>();
        if (nodes.advancedFiltersContainer() != null) {
            for (javafx.scene.Node node : nodes.advancedFiltersContainer().getChildren()) {
                if (node instanceof HBox ruleBox
                        && !ruleBox.getChildren().isEmpty()
                        && ruleBox.getChildren().get(0) instanceof Label label) {
                    String text = label.getText();
                    if (text.startsWith("INFO.")) {
                        String[] parts = text.substring(5).split(" = ");
                        if (parts.length == 2) {
                            infoFilters.put(parts[0], parts[1]);
                        }
                    } else if (text.startsWith("FILTER = ")) {
                        filterValues.add(text.substring(9));
                    }
                }
            }
        }
        filter.setInfoFieldFilters(infoFilters);
        filter.setAllowedFilterValues(filterValues);
    }

    private Set<VcfVariantType> currentAllowedTypes() {
        Set<VcfVariantType> types = new HashSet<>();
        if (variantTypeCheckBoxes.isEmpty()) {
            types.addAll(EnumSet.allOf(VcfVariantType.class));
        } else {
            for (Map.Entry<VcfVariantType, CheckBox> entry : variantTypeCheckBoxes.entrySet()) {
                if (entry.getValue().isSelected()) {
                    types.add(entry.getKey());
                }
            }
        }
        return types;
    }

    private Set<VariantEffect> currentAllowedEffects() {
        Set<VariantEffect> effects = EnumSet.noneOf(VariantEffect.class);
        if (nodes == null) {
            return effects;
        }
        if (nodes.missenseCheckBox().isSelected()) effects.add(VariantEffect.CODING_MISSENSE);
        if (nodes.synonymousCheckBox().isSelected()) effects.add(VariantEffect.CODING_SYNONYMOUS);
        if (nodes.stopFrameshiftCheckBox().isSelected()) {
            effects.add(VariantEffect.CODING_STOP_GAIN);
            effects.add(VariantEffect.CODING_STOP_LOSS);
            effects.add(VariantEffect.CODING_FRAMESHIFT);
        }
        if (nodes.spliceSiteCheckBox().isSelected()) effects.add(VariantEffect.SPLICE_SITE);
        if (nodes.utrCheckBox().isSelected()) {
            effects.add(VariantEffect.UTR5);
            effects.add(VariantEffect.UTR3);
        }
        if (nodes.noncodingCheckBox().isSelected()) effects.add(VariantEffect.NONCODING_GENE);
        if (nodes.intronicCheckBox().isSelected()) effects.add(VariantEffect.INTRONIC);
        if (nodes.intergenicCheckBox().isSelected()) effects.add(VariantEffect.INTERGENIC);
        return effects;
    }

    public void populateVariantTypes(List<VcfManager.CachedChromosomeVariants> sources) {
        populateVariantTypes(sources, null);
    }

    public void populateVariantTypes(
            List<VcfManager.CachedChromosomeVariants> sources,
            VariantFilter currentFilter) {
        populateVariantTypes(collectPresentVariantTypes(sources), currentFilter);
    }

    public void populateVariantTypes(Collection<VcfVariantType> presentTypes) {
        populateVariantTypes(presentTypes, null);
    }

    public void populateVariantTypes(Collection<VcfVariantType> presentTypes, VariantFilter currentFilter) {
        if (nodes == null || nodes.variantTypesContainer() == null) {
            return;
        }

        boolean previousLocalSuppress = localSuppress;
        localSuppress = true;
        try {
            Set<VcfVariantType> present = (presentTypes != null && !presentTypes.isEmpty())
                ? EnumSet.copyOf(presentTypes)
                : EnumSet.noneOf(VcfVariantType.class);

            boolean hasSvTypes = present.stream().anyMatch(t ->
                t == VcfVariantType.SV_DELETION || t == VcfVariantType.SV_INSERTION
                    || t == VcfVariantType.SV_DUPLICATION || t == VcfVariantType.SV_INVERSION
                    || t == VcfVariantType.SV_TRANSLOCATION || t == VcfVariantType.SV_BREAKEND);

            Set<VcfVariantType> typesToShow = new LinkedHashSet<>();
            for (VcfVariantType type : present) {
                switch (type) {
                    case SNV, MNV, COMPLEX -> typesToShow.add(type);
                    case INSERTION -> {
                        if (!hasSvTypes || !present.contains(VcfVariantType.SV_INSERTION)) {
                            typesToShow.add(type);
                        }
                    }
                    case DELETION -> {
                        if (!hasSvTypes || !present.contains(VcfVariantType.SV_DELETION)) {
                            typesToShow.add(type);
                        }
                    }
                    case SV_INSERTION, SV_DELETION, SV_DUPLICATION, SV_INVERSION, SV_TRANSLOCATION, SV_BREAKEND -> {
                        if (hasSvTypes) {
                            typesToShow.add(type);
                        }
                    }
                }
            }

            Set<VcfVariantType> addedInThisCall = new HashSet<>();
            for (VcfVariantType type : typesToShow) {
                if (!variantTypeCheckBoxes.containsKey(type)) {
                    CheckBox cb = new CheckBox(getVariantTypeLabel(type));
                    boolean isTypeSelected = currentFilter != null && currentFilter.getAllowedTypes().contains(type);
                    cb.setSelected(isTypeSelected);
                    cb.getStyleClass().add("filter-checkbox");
                    cb.selectedProperty().addListener((obs, oldVal, newVal) -> {
                        updateSelectAllTypesState();
                        refreshReloadBannerFromFilters();
                        scheduleImmediateChange();
                    });
                    variantTypeCheckBoxes.put(type, cb);
                    addedInThisCall.add(type);

                    if (type == VcfVariantType.SV_INSERTION && present.contains(VcfVariantType.INSERTION)) {
                        variantTypeCheckBoxes.put(VcfVariantType.INSERTION, cb);
                    } else if (type == VcfVariantType.SV_DELETION && present.contains(VcfVariantType.DELETION)) {
                        variantTypeCheckBoxes.put(VcfVariantType.DELETION, cb);
                    }
                }
            }

            int columnCount = 3;
            for (VcfVariantType type : addedInThisCall) {
                CheckBox cb = variantTypeCheckBoxes.get(type);
                int row = nodes.variantTypesContainer().getChildren().size() / columnCount;
                int col = nodes.variantTypesContainer().getChildren().size() % columnCount;
                nodes.variantTypesContainer().add(cb, col, row);
            }

            updateSelectAllTypesState();
        } finally {
            localSuppress = previousLocalSuppress;
        }
    }

    public void resetToDefaults() {
        if (nodes == null) {
            return;
        }
        for (CheckBox cb : variantTypeCheckBoxes.values()) {
            cb.setSelected(true);
        }
        if (nodes.selectAllTypesCheckBox() != null) {
            nodes.selectAllTypesCheckBox().setSelected(true);
        }
        nodes.missenseCheckBox().setSelected(true);
        nodes.synonymousCheckBox().setSelected(true);
        nodes.stopFrameshiftCheckBox().setSelected(true);
        nodes.spliceSiteCheckBox().setSelected(true);
        nodes.utrCheckBox().setSelected(true);
        nodes.noncodingCheckBox().setSelected(true);
        nodes.intronicCheckBox().setSelected(true);
        nodes.intergenicCheckBox().setSelected(true);
        if (nodes.selectAllEffectsCheckBox() != null) {
            nodes.selectAllEffectsCheckBox().setSelected(true);
        }
        nodes.qualitySlider().setValue(0);
        nodes.coverageSlider().setValue(0);
        nodes.alleleFreqSlider().setValue(0);
        nodes.cancerOnlyCheckBox().setSelected(false);
        if (nodes.advancedFiltersContainer() != null) {
            nodes.advancedFiltersContainer().getChildren().clear();
        }
    }

    public void setControlsLocked(boolean locked) {
        if (nodes == null) {
            return;
        }
        if (nodes.reloadBannerButton() != null) {
            nodes.reloadBannerButton().setDisable(locked);
        }
    }

    public void captureFilterSnapshot(VariantFilter loaded) {
        filtersSnapshotHash.clear();
        setFiltersSnapshotHash.clear();
        flagFiltersSnapshotHash.clear();
        if (loaded == null) {
            return;
        }
        for (ThresholdFilter filter : thresholdFilters) {
            filtersSnapshotHash.put(filter.key(), filter.snapshotFromLoaded().applyAsDouble(loaded));
        }
        for (SetFilter filter : setFilters) {
            Set<?> snap = filter.snapshotFromLoaded().apply(loaded);
            setFiltersSnapshotHash.put(filter.key(), snap == null ? Set.of() : Set.copyOf(snap));
        }
        for (FlagFilter filter : flagFilters) {
            flagFiltersSnapshotHash.put(filter.key(), filter.snapshotFromLoaded().test(loaded));
        }
    }

    public boolean compareIfLooserValue(String filterKey, double currentValue) {
        Double snapshot = filtersSnapshotHash.get(filterKey);
        if (snapshot == null) {
            return false;
        }
        ThresholdFilter spec = findThresholdFilter(filterKey);
        if (spec == null) {
            return false;
        }
        return switch (spec.looserWhen()) {
            case CURRENT_LOWER -> currentValue < snapshot;
            case CURRENT_HIGHER -> currentValue > snapshot;
        };
    }

    /**
     * Allowed-set looser check: current contains any member not in the snapshot.
     */
    public boolean compareIfLooserSet(String filterKey, Set<?> currentValue) {
        Set<?> snapshot = setFiltersSnapshotHash.get(filterKey);
        if (snapshot == null) {
            return false;
        }
        if (currentValue == null || currentValue.isEmpty()) {
            return false;
        }
        for (Object value : currentValue) {
            if (!snapshot.contains(value)) {
                return true;
            }
        }
        return false;
    }

    /** Restrictive flag looser check: was on at load, now off. */
    public boolean compareIfLooserFlag(String filterKey, boolean currentValue) {
        Boolean snapshot = flagFiltersSnapshotHash.get(filterKey);
        if (snapshot == null) {
            return false;
        }
        return snapshot && !currentValue;
    }

    /** True if any registered filter is currently looser than its load-time snapshot. */
    public boolean anyFilterLooserThanSnapshot() {
        for (ThresholdFilter filter : thresholdFilters) {
            if (compareIfLooserValue(filter.key(), filter.currentValue().getAsDouble())) {
                return true;
            }
        }
        for (SetFilter filter : setFilters) {
            if (compareIfLooserSet(filter.key(), filter.currentValue().get())) {
                return true;
            }
        }
        for (FlagFilter filter : flagFilters) {
            if (compareIfLooserFlag(filter.key(), filter.currentValue().getAsBoolean())) {
                return true;
            }
        }
        return false;
    }

    private ThresholdFilter findThresholdFilter(String key) {
        for (ThresholdFilter filter : thresholdFilters) {
            if (filter.key().equals(key)) {
                return filter;
            }
        }
        return null;
    }

    private void refreshReloadBannerFromFilters() {
        refreshReloadBannerState(anyFilterLooserThanSnapshot(), "Reload needed");
    }

    public void showReloadBanner(String message) {
        if (nodes == null) {
            return;
        }
        if (nodes.reloadBannerLabel() != null) {
            nodes.reloadBannerLabel().setText(message);
        }
        if (nodes.reloadBanner() != null) {
            nodes.reloadBanner().setVisible(true);
        }
    }

    public void hideReloadBanner() {
        if (nodes != null && nodes.reloadBanner() != null) {
            nodes.reloadBanner().setVisible(false);
        }
    }

    public void refreshReloadBannerState(boolean needed, String message) {
        if (needed) {
            showReloadBanner(message != null ? message : "Reload needed");
        } else {
            hideReloadBanner();
        }
    }

    public Button getReloadBannerButton() {
        return nodes != null ? nodes.reloadBannerButton() : null;
    }

    public Button getAddInfoFilterButton() {
        return nodes != null ? nodes.addInfoFilterButton() : null;
    }

    public Button getAddFilterFieldButton() {
        return nodes != null ? nodes.addFilterFieldButton() : null;
    }

    public Map<VcfVariantType, CheckBox> getVariantTypeCheckBoxes() {
        return variantTypeCheckBoxes;
    }

    public void cancelPendingFilterTimers() {
        // Debounce lives in VariantManagerController.
    }

    public void showInfoFilterDialog() {
        Dialog<Pair<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Add INFO Field Filter");
        dialog.setHeaderText(
            "Specify an INFO field and expected value\n\n"
                + "Note: INFO/FILTER filtering will be applied once VariantNode stores these fields.");

        ButtonType addButtonType = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addButtonType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        TextField fieldName = new TextField();
        fieldName.setPromptText("e.g., SVTYPE");
        TextField fieldValue = new TextField();
        fieldValue.setPromptText("e.g., DEL");

        grid.add(new Label("INFO Field Name:"), 0, 0);
        grid.add(fieldName, 1, 0);
        grid.add(new Label("Expected Value:"), 0, 1);
        grid.add(fieldValue, 1, 1);

        dialog.getDialogPane().setContent(grid);
        Platform.runLater(fieldName::requestFocus);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == addButtonType) {
                return new Pair<>(fieldName.getText().trim(), fieldValue.getText().trim());
            }
            return null;
        });

        dialog.showAndWait().ifPresent(pair -> {
            if (!pair.getKey().isEmpty() && !pair.getValue().isEmpty()) {
                addInfoFilterRule(pair.getKey(), pair.getValue());
                scheduleImmediateChange();
            }
        });
    }

    public void showFilterFieldDialog() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Add FILTER Field Value");
        dialog.setHeaderText(
            "Specify allowed FILTER values (e.g., PASS, LowQual)\n\n"
                + "Note: INFO/FILTER filtering will be applied once VariantNode stores these fields.");

        ButtonType addButtonType = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addButtonType, ButtonType.CANCEL);

        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(20, 150, 10, 10));

        TextField filterValue = new TextField();
        filterValue.setPromptText("e.g., PASS");
        Label hint = new Label("Only variants with this FILTER value will be shown.");
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        vbox.getChildren().addAll(new Label("FILTER Value:"), filterValue, hint);

        dialog.getDialogPane().setContent(vbox);
        Platform.runLater(filterValue::requestFocus);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == addButtonType) {
                return filterValue.getText().trim();
            }
            return null;
        });

        dialog.showAndWait().ifPresent(value -> {
            if (!value.isEmpty()) {
                addFilterFieldRule(value);
                scheduleImmediateChange();
            }
        });
    }

    public void addInfoFilterRule(String fieldName, String fieldValue) {
        if (nodes == null || nodes.advancedFiltersContainer() == null) {
            return;
        }
        HBox ruleBox = new HBox(10);
        ruleBox.setAlignment(Pos.CENTER_LEFT);

        Label ruleLabel = new Label("INFO." + fieldName + " = " + fieldValue);
        ruleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 11px;");

        Button removeBtn = new Button("×");
        removeBtn.setStyle("-fx-font-size: 14px; -fx-padding: 0 5 0 5;");
        removeBtn.getStyleClass().add("secondary-button");
        removeBtn.setOnAction(e -> {
            nodes.advancedFiltersContainer().getChildren().remove(ruleBox);
            scheduleImmediateChange();
        });

        ruleBox.getChildren().addAll(ruleLabel, removeBtn);
        nodes.advancedFiltersContainer().getChildren().add(ruleBox);
    }

    public void addFilterFieldRule(String filterValue) {
        if (nodes == null || nodes.advancedFiltersContainer() == null) {
            return;
        }
        HBox ruleBox = new HBox(10);
        ruleBox.setAlignment(Pos.CENTER_LEFT);

        Label ruleLabel = new Label("FILTER = " + filterValue);
        ruleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 11px;");

        Button removeBtn = new Button("×");
        removeBtn.setStyle("-fx-font-size: 14px; -fx-padding: 0 5 0 5;");
        removeBtn.getStyleClass().add("secondary-button");
        removeBtn.setOnAction(e -> {
            nodes.advancedFiltersContainer().getChildren().remove(ruleBox);
            scheduleImmediateChange();
        });

        ruleBox.getChildren().addAll(ruleLabel, removeBtn);
        nodes.advancedFiltersContainer().getChildren().add(ruleBox);
    }

    public static String getVariantTypeLabel(VcfVariantType type) {
        return switch (type) {
            case SNV -> "SNV";
            case INSERTION -> "INS";
            case DELETION -> "DEL";
            case MNV -> "MNV";
            case SV_DELETION -> "DEL";
            case SV_INSERTION -> "INS";
            case SV_DUPLICATION -> "DUP";
            case SV_INVERSION -> "INV";
            case SV_TRANSLOCATION -> "TRA";
            case SV_BREAKEND -> "BND";
            case COMPLEX -> "Complex";
        };
    }

    private void setupSliderBindings() {
        bindThresholdSlider(
            nodes.qualitySlider(),
            nodes.qualityField(),
            nodes.qualityValueLabel(),
            v -> String.valueOf(v.intValue()));
        bindThresholdSlider(
            nodes.coverageSlider(),
            nodes.coverageField(),
            nodes.coverageValueLabel(),
            v -> String.valueOf(v.intValue()));
        bindThresholdSlider(
            nodes.alleleFreqSlider(),
            nodes.alleleFreqField(),
            nodes.alleleFreqValueLabel(),
            v -> String.format("%.2f", v));
    }

    private void bindThresholdSlider(
            Slider slider,
            TextField field,
            Label valueLabel,
            java.util.function.Function<Double, String> format) {
        slider.valueProperty().addListener((obs, oldVal, newVal) -> {
            String text = format.apply(newVal.doubleValue());
            valueLabel.setText(text);
            field.setText(text);
            refreshReloadBannerFromFilters();
            scheduleDebouncedChange();
        });
        field.textProperty().addListener((obs, oldVal, newVal) -> {
            try {
                slider.setValue(Double.parseDouble(newVal));
            } catch (NumberFormatException ignored) {}
        });
    }

    private void setupAutoFilterListeners() {
        nodes.selectAllTypesCheckBox().setOnAction(e -> {
            boolean selectAll = nodes.selectAllTypesCheckBox().isSelected();
            localSuppress = true;
            for (CheckBox cb : new HashSet<>(variantTypeCheckBoxes.values())) {
                cb.setSelected(selectAll);
            }
            localSuppress = false;
            refreshReloadBannerFromFilters();
            scheduleImmediateChange();
        });

        nodes.selectAllEffectsCheckBox().setOnAction(e -> {
            boolean selectAll = nodes.selectAllEffectsCheckBox().isSelected();
            localSuppress = true;
            nodes.missenseCheckBox().setSelected(selectAll);
            nodes.synonymousCheckBox().setSelected(selectAll);
            nodes.stopFrameshiftCheckBox().setSelected(selectAll);
            nodes.spliceSiteCheckBox().setSelected(selectAll);
            nodes.utrCheckBox().setSelected(selectAll);
            nodes.noncodingCheckBox().setSelected(selectAll);
            nodes.intronicCheckBox().setSelected(selectAll);
            nodes.intergenicCheckBox().setSelected(selectAll);
            localSuppress = false;
            refreshReloadBannerFromFilters();
            scheduleImmediateChange();
        });

        ChangeListener<Boolean> effectCheckListener = (obs, oldVal, newVal) -> {
            updateSelectAllEffectsState();
            refreshReloadBannerFromFilters();
            scheduleImmediateChange();
        };
        nodes.missenseCheckBox().selectedProperty().addListener(effectCheckListener);
        nodes.synonymousCheckBox().selectedProperty().addListener(effectCheckListener);
        nodes.stopFrameshiftCheckBox().selectedProperty().addListener(effectCheckListener);
        nodes.spliceSiteCheckBox().selectedProperty().addListener(effectCheckListener);
        nodes.utrCheckBox().selectedProperty().addListener(effectCheckListener);
        nodes.noncodingCheckBox().selectedProperty().addListener(effectCheckListener);
        nodes.intronicCheckBox().selectedProperty().addListener(effectCheckListener);
        nodes.intergenicCheckBox().selectedProperty().addListener(effectCheckListener);

        nodes.cancerOnlyCheckBox().setOnAction(e -> {
            refreshReloadBannerFromFilters();
            scheduleImmediateChange();
        });
        nodes.qualityField().setOnAction(e -> scheduleImmediateChange());
    }

    private void updateSelectAllEffectsState() {
        boolean previousLocalSuppress = localSuppress;
        localSuppress = true;
        try {
            boolean allSelected = nodes.missenseCheckBox().isSelected()
                && nodes.synonymousCheckBox().isSelected()
                && nodes.stopFrameshiftCheckBox().isSelected()
                && nodes.spliceSiteCheckBox().isSelected()
                && nodes.utrCheckBox().isSelected()
                && nodes.noncodingCheckBox().isSelected()
                && nodes.intronicCheckBox().isSelected()
                && nodes.intergenicCheckBox().isSelected();
            nodes.selectAllEffectsCheckBox().setSelected(allSelected);
        } finally {
            localSuppress = previousLocalSuppress;
        }
    }

    private void updateSelectAllTypesState() {
        boolean previousLocalSuppress = localSuppress;
        localSuppress = true;
        try {
            boolean allSelected = true;
            for (CheckBox cb : variantTypeCheckBoxes.values()) {
                if (!cb.isSelected()) {
                    allSelected = false;
                    break;
                }
            }
            nodes.selectAllTypesCheckBox().setSelected(allSelected);
        } finally {
            localSuppress = previousLocalSuppress;
        }
    }

    private void scheduleDebouncedChange() {
        if (shouldIgnoreChange()) {
            return;
        }
        onDebouncedChange.run();
    }

    private void scheduleImmediateChange() {
        if (shouldIgnoreChange()) {
            return;
        }
        onImmediateChange.run();
    }

    private boolean shouldIgnoreChange() {
        return localSuppress || isSuppressing.getAsBoolean();
    }

    private static Set<VcfVariantType> collectPresentVariantTypes(
            List<VcfManager.CachedChromosomeVariants> sources) {
        Set<VcfVariantType> combined = EnumSet.noneOf(VcfVariantType.class);
        if (sources == null) {
            return combined;
        }
        for (VcfManager.CachedChromosomeVariants cached : sources) {
            VariantList variants = cached.variants();
            if (variants != null && !variants.isEmpty()) {
                combined.addAll(variants.collectVariantTypes());
            }
        }
        return combined;
    }
}

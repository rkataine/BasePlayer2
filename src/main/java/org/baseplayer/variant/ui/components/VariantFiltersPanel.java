package org.baseplayer.variant.ui.components;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import org.baseplayer.variant.VariantTypeVisuals;
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
        GridPane effectCategoriesContainer,
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

    /** UI groups that map one checkbox to one or more {@link VariantEffect} values. */
    private enum EffectCategory {
        MISSENSE("Missense", VariantEffect.CODING_MISSENSE),
        SYNONYMOUS("Synonymous", VariantEffect.CODING_SYNONYMOUS),
        STOP_FRAMESHIFT("Stop/Frameshift",
            VariantEffect.CODING_STOP_GAIN,
            VariantEffect.CODING_STOP_LOSS,
            VariantEffect.CODING_FRAMESHIFT),
        INFRAME("In-frame", VariantEffect.CODING_INFRAME),
        CODING("Coding", VariantEffect.CODING_OTHER),
        SPLICE("Splice Site", VariantEffect.SPLICE_SITE),
        UTR("UTR", VariantEffect.UTR5, VariantEffect.UTR3),
        NONCODING("Non-coding", VariantEffect.NONCODING_GENE),
        INTRONIC("Intronic", VariantEffect.INTRONIC),
        INTERGENIC("Intergenic", VariantEffect.INTERGENIC);

        final String label;
        final EnumSet<VariantEffect> effects;

        EffectCategory(String label, VariantEffect first, VariantEffect... rest) {
            this.label = label;
            this.effects = EnumSet.of(first, rest);
        }

        boolean matchesAny(Set<VariantEffect> present) {
            for (VariantEffect effect : effects) {
                if (present.contains(effect)) {
                    return true;
                }
            }
            return false;
        }

        boolean matchesAllowed(Set<VariantEffect> allowed) {
            for (VariantEffect effect : effects) {
                if (allowed.contains(effect)) {
                    return true;
                }
            }
            return false;
        }
    }

    private Nodes nodes;
    private Runnable onDebouncedChange;
    private Runnable onImmediateChange;
    private BooleanSupplier isSuppressing;

    private boolean localSuppress;
    private final Map<VcfVariantType, CheckBox> variantTypeCheckBoxes = new HashMap<>();
    private final Map<EffectCategory, CheckBox> effectCategoryCheckBoxes = new LinkedHashMap<>();
    private final List<ThresholdFilter> thresholdFilters = new ArrayList<>();
    private final List<SetFilter> setFilters = new ArrayList<>();
    private final List<FlagFilter> flagFilters = new ArrayList<>();
    private final ChangeListener<Boolean> effectCheckListener = (obs, oldVal, newVal) -> {
        updateSelectAllEffectsState();
        refreshReloadBannerFromFilters();
        scheduleImmediateChange();
    };
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
        for (Map.Entry<EffectCategory, CheckBox> entry : effectCategoryCheckBoxes.entrySet()) {
            entry.getValue().setSelected(entry.getKey().matchesAllowed(effects));
        }

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
        if (effectCategoryCheckBoxes.isEmpty()) {
            // No annotated effects UI yet — do not over-filter.
            return EnumSet.allOf(VariantEffect.class);
        }
        Set<VariantEffect> effects = EnumSet.noneOf(VariantEffect.class);
        for (Map.Entry<EffectCategory, CheckBox> entry : effectCategoryCheckBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                effects.addAll(entry.getKey().effects);
            }
        }
        return effects;
    }

    public void populateVariantTypes(List<VcfManager.CachedChromosomeVariants> sources) {
        populateVariantTypes(sources, null);
    }

    public void populateVariantTypes(
            List<VcfManager.CachedChromosomeVariants> sources,
            VariantFilter currentFilter) {
        populateVariantTypes(collectPresentVariantTypes(sources), currentFilter);
        populateEffectCategories(collectPresentVariantEffects(sources), currentFilter);
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
            Map<VcfVariantType, Boolean> previousSelection = new HashMap<>();
            for (Map.Entry<VcfVariantType, CheckBox> entry : variantTypeCheckBoxes.entrySet()) {
                previousSelection.put(entry.getKey(), entry.getValue().isSelected());
            }

            nodes.variantTypesContainer().getChildren().clear();
            variantTypeCheckBoxes.clear();

            Set<VcfVariantType> present = (presentTypes != null && !presentTypes.isEmpty())
                ? EnumSet.copyOf(presentTypes)
                : EnumSet.noneOf(VcfVariantType.class);

            Set<VcfVariantType> typesToShow = VariantTypeVisuals.typesForUi(present);

            int columnCount = 3;
            int index = 0;
            Set<VcfVariantType> placed = new HashSet<>();
            for (VcfVariantType type : typesToShow) {
                if (placed.contains(type)) {
                    continue;
                }
                CheckBox cb = new CheckBox(getVariantTypeLabel(type));
                boolean selected = currentFilter != null
                    ? currentFilter.getAllowedTypes().contains(type)
                    : previousSelection.getOrDefault(type, true);
                cb.setSelected(selected);
                cb.getStyleClass().add("filter-checkbox");
                cb.selectedProperty().addListener((obs, oldVal, newVal) -> {
                    updateSelectAllTypesState();
                    refreshReloadBannerFromFilters();
                    scheduleImmediateChange();
                });
                variantTypeCheckBoxes.put(type, cb);
                placed.add(type);

                for (VcfVariantType linked : VariantTypeVisuals.linkedTypes(type, present)) {
                    variantTypeCheckBoxes.put(linked, cb);
                    placed.add(linked);
                }

                nodes.variantTypesContainer().add(cb, index % columnCount, index / columnCount);
                index++;
            }

            updateSelectAllTypesState();
        } finally {
            localSuppress = previousLocalSuppress;
        }
    }

    public void populateEffectCategories(
            Collection<VariantEffect> presentEffects,
            VariantFilter currentFilter) {
        if (nodes == null || nodes.effectCategoriesContainer() == null) {
            return;
        }

        boolean previousLocalSuppress = localSuppress;
        localSuppress = true;
        try {
            Map<EffectCategory, Boolean> previousSelection = new HashMap<>();
            for (Map.Entry<EffectCategory, CheckBox> entry : effectCategoryCheckBoxes.entrySet()) {
                previousSelection.put(entry.getKey(), entry.getValue().isSelected());
            }

            nodes.effectCategoriesContainer().getChildren().clear();
            effectCategoryCheckBoxes.clear();

            Set<VariantEffect> present = (presentEffects != null && !presentEffects.isEmpty())
                ? EnumSet.copyOf(presentEffects)
                : EnumSet.noneOf(VariantEffect.class);

            int columnCount = 4;
            int index = 0;
            for (EffectCategory category : EffectCategory.values()) {
                if (!category.matchesAny(present)) {
                    continue;
                }
                CheckBox cb = new CheckBox(category.label);
                boolean selected = currentFilter != null
                    ? category.matchesAllowed(currentFilter.getAllowedEffects())
                    : previousSelection.getOrDefault(category, true);
                cb.setSelected(selected);
                cb.getStyleClass().add("filter-checkbox");
                cb.selectedProperty().addListener(effectCheckListener);
                effectCategoryCheckBoxes.put(category, cb);
                nodes.effectCategoriesContainer().add(cb, index % columnCount, index / columnCount);
                index++;
            }

            updateSelectAllEffectsState();
        } finally {
            localSuppress = previousLocalSuppress;
        }
    }

    public void resetToDefaults() {
        if (nodes == null) {
            return;
        }
        for (CheckBox cb : new HashSet<>(variantTypeCheckBoxes.values())) {
            cb.setSelected(true);
        }
        if (nodes.selectAllTypesCheckBox() != null) {
            nodes.selectAllTypesCheckBox().setSelected(true);
        }
        for (CheckBox cb : effectCategoryCheckBoxes.values()) {
            cb.setSelected(true);
        }
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
        updateSelectAllTypesState();
        updateSelectAllEffectsState();
        refreshReloadBannerFromFilters();
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

    public void clearEffectCategoryCheckBoxes() {
        effectCategoryCheckBoxes.clear();
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
        return VariantTypeVisuals.shortLabel(type);
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
            for (CheckBox cb : effectCategoryCheckBoxes.values()) {
                cb.setSelected(selectAll);
            }
            localSuppress = false;
            refreshReloadBannerFromFilters();
            scheduleImmediateChange();
        });

        nodes.cancerOnlyCheckBox().setOnAction(e -> {
            refreshReloadBannerFromFilters();
            scheduleImmediateChange();
        });
        nodes.qualityField().setOnAction(e -> scheduleImmediateChange());
    }

    private void updateSelectAllEffectsState() {
        if (nodes == null || nodes.selectAllEffectsCheckBox() == null) {
            return;
        }
        boolean previousLocalSuppress = localSuppress;
        localSuppress = true;
        try {
            boolean allSelected = !effectCategoryCheckBoxes.isEmpty();
            for (CheckBox cb : effectCategoryCheckBoxes.values()) {
                if (!cb.isSelected()) {
                    allSelected = false;
                    break;
                }
            }
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

    private static Set<VariantEffect> collectPresentVariantEffects(
            List<VcfManager.CachedChromosomeVariants> sources) {
        Set<VariantEffect> combined = EnumSet.noneOf(VariantEffect.class);
        if (sources == null) {
            return combined;
        }
        for (VcfManager.CachedChromosomeVariants cached : sources) {
            VariantList variants = cached.variants();
            if (variants != null && !variants.isEmpty()) {
                combined.addAll(variants.collectVariantEffects());
            }
        }
        return combined;
    }
}

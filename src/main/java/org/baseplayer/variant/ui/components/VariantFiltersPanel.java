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

import org.baseplayer.components.ColoredToggle;
import org.baseplayer.io.VcfManager;
import org.baseplayer.utils.GeneBiotypeVisibility;
import org.baseplayer.variant.InfoFieldRule;
import org.baseplayer.variant.VcfHeaderFieldDef;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.annotation.VariantEffect;

import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
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

    private VariantTypeVisuals.VariantClass variantClass;

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
        FlowPane variantTypesContainer,
        ColoredToggle selectAllTypesCheckBox,
        ColoredToggle selectAllEffectsCheckBox,
        FlowPane effectCategoriesContainer,
        Slider qualitySlider,
        Slider coverageSlider,
        DualRangeSlider alleleFreqRangeSlider,
        TextField qualityField,
        TextField coverageField,
        Label qualityValueLabel,
        Label coverageValueLabel,
        ColoredToggle cancerOnlyCheckBox,
        VBox advancedFiltersContainer,
        Button addInfoFilterButton,
        Button addFilterFieldButton,
        HBox reloadBanner,
        Label reloadBannerLabel,
        Button reloadBannerButton,
        TextField minSvLengthField,
        TextField maxSvLengthField,
        Button annotateAllChromosomesButton,
        TextField tableSearchField,
        Button excelExportButton
    ) {
        /** Point-mutation nodes without SV length / annotate / search fields. */
        public Nodes(
            FlowPane variantTypesContainer,
            ColoredToggle selectAllTypesCheckBox,
            ColoredToggle selectAllEffectsCheckBox,
            FlowPane effectCategoriesContainer,
            Slider qualitySlider,
            Slider coverageSlider,
            DualRangeSlider alleleFreqRangeSlider,
            TextField qualityField,
            TextField coverageField,
            Label qualityValueLabel,
            Label coverageValueLabel,
            ColoredToggle cancerOnlyCheckBox,
            VBox advancedFiltersContainer,
            Button addInfoFilterButton,
            Button addFilterFieldButton,
            HBox reloadBanner,
            Label reloadBannerLabel,
            Button reloadBannerButton) {
            this(
                variantTypesContainer,
                selectAllTypesCheckBox,
                selectAllEffectsCheckBox,
                effectCategoriesContainer,
                qualitySlider,
                coverageSlider,
                alleleFreqRangeSlider,
                qualityField,
                coverageField,
                qualityValueLabel,
                coverageValueLabel,
                cancerOnlyCheckBox,
                advancedFiltersContainer,
                addInfoFilterButton,
                addFilterFieldButton,
                reloadBanner,
                reloadBannerLabel,
                reloadBannerButton,
                null,
                null,
                null,
                null,
                null);
        }

        /** SV nodes with length fields but without annotate/search (compat). */
        public Nodes(
            FlowPane variantTypesContainer,
            ColoredToggle selectAllTypesCheckBox,
            ColoredToggle selectAllEffectsCheckBox,
            FlowPane effectCategoriesContainer,
            Slider qualitySlider,
            Slider coverageSlider,
            DualRangeSlider alleleFreqRangeSlider,
            TextField qualityField,
            TextField coverageField,
            Label qualityValueLabel,
            Label coverageValueLabel,
            ColoredToggle cancerOnlyCheckBox,
            VBox advancedFiltersContainer,
            Button addInfoFilterButton,
            Button addFilterFieldButton,
            HBox reloadBanner,
            Label reloadBannerLabel,
            Button reloadBannerButton,
            TextField minSvLengthField,
            TextField maxSvLengthField) {
            this(
                variantTypesContainer,
                selectAllTypesCheckBox,
                selectAllEffectsCheckBox,
                effectCategoriesContainer,
                qualitySlider,
                coverageSlider,
                alleleFreqRangeSlider,
                qualityField,
                coverageField,
                qualityValueLabel,
                coverageValueLabel,
                cancerOnlyCheckBox,
                advancedFiltersContainer,
                addInfoFilterButton,
                addFilterFieldButton,
                reloadBanner,
                reloadBannerLabel,
                reloadBannerButton,
                minSvLengthField,
                maxSvLengthField,
                null,
                null,
                null);
        }
    }

    /**
     * Programmatically build an SV filter panel (types, QUAL, SV length, cancer, reload).
     * Returns the root node and wired {@link Nodes}.
     */
    public static Pair<javafx.scene.Node, Nodes> buildStructuralFiltersUi() {
        FlowPane typesContainer = newFilterFlow();
        ColoredToggle selectAllTypes = new ColoredToggle();
        selectAllTypes.setSelected(true);

        Slider qualitySlider = new Slider(0, 99, 0);
        qualitySlider.setShowTickLabels(false);
        qualitySlider.setShowTickMarks(false);
        qualitySlider.setBlockIncrement(5);
        qualitySlider.setPrefWidth(350);
        TextField qualityField = new TextField("0");
        qualityField.setPrefWidth(60);
        qualityField.getStyleClass().add("filter-field");
        Label qualityValue = new Label("0");
        qualityValue.getStyleClass().add("value-label");

        // Hidden depth/AF for Nodes compatibility (SV panel does not show them).
        Slider coverageSlider = new Slider(0, 200, 0);
        coverageSlider.setVisible(false);
        coverageSlider.setManaged(false);
        TextField coverageField = new TextField("0");
        coverageField.setVisible(false);
        coverageField.setManaged(false);
        Label coverageValue = new Label("0");
        coverageValue.setVisible(false);
        coverageValue.setManaged(false);

        DualRangeSlider alleleFreqRangeSlider = new DualRangeSlider();
        alleleFreqRangeSlider.setVisible(false);
        alleleFreqRangeSlider.setManaged(false);

        ColoredToggle selectAllEffects = new ColoredToggle();
        selectAllEffects.setSelected(true);
        FlowPane effectsContainer = newFilterFlow();

        ColoredToggle cancerOnly = new ColoredToggle(
            "Cancer genes only (COSMIC Census)", GeneBiotypeVisibility.Category.COSMIC.color());

        TextField minSvLen = new TextField("0");
        minSvLen.setPrefWidth(80);
        minSvLen.getStyleClass().add("filter-field");
        TextField maxSvLen = new TextField("");
        maxSvLen.setPrefWidth(80);
        maxSvLen.setPromptText("no max");
        maxSvLen.getStyleClass().add("filter-field");

        HBox reloadBanner = new HBox(6);
        reloadBanner.setAlignment(Pos.CENTER_LEFT);
        reloadBanner.setVisible(false);
        reloadBanner.setStyle(
            "-fx-background-color: rgba(209,102,36,0.18); -fx-background-radius: 6; -fx-padding: 2 6 2 6;");
        Label reloadLabel = new Label("Reload needed");
        reloadLabel.setStyle("-fx-text-fill: #f0c6a9; -fx-font-size: 11px;");
        Button reloadButton = new Button("Reload");
        reloadButton.getStyleClass().add("secondary-button");
        reloadButton.setStyle("-fx-font-size: 10px; -fx-padding: 1 6 1 6;");
        reloadBanner.getChildren().addAll(reloadLabel, reloadButton);

        VBox advanced = new VBox(4);
        Button addCustom = new Button("Add field…");
        addCustom.getStyleClass().add("secondary-button");

        Label title = new Label("Structural variant filters");
        title.getStyleClass().add("panel-title");

        HBox titleRow = new HBox(8, title, reloadBanner);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        VBox left = new VBox(8);
        left.getStyleClass().add("filter-panel");
        left.setPadding(new Insets(16));
        left.getChildren().addAll(
            titleRow,
            new javafx.scene.control.Separator(),
            compactSliderRow("QUAL", "Minimum variant quality (QUAL)",
                qualitySlider, qualityField),
            labeledFields("SV length (bp)",
                new Label("Min"), minSvLen, new Label("Max"), maxSvLen),
            new javafx.scene.control.Separator(),
            section("Custom filters", advanced, new HBox(8, addCustom)));

        VBox right = new VBox(8);
        right.getStyleClass().add("filter-panel");
        right.setPadding(new Insets(16));
        Label typesLabel = new Label("SV Types");
        typesLabel.getStyleClass().add("section-header");
        HBox typesHeader = new HBox(6, selectAllTypes, typesLabel);
        typesHeader.setAlignment(Pos.CENTER_LEFT);
        Label effectsLabel = new Label("Gene effect");
        effectsLabel.getStyleClass().add("section-header");
        HBox effectsHeader = new HBox(6, selectAllEffects, effectsLabel);
        effectsHeader.setAlignment(Pos.CENTER_LEFT);
        right.getChildren().addAll(
            new VBox(6, typesHeader, typesContainer),
            new javafx.scene.control.Separator(),
            new VBox(6, effectsHeader, effectsContainer),
            new javafx.scene.control.Separator(),
            new VBox(6, new Label("Special Filters") {{ getStyleClass().add("section-header"); }}, cancerOnly));

        // Annotate / search / Excel live in the variant-table toolbar (above results tabs).

        javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane(left, right);
        split.setDividerPositions(0.5);
        split.getStyleClass().add("tab-content-split");

        Nodes nodes = new Nodes(
            typesContainer,
            selectAllTypes,
            selectAllEffects,
            effectsContainer,
            qualitySlider,
            coverageSlider,
            alleleFreqRangeSlider,
            qualityField,
            coverageField,
            qualityValue,
            coverageValue,
            cancerOnly,
            advanced,
            addCustom,
            null,
            reloadBanner,
            reloadLabel,
            reloadButton,
            minSvLen,
            maxSvLen,
            null,
            null,
            null);
        return new Pair<>(split, nodes);
    }

    private static HBox compactSliderRow(String shortLabel, String tooltip, Slider slider, TextField field) {
        Label header = new Label(shortLabel);
        header.getStyleClass().addAll("section-header", "filter-slider-label");
        header.setMinWidth(36);
        header.setPrefWidth(36);
        if (tooltip != null && !tooltip.isBlank()) {
            header.setTooltip(new Tooltip(tooltip));
        }
        HBox.setHgrow(slider, Priority.ALWAYS);
        HBox row = new HBox(8, header, slider, field);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static VBox labeledFields(String title, javafx.scene.Node... nodes) {
        Label header = new Label(title);
        header.getStyleClass().add("section-header");
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(nodes);
        return new VBox(4, header, row);
    }

    private static VBox section(String title, javafx.scene.Node body, javafx.scene.Node footer) {
        Label header = new Label(title);
        header.getStyleClass().add("section-header");
        return new VBox(6, header, body, footer);
    }

    private static FlowPane newFilterFlow() {
        FlowPane flow = new FlowPane(10, 2);
        flow.setPrefWrapLength(320);
        flow.getStyleClass().add("filter-toggle-flow");
        return flow;
    }

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

        Color color() {
            return switch (this) {
                case STOP_FRAMESHIFT, SPLICE -> Color.web("#e81123");
                case MISSENSE, INFRAME -> Color.web("#c9a000");
                case SYNONYMOUS -> Color.web("#107c10");
                case CODING -> Color.web("#8a8886");
                case UTR -> Color.web("#7a9bb8");
                case NONCODING -> Color.web("#8a8886");
                case INTRONIC -> Color.web("#6b7280");
                case INTERGENIC -> Color.web("#9ca3af");
            };
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
    private final Map<VcfVariantType, ColoredToggle> variantTypeCheckBoxes = new HashMap<>();
    private final Map<EffectCategory, ColoredToggle> effectCategoryCheckBoxes = new LinkedHashMap<>();
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
        install(nodes, onDebouncedChange, onImmediateChange, isSuppressing, null);
    }

    public void install(
            Nodes nodes,
            Runnable onDebouncedChange,
            Runnable onImmediateChange,
            BooleanSupplier isSuppressing,
            VariantTypeVisuals.VariantClass variantClass) {
        this.nodes = nodes;
        this.variantClass = variantClass;
        this.onDebouncedChange = onDebouncedChange != null ? onDebouncedChange : () -> {};
        this.onImmediateChange = onImmediateChange != null ? onImmediateChange : () -> {};
        this.isSuppressing = isSuppressing != null ? isSuppressing : () -> false;

        if (nodes.cancerOnlyCheckBox() != null) {
            // Match gene-sidebar COSMIC swatch (#d16624).
            nodes.cancerOnlyCheckBox().setSwatchColor(GeneBiotypeVisibility.Category.COSMIC.color());
        }

        registerDefaultFilters();
        setupSliderBindings();
        setupAutoFilterListeners();

        if (nodes.addInfoFilterButton() != null) {
            nodes.addInfoFilterButton().setText("Add field…");
            nodes.addInfoFilterButton().setTooltip(new Tooltip(
                "Add an INFO, FILTER, or FORMAT control below the default sliders"));
            nodes.addInfoFilterButton().setOnAction(e -> showAddFieldDialog());
        }
        if (nodes.addFilterFieldButton() != null) {
            nodes.addFilterFieldButton().setOnAction(e -> showAddFieldDialog(VcfHeaderFieldDef.Kind.FILTER));
        }
    }

    public VariantTypeVisuals.VariantClass getVariantClass() {
        return variantClass;
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
            () -> nodes.alleleFreqRangeSlider().getLowValue(),
            VariantFilter::getMinAlleleFraction,
            LooserWhen.CURRENT_LOWER);
        registerThresholdFilter(
            "maxAF",
            () -> nodes.alleleFreqRangeSlider().getHighValue(),
            VariantFilter::getMaxAlleleFraction,
            LooserWhen.CURRENT_HIGHER);

        registerSetFilter(
            "allowedTypes",
            this::currentAllowedTypes,
            f -> f.getAllowedTypes() == null ? Set.of() : Set.copyOf(f.getAllowedTypes()));
        registerSetFilter(
            "allowedEffects",
            this::currentAllowedEffects,
            f -> f.getAllowedEffects() == null ? Set.of() : Set.copyOf(f.getAllowedEffects()));
        // New INFO/FORMAT keys (and FILTER column) require a reload so values are persisted.
        registerSetFilter(
            "infoKeys",
            this::currentInfoKeys,
            f -> {
                Set<String> keys = f.getInfoFieldFilters() == null
                    ? Set.of()
                    : Set.copyOf(f.getInfoFieldFilters().keySet());
                return keys;
            });
        registerSetFilter(
            "formatKeys",
            this::currentFormatKeys,
            f -> {
                Set<String> keys = f.formatKeysToPersist();
                return keys == null || keys.isEmpty() ? Set.of() : Set.copyOf(keys);
            });

        registerFlagFilter(
            "cancerOnly",
            () -> nodes.cancerOnlyCheckBox().isSelected(),
            VariantFilter::isCancerGenesOnly);

        if (nodes.minSvLengthField() != null) {
            registerThresholdFilter(
                "minSvLen",
                () -> {
                    try {
                        String t = nodes.minSvLengthField().getText().trim();
                        return t.isEmpty() ? 0.0 : Double.parseDouble(t);
                    } catch (NumberFormatException e) {
                        return 0.0;
                    }
                },
                f -> (double) f.getMinSvLengthBp(),
                LooserWhen.CURRENT_LOWER);
        }
        if (nodes.maxSvLengthField() != null) {
            registerThresholdFilter(
                "maxSvLen",
                () -> {
                    try {
                        String t = nodes.maxSvLengthField().getText().trim();
                        return t.isEmpty() ? Double.MAX_VALUE : Double.parseDouble(t);
                    } catch (NumberFormatException e) {
                        return Double.MAX_VALUE;
                    }
                },
                f -> f.getMaxSvLengthBp() == Long.MAX_VALUE
                    ? Double.MAX_VALUE
                    : (double) f.getMaxSvLengthBp(),
                LooserWhen.CURRENT_HIGHER);
        }
    }

    /**
     * Load filter UI state. Caller must suppress apply events around this call.
     */
    public void loadFrom(VariantFilter filter) {
        if (nodes == null || filter == null) {
            return;
        }

        Set<VcfVariantType> types = filter.getAllowedTypes();
        for (Map.Entry<VcfVariantType, ColoredToggle> entry : variantTypeCheckBoxes.entrySet()) {
            entry.getValue().setSelected(types.contains(entry.getKey()));
        }

        Set<VariantEffect> effects = filter.getAllowedEffects();
        for (Map.Entry<EffectCategory, ColoredToggle> entry : effectCategoryCheckBoxes.entrySet()) {
            entry.getValue().setSelected(entry.getKey().matchesAllowed(effects));
        }

        nodes.qualitySlider().setValue(filter.getMinQuality());
        nodes.coverageSlider().setValue(filter.getMinDepth());
        nodes.alleleFreqRangeSlider().setRange(
            filter.getMinAlleleFraction(), filter.getMaxAlleleFraction());
        nodes.cancerOnlyCheckBox().setSelected(filter.isCancerGenesOnly());

        if (nodes.minSvLengthField() != null) {
            nodes.minSvLengthField().setText(Long.toString(Math.max(0, filter.getMinSvLengthBp())));
        }
        if (nodes.maxSvLengthField() != null) {
            long max = filter.getMaxSvLengthBp();
            nodes.maxSvLengthField().setText(max == Long.MAX_VALUE ? "" : Long.toString(max));
        }

        if (nodes.advancedFiltersContainer() != null) {
            nodes.advancedFiltersContainer().getChildren().clear();
            if (filter.getAllowedFilterValues() != null) {
                for (String filterValue : filter.getAllowedFilterValues()) {
                    addFilterTokenRow(filterValue, true, false);
                }
            }
            if (filter.getExcludedFilterValues() != null) {
                for (String filterValue : filter.getExcludedFilterValues()) {
                    addFilterTokenRow(filterValue, true, true);
                }
            }
            for (Map.Entry<String, String> entry : filter.getInfoFieldFilters().entrySet()) {
                addInfoRuleRow(InfoFieldRule.decode(entry.getKey(), entry.getValue()),
                    lookupInfoDef(entry.getKey()));
            }
            if (filter.getFormatFieldFilters() != null) {
                for (Map.Entry<String, String> entry : filter.getFormatFieldFilters().entrySet()) {
                    addFormatRuleRow(InfoFieldRule.decode(entry.getKey(), entry.getValue()),
                        lookupFormatDef(entry.getKey()));
                }
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
        filter.setMinAlleleFraction(nodes.alleleFreqRangeSlider().getLowValue());
        filter.setMaxAlleleFraction(nodes.alleleFreqRangeSlider().getHighValue());

        filter.setCancerGenesOnly(nodes.cancerOnlyCheckBox().isSelected());

        if (nodes.minSvLengthField() != null) {
            try {
                String text = nodes.minSvLengthField().getText().trim();
                filter.setMinSvLengthBp(text.isEmpty() ? 0 : Long.parseLong(text));
            } catch (NumberFormatException ignored) {
                filter.setMinSvLengthBp(0);
            }
        }
        if (nodes.maxSvLengthField() != null) {
            try {
                String text = nodes.maxSvLengthField().getText().trim();
                filter.setMaxSvLengthBp(text.isEmpty() ? Long.MAX_VALUE : Long.parseLong(text));
            } catch (NumberFormatException ignored) {
                filter.setMaxSvLengthBp(Long.MAX_VALUE);
            }
        }

        Map<String, String> infoFilters = new HashMap<>();
        Map<String, String> formatFilters = new HashMap<>();
        Set<String> allowedFilters = new HashSet<>();
        Set<String> excludedFilters = new HashSet<>();
        if (nodes.advancedFiltersContainer() != null) {
            for (javafx.scene.Node node : nodes.advancedFiltersContainer().getChildren()) {
                Object data = node.getUserData();
                if (data instanceof CustomFilterRow row) {
                    row.collectInto(infoFilters, formatFilters, allowedFilters, excludedFilters);
                }
            }
        }
        filter.setAllowedFilterValues(allowedFilters);
        filter.setExcludedFilterValues(excludedFilters);
        filter.setInfoFieldFilters(infoFilters);
        filter.setFormatFieldFilters(formatFilters);
    }

    private Set<String> currentInfoKeys() {
        Map<String, String> infoFilters = new HashMap<>();
        collectCustomFieldMaps(infoFilters, new HashMap<>());
        return Set.copyOf(infoFilters.keySet());
    }

    private Set<String> currentFormatKeys() {
        Map<String, String> formatFilters = new HashMap<>();
        collectCustomFieldMaps(new HashMap<>(), formatFilters);
        return Set.copyOf(formatFilters.keySet());
    }

    private void collectCustomFieldMaps(
            Map<String, String> infoFilters, Map<String, String> formatFilters) {
        if (nodes == null || nodes.advancedFiltersContainer() == null) {
            return;
        }
        for (javafx.scene.Node node : nodes.advancedFiltersContainer().getChildren()) {
            Object data = node.getUserData();
            if (data instanceof CustomFilterRow row) {
                row.collectInto(infoFilters, formatFilters, new HashSet<>(), new HashSet<>());
            }
        }
    }

    private Set<VcfVariantType> currentAllowedTypes() {
        Set<VcfVariantType> types = new HashSet<>();
        if (variantTypeCheckBoxes.isEmpty()) {
            // No types observed for this class yet — keep allowedTypes empty so legends /
            // checkboxes stay empty. Load paths expand via ensureUnobservedClassSlicesPassAll.
            // (Unchecking every checkbox leaves map entries with selected=false → also empty.)
            return types;
        }
        for (Map.Entry<VcfVariantType, ColoredToggle> entry : variantTypeCheckBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                types.add(entry.getKey());
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
        for (Map.Entry<EffectCategory, ColoredToggle> entry : effectCategoryCheckBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                effects.addAll(entry.getKey().effects);
            }
        }
        // Only selected categories pass. Effects that belong to a known category but have
        // no visible checkbox (historically Coding / Non-coding on point) must NOT be
        // auto-allowed — that kept non-coding genes visible when only Missense/Trunc
        // were checked. Orphan effects with no EffectCategory stay allowed.
        Set<VariantEffect> categorized = EnumSet.noneOf(VariantEffect.class);
        for (EffectCategory category : EffectCategory.values()) {
            categorized.addAll(category.effects);
        }
        for (VariantEffect effect : VariantEffect.values()) {
            if (!categorized.contains(effect)) {
                effects.add(effect);
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
        Set<VcfVariantType> present = collectPresentVariantTypes(sources);
        // Single source: types observed on open VCFs (recorded at stream time).
        present.addAll(VcfManager.getInstance().getSessionAvailableTypes());
        Set<VariantEffect> presentEffects = collectPresentVariantEffects(sources, variantClass);
        presentEffects.addAll(VcfManager.getInstance().getSessionAvailableEffects());
        if (variantClass != null) {
            present.removeIf(t -> t != null && !variantClass.contains(t));
        }
        populateVariantTypes(present, currentFilter);
        populateEffectCategories(presentEffects, currentFilter);
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
            for (Map.Entry<VcfVariantType, ColoredToggle> entry : variantTypeCheckBoxes.entrySet()) {
                previousSelection.put(entry.getKey(), entry.getValue().isSelected());
            }

            nodes.variantTypesContainer().getChildren().clear();
            variantTypeCheckBoxes.clear();

            Set<VcfVariantType> present = (presentTypes != null && !presentTypes.isEmpty())
                ? EnumSet.copyOf(presentTypes)
                : EnumSet.noneOf(VcfVariantType.class);

            Set<VcfVariantType> typesToShow = VariantTypeVisuals.typesForUi(present, variantClass);

            Set<VcfVariantType> filterTypes = currentFilter != null && currentFilter.getAllowedTypes() != null
                ? currentFilter.getAllowedTypes()
                : Set.of();
            // Empty allowedTypes means unobserved class (or exclude-all). Newly observed
            // types default on; an explicit non-empty filter drives selection.
            boolean filterDrivesSelection = !filterTypes.isEmpty();

            FlowPane typesFlow = nodes.variantTypesContainer();
            Set<VcfVariantType> placed = new HashSet<>();
            for (VcfVariantType type : typesToShow) {
                if (placed.contains(type)) {
                    continue;
                }
                ColoredToggle cb = new ColoredToggle(getVariantTypeLabel(type), VariantTypeVisuals.color(type));
                boolean selected;
                if (previousSelection.containsKey(type)) {
                    selected = Boolean.TRUE.equals(previousSelection.get(type));
                } else if (filterDrivesSelection) {
                    selected = filterTypes.contains(type);
                } else {
                    selected = true;
                }
                cb.setSelected(selected);
                cb.setPrefWidth(148);
                cb.setMaxWidth(160);
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

                typesFlow.getChildren().add(cb);
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
            for (Map.Entry<EffectCategory, ColoredToggle> entry : effectCategoryCheckBoxes.entrySet()) {
                previousSelection.put(entry.getKey(), entry.getValue().isSelected());
            }

            nodes.effectCategoriesContainer().getChildren().clear();
            effectCategoryCheckBoxes.clear();

            Set<VariantEffect> present = (presentEffects != null && !presentEffects.isEmpty())
                ? EnumSet.copyOf(presentEffects)
                : EnumSet.noneOf(VariantEffect.class);

            FlowPane effectsFlow = nodes.effectCategoriesContainer();
            for (EffectCategory category : EffectCategory.values()) {
                // Point UI: skip generic Coding (CODING_OTHER) — keep specific categories.
                // Non-coding stays visible so Missense/Trunc-only selections can exclude it.
                if (variantClass != VariantTypeVisuals.VariantClass.STRUCTURAL
                        && category == EffectCategory.CODING) {
                    continue;
                }
                if (variantClass == VariantTypeVisuals.VariantClass.STRUCTURAL
                        && category != EffectCategory.NONCODING
                        && category != EffectCategory.INTERGENIC) {
                    continue;
                }
                if (!category.matchesAny(present)) {
                    continue;
                }
                ColoredToggle cb = new ColoredToggle(effectCategoryLabel(category), category.color());
                // Prefer the live UI selection (like variant types) so gene navigation /
                // populate-on-revision does not wipe a missense-only choice from the filter
                // snapshot that was used at chromosome load time.
                boolean selected;
                if (previousSelection.containsKey(category)) {
                    selected = Boolean.TRUE.equals(previousSelection.get(category));
                } else if (!previousSelection.isEmpty()) {
                    // New category while user already chose effects → off (e.g. Non-coding
                    // appearing must not re-enable after Missense/Trunc-only).
                    selected = false;
                } else if (currentFilter != null) {
                    selected = category.matchesAllowed(currentFilter.getAllowedEffects());
                } else {
                    selected = true;
                }
                cb.setSelected(selected);
                cb.setPrefWidth(148);
                cb.setMaxWidth(160);
                cb.selectedProperty().addListener(effectCheckListener);
                effectCategoryCheckBoxes.put(category, cb);
                effectsFlow.getChildren().add(cb);
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
        for (ColoredToggle cb : new HashSet<>(variantTypeCheckBoxes.values())) {
            cb.setSelected(true);
        }
        if (nodes.selectAllTypesCheckBox() != null) {
            nodes.selectAllTypesCheckBox().setSelected(true);
        }
        for (ColoredToggle cb : effectCategoryCheckBoxes.values()) {
            cb.setSelected(true);
        }
        if (nodes.selectAllEffectsCheckBox() != null) {
            nodes.selectAllEffectsCheckBox().setSelected(true);
        }
        nodes.qualitySlider().setValue(0);
        nodes.coverageSlider().setValue(0);
        nodes.alleleFreqRangeSlider().resetToFullRange();
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

    public Map<VcfVariantType, ColoredToggle> getVariantTypeCheckBoxes() {
        return variantTypeCheckBoxes;
    }

    /** Types currently shown as checkboxes (for session persistence). */
    public Set<VcfVariantType> snapshotShownVariantTypes() {
        return variantTypeCheckBoxes.isEmpty()
            ? EnumSet.noneOf(VcfVariantType.class)
            : EnumSet.copyOf(variantTypeCheckBoxes.keySet());
    }

    /** Effects covered by currently shown effect-category checkboxes. */
    public Set<VariantEffect> snapshotShownVariantEffects() {
        EnumSet<VariantEffect> effects = EnumSet.noneOf(VariantEffect.class);
        for (EffectCategory category : effectCategoryCheckBoxes.keySet()) {
            effects.addAll(category.effects);
        }
        return effects;
    }

    public void clearEffectCategoryCheckBoxes() {
        effectCategoryCheckBoxes.clear();
    }

    public void cancelPendingFilterTimers() {
        // Debounce lives in VariantManagerController.
    }

    public void showInfoFilterDialog() {
        showAddFieldDialog(VcfHeaderFieldDef.Kind.INFO);
    }

    public void showInfoFilterDialog(String prefillFieldId) {
        showAddFieldDialog(VcfHeaderFieldDef.Kind.INFO, prefillFieldId);
    }

    public void showFilterFieldDialog() {
        showAddFieldDialog(VcfHeaderFieldDef.Kind.FILTER);
    }

    public void showFilterFieldDialog(String prefillFilterId) {
        showAddFieldDialog(VcfHeaderFieldDef.Kind.FILTER, prefillFilterId);
    }

    public void showCustomFilterDialog() {
        showAddFieldDialog();
    }

    public void showHeaderFieldsDialog() {
        showAddFieldDialog();
    }

    public void showAddFieldDialog() {
        showAddFieldDialog(null, null);
    }

    public void showAddFieldDialog(VcfHeaderFieldDef.Kind preferredKind) {
        showAddFieldDialog(preferredKind, null);
    }

    /**
     * Unified picker for INFO / FILTER / FORMAT header fields. Adds a checkbox or
     * text control under the default sliders.
     */
    public void showAddFieldDialog(VcfHeaderFieldDef.Kind preferredKind, String prefillId) {
        Dialog<VcfHeaderFieldDef> dialog = new Dialog<>();
        dialog.setTitle("Add field");
        dialog.setHeaderText(
            "Choose an INFO, FILTER, or FORMAT field from open VCF headers.\n"
                + "A checkbox or text control is added below the default sliders.");

        ButtonType addButtonType = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addButtonType, ButtonType.CANCEL);

        ListView<VcfHeaderFieldDef> infoList =
            headerFieldListView(VcfManager.getInstance().getSessionInfoHeaderFields());
        ListView<VcfHeaderFieldDef> filterList =
            headerFieldListView(VcfManager.getInstance().getSessionFilterHeaderFields());
        ListView<VcfHeaderFieldDef> formatList =
            headerFieldListView(VcfManager.getInstance().getSessionFormatHeaderFields());

        TextArea detail = new TextArea();
        detail.setEditable(false);
        detail.setWrapText(true);
        detail.setPrefRowCount(4);
        detail.setPromptText("Select a field to see Type / Number / Description.");

        Label typeHint = new Label();
        typeHint.setWrapText(true);
        typeHint.setStyle("-fx-font-size: 11px;");
        typeHint.setMaxWidth(520);

        ChangeListener<VcfHeaderFieldDef> detailListener = (obs, oldVal, newVal) -> {
            if (newVal == null) {
                detail.clear();
                typeHint.setText("");
                return;
            }
            detail.setText(newVal.tooltipText());
            InfoFieldRule.UiKind kind = InfoFieldRule.uiKindFor(newVal);
            typeHint.setText(switch (newVal.kind()) {
                case FILTER ->
                    "Adds a FILTER checkbox (Include / Exclude). Reloads variants to load FILTER values.";
                case FORMAT -> switch (kind) {
                    case FLAG -> "Adds a FORMAT present checkbox. Reloads variants to load this field.";
                    case NUMBER_PAIR -> "Adds one threshold on the sum of comma-separated values (e.g. PR). Reloads to load values.";
                    case NUMBER -> "Adds min/max number fields. Reloads variants to load this field.";
                    default -> "Adds a FORMAT text field. Reloads variants to load this field.";
                };
                case INFO -> switch (kind) {
                    case FLAG -> "Adds an INFO present checkbox. Reloads variants to load this field.";
                    case NUMBER_PAIR -> "Adds one sum≥ threshold. Reloads variants to load this field.";
                    case NUMBER -> "Adds min/max number fields. Reloads variants to load this field.";
                    case CSV -> "Adds a comma-separated token field (Any / All). Reloads to load values.";
                    default -> "Adds an INFO text field. Reloads variants to load this field.";
                };
            });
        };
        infoList.getSelectionModel().selectedItemProperty().addListener(detailListener);
        filterList.getSelectionModel().selectedItemProperty().addListener(detailListener);
        formatList.getSelectionModel().selectedItemProperty().addListener(detailListener);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
            new Tab("INFO (" + infoList.getItems().size() + ")", infoList),
            new Tab("FILTER (" + filterList.getItems().size() + ")", filterList),
            new Tab("FORMAT (" + formatList.getItems().size() + ")", formatList));
        if (preferredKind == VcfHeaderFieldDef.Kind.FILTER) {
            tabs.getSelectionModel().select(1);
        } else if (preferredKind == VcfHeaderFieldDef.Kind.FORMAT) {
            tabs.getSelectionModel().select(2);
        } else {
            tabs.getSelectionModel().select(0);
        }
        if (prefillId != null && !prefillId.isBlank()) {
            ListView<VcfHeaderFieldDef> list = switch (tabs.getSelectionModel().getSelectedIndex()) {
                case 1 -> filterList;
                case 2 -> formatList;
                default -> infoList;
            };
            for (VcfHeaderFieldDef def : list.getItems()) {
                if (prefillId.equalsIgnoreCase(def.id())) {
                    list.getSelectionModel().select(def);
                    list.scrollTo(def);
                    break;
                }
            }
        }

        VBox content = new VBox(10, tabs, detail, typeHint);
        content.setPadding(new Insets(10));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(560, 520);

        Runnable closeWithSelected = () -> {
            VcfHeaderFieldDef selected = switch (tabs.getSelectionModel().getSelectedIndex()) {
                case 1 -> filterList.getSelectionModel().getSelectedItem();
                case 2 -> formatList.getSelectionModel().getSelectedItem();
                default -> infoList.getSelectionModel().getSelectedItem();
            };
            if (selected != null) {
                dialog.setResult(selected);
                dialog.close();
            }
        };

        infoList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                closeWithSelected.run();
            }
        });
        filterList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                closeWithSelected.run();
            }
        });
        formatList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                closeWithSelected.run();
            }
        });

        dialog.setResultConverter(button -> {
            if (button != addButtonType) {
                return null;
            }
            return switch (tabs.getSelectionModel().getSelectedIndex()) {
                case 1 -> filterList.getSelectionModel().getSelectedItem();
                case 2 -> formatList.getSelectionModel().getSelectedItem();
                default -> infoList.getSelectionModel().getSelectedItem();
            };
        });

        dialog.showAndWait().ifPresent(def -> {
            addFieldFromHeader(def);
            scheduleImmediateChange();
        });
    }

    private void addFieldFromHeader(VcfHeaderFieldDef def) {
        if (def == null) {
            return;
        }
        if (def.kind() == VcfHeaderFieldDef.Kind.FILTER) {
            addFilterTokenRow(def.id(), true, false);
            return;
        }
        InfoFieldRule.UiKind ui = InfoFieldRule.uiKindFor(def);
        InfoFieldRule initial = switch (ui) {
            case FLAG -> InfoFieldRule.flagPresent(def.id());
            case NUMBER -> InfoFieldRule.numberRange(def.id(), null, null);
            case NUMBER_PAIR -> InfoFieldRule.numberPair(def.id(), null, null);
            case CSV -> InfoFieldRule.csvAny(def.id(), "");
            default -> InfoFieldRule.equals(def.id(), "");
        };
        if (def.kind() == VcfHeaderFieldDef.Kind.FORMAT) {
            addFormatRuleRow(initial, def);
        } else {
            addInfoRuleRow(initial, def);
        }
    }

    private static ListView<VcfHeaderFieldDef> headerFieldListView(List<VcfHeaderFieldDef> fields) {
        ListView<VcfHeaderFieldDef> list = new ListView<>(FXCollections.observableArrayList(fields));
        list.setCellFactory(view -> new ListCell<>() {
            private final Tooltip tip = new Tooltip();
            {
                tip.setWrapText(true);
                tip.setMaxWidth(420);
            }
            @Override
            protected void updateItem(VcfHeaderFieldDef item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item.displayLabel());
                    tip.setText(item.tooltipText());
                    setTooltip(tip);
                }
            }
        });
        if (fields.isEmpty()) {
            list.setPlaceholder(new Label("No definitions in open VCF headers."));
        }
        return list;
    }

    public void addInfoFilterRule(String fieldName, String fieldValue) {
        addInfoRuleRow(InfoFieldRule.decode(fieldName, fieldValue), lookupInfoDef(fieldName));
    }

    public void addFilterFieldRule(String filterValue) {
        addFilterTokenRow(filterValue, true, false);
    }

    private void addInfoRuleRow(InfoFieldRule rule, VcfHeaderFieldDef def) {
        if (nodes == null || nodes.advancedFiltersContainer() == null || rule == null) {
            return;
        }
        InfoFieldRule.UiKind ui = def != null
            ? InfoFieldRule.uiKindFor(def)
            : uiKindFromRule(rule);

        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("custom-filter-row");

        Label name = new Label("INFO." + rule.fieldId());
        name.getStyleClass().add("filter-slider-label");
        name.setMinWidth(Region.USE_PREF_SIZE);
        if (def != null) {
            Tooltip tip = new Tooltip(def.tooltipText());
            tip.setWrapText(true);
            tip.setMaxWidth(420);
            name.setTooltip(tip);
        }

        Button removeBtn = removeRowButton(row);
        CustomFilterRow collector = buildTypedRuleCollector(
            rule, ui, name, removeBtn, row, true);

        row.setUserData(collector);
        nodes.advancedFiltersContainer().getChildren().add(row);
    }

    private void addFormatRuleRow(InfoFieldRule rule, VcfHeaderFieldDef def) {
        if (nodes == null || nodes.advancedFiltersContainer() == null || rule == null) {
            return;
        }
        InfoFieldRule.UiKind ui = def != null
            ? InfoFieldRule.uiKindFor(def)
            : uiKindFromRule(rule);

        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("custom-filter-row");

        Label name = new Label("FORMAT." + rule.fieldId());
        name.getStyleClass().add("filter-slider-label");
        name.setMinWidth(Region.USE_PREF_SIZE);
        if (def != null) {
            Tooltip tip = new Tooltip(def.tooltipText());
            tip.setWrapText(true);
            tip.setMaxWidth(420);
            name.setTooltip(tip);
        } else {
            attachHeaderTooltip(name, VcfHeaderFieldDef.Kind.FORMAT, rule.fieldId());
        }

        Button removeBtn = removeRowButton(row);
        CustomFilterRow collector = buildTypedRuleCollector(
            rule, ui, name, removeBtn, row, false);

        row.setUserData(collector);
        nodes.advancedFiltersContainer().getChildren().add(row);
    }

    private static InfoFieldRule.UiKind uiKindFromRule(InfoFieldRule rule) {
        return switch (rule.mode()) {
            case FLAG_PRESENT -> InfoFieldRule.UiKind.FLAG;
            case NUMBER_RANGE -> InfoFieldRule.UiKind.NUMBER;
            case NUMBER_PAIR -> InfoFieldRule.UiKind.NUMBER_PAIR;
            case CSV_ANY, CSV_ALL -> InfoFieldRule.UiKind.CSV;
            default -> InfoFieldRule.UiKind.TEXT;
        };
    }

    /**
     * @param toInfo when true write into INFO map; otherwise FORMAT map
     */
    private CustomFilterRow buildTypedRuleCollector(
            InfoFieldRule rule,
            InfoFieldRule.UiKind ui,
            Label name,
            Button removeBtn,
            HBox row,
            boolean toInfo) {
        return switch (ui) {
            case FLAG -> {
                ColoredToggle present = new ColoredToggle("present");
                present.setSelected(
                    rule.mode() == InfoFieldRule.Mode.FLAG_PRESENT || rule.textValue().isBlank());
                present.selectedProperty().addListener((obs, o, n) -> scheduleImmediateChange());
                row.getChildren().addAll(name, present, removeBtn);
                yield (info, format, allowed, excluded) -> {
                    if (present.isSelected()) {
                        putRule(toInfo, info, format, rule.fieldId(),
                            InfoFieldRule.flagPresent(rule.fieldId()).encode());
                    }
                };
            }
            case NUMBER -> {
                String minInit = "";
                String maxInit = "";
                if (rule.mode() == InfoFieldRule.Mode.NUMBER_RANGE) {
                    minInit = rule.min() != null ? stripTrailingDotZero(rule.min()) : "";
                    maxInit = rule.max() != null ? stripTrailingDotZero(rule.max()) : "";
                } else if (rule.mode() == InfoFieldRule.Mode.EQUALS
                        && parseOptionalDouble(rule.textValue()) != null) {
                    minInit = rule.textValue().trim();
                    maxInit = minInit;
                }
                TextField minField = new TextField(minInit);
                TextField maxField = new TextField(maxInit);
                minField.setPrefWidth(56);
                maxField.setPrefWidth(56);
                minField.getStyleClass().add("filter-field");
                maxField.getStyleClass().add("filter-field");
                minField.setPromptText("min");
                maxField.setPromptText("max");
                Label dash = new Label("–");
                dash.getStyleClass().add("subsection-label");
                minField.textProperty().addListener((obs, o, n) -> scheduleDebouncedChange());
                maxField.textProperty().addListener((obs, o, n) -> scheduleDebouncedChange());
                minField.setOnAction(e -> scheduleImmediateChange());
                maxField.setOnAction(e -> scheduleImmediateChange());
                row.getChildren().addAll(name, minField, dash, maxField, removeBtn);
                yield (info, format, allowed, excluded) -> {
                    Double min = parseOptionalDouble(minField.getText());
                    Double max = parseOptionalDouble(maxField.getText());
                    if (min == null && max == null) {
                        // Still register the key so reload can persist values on demand.
                        putRule(toInfo, info, format, rule.fieldId(),
                            InfoFieldRule.numberRange(rule.fieldId(), null, null).encode());
                        return;
                    }
                    putRule(toInfo, info, format, rule.fieldId(),
                        InfoFieldRule.numberRange(rule.fieldId(), min, max).encode());
                };
            }
            case NUMBER_PAIR -> {
                String minInit = rule.mode() == InfoFieldRule.Mode.NUMBER_PAIR && rule.min() != null
                    ? stripTrailingDotZero(rule.min()) : "";
                TextField sumField = new TextField(minInit);
                sumField.setPrefWidth(72);
                sumField.getStyleClass().add("filter-field");
                sumField.setPromptText("sum ≥");
                sumField.setTooltip(new Tooltip(
                    "Comma-separated values are summed (e.g. PR strands), then compared to this minimum."));
                sumField.textProperty().addListener((obs, o, n) -> scheduleDebouncedChange());
                sumField.setOnAction(e -> scheduleImmediateChange());
                row.getChildren().addAll(name, sumField, removeBtn);
                yield (info, format, allowed, excluded) -> {
                    Double minSum = parseOptionalDouble(sumField.getText());
                    if (minSum == null) {
                        // Keep the key registered so a reload can persist values.
                        putRule(toInfo, info, format, rule.fieldId(),
                            InfoFieldRule.numberPair(rule.fieldId(), null, null).encode());
                        return;
                    }
                    putRule(toInfo, info, format, rule.fieldId(),
                        InfoFieldRule.numberPair(rule.fieldId(), minSum, null).encode());
                };
            }
            case CSV -> {
                ComboBox<String> mode = new ComboBox<>();
                mode.getItems().addAll("Any", "All");
                mode.setValue(rule.mode() == InfoFieldRule.Mode.CSV_ALL ? "All" : "Any");
                mode.setMaxWidth(72);
                TextField values = new TextField(rule.textValue());
                values.setPromptText("e.g. FWD,REV");
                values.getStyleClass().add("filter-field");
                HBox.setHgrow(values, Priority.ALWAYS);
                mode.valueProperty().addListener((obs, o, n) -> scheduleImmediateChange());
                values.textProperty().addListener((obs, o, n) -> scheduleDebouncedChange());
                values.setOnAction(e -> scheduleImmediateChange());
                row.getChildren().addAll(name, mode, values, removeBtn);
                yield (info, format, allowed, excluded) -> {
                    String csv = values.getText() != null ? values.getText().trim() : "";
                    // Empty value still registers the key for on-demand persist/reload.
                    InfoFieldRule encoded = "All".equals(mode.getValue())
                        ? InfoFieldRule.csvAll(rule.fieldId(), csv)
                        : InfoFieldRule.csvAny(rule.fieldId(), csv);
                    putRule(toInfo, info, format, rule.fieldId(), encoded.encode());
                };
            }
            default -> {
                TextField value = new TextField(rule.textValue());
                value.setPromptText("exact value");
                value.getStyleClass().add("filter-field");
                HBox.setHgrow(value, Priority.ALWAYS);
                value.textProperty().addListener((obs, o, n) -> scheduleDebouncedChange());
                value.setOnAction(e -> scheduleImmediateChange());
                row.getChildren().addAll(name, value, removeBtn);
                yield (info, format, allowed, excluded) -> {
                    String text = value.getText() != null ? value.getText().trim() : "";
                    // Empty value still registers the key for on-demand persist/reload.
                    putRule(toInfo, info, format, rule.fieldId(),
                        InfoFieldRule.equals(rule.fieldId(), text).encode());
                };
            }
        };
    }

    private static void putRule(
            boolean toInfo,
            Map<String, String> info,
            Map<String, String> format,
            String fieldId,
            String encoded) {
        if (toInfo) {
            info.put(fieldId, encoded);
        } else {
            format.put(fieldId, encoded);
        }
    }

    /**
     * @param exclude when true, checked token goes to the exclude set
     */
    private void addFilterTokenRow(String filterValue, boolean selected, boolean exclude) {
        if (nodes == null || nodes.advancedFiltersContainer() == null
                || filterValue == null || filterValue.isBlank()) {
            return;
        }
        for (javafx.scene.Node child : nodes.advancedFiltersContainer().getChildren()) {
            Object marker = child.getProperties().get(FilterTokenMarker.class);
            if (marker instanceof FilterTokenMarker token && filterValue.equals(token.token())) {
                return;
            }
        }

        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("custom-filter-row");

        ColoredToggle check = new ColoredToggle("FILTER." + filterValue);
        check.setSelected(selected);
        Tooltip tip = new Tooltip("FILTER token: " + filterValue);
        for (VcfHeaderFieldDef def : VcfManager.getInstance().getSessionFilterHeaderFields()) {
            if (filterValue.equalsIgnoreCase(def.id())) {
                tip.setText(def.tooltipText());
                tip.setWrapText(true);
                tip.setMaxWidth(420);
                break;
            }
        }
        check.setTooltip(tip);
        check.selectedProperty().addListener((obs, o, n) -> scheduleImmediateChange());

        ComboBox<String> mode = new ComboBox<>();
        mode.getItems().addAll("Include", "Exclude");
        mode.setValue(exclude ? "Exclude" : "Include");
        mode.setMaxWidth(100);
        mode.setTooltip(new Tooltip(
            "Include: keep variants with this FILTER token.\n"
                + "Exclude: hide variants with this FILTER token."));
        mode.valueProperty().addListener((obs, o, n) -> scheduleImmediateChange());
        mode.disableProperty().bind(check.selectedProperty().not());

        Button removeBtn = removeRowButton(row);
        row.getChildren().addAll(check, mode, removeBtn);
        row.setUserData((CustomFilterRow) (info, format, allowed, excluded) -> {
            if (!check.isSelected()) {
                return;
            }
            if ("Exclude".equals(mode.getValue())) {
                excluded.add(filterValue);
            } else {
                allowed.add(filterValue);
            }
        });
        row.getProperties().put(FilterTokenMarker.class, new FilterTokenMarker(filterValue));
        nodes.advancedFiltersContainer().getChildren().add(row);
    }


    private Button removeRowButton(HBox row) {
        Button removeBtn = new Button("×");
        removeBtn.getStyleClass().add("secondary-button");
        removeBtn.setStyle("-fx-font-size: 12px; -fx-padding: 1 6 1 6;");
        removeBtn.setOnAction(e -> {
            if (nodes != null && nodes.advancedFiltersContainer() != null) {
                nodes.advancedFiltersContainer().getChildren().remove(row);
            }
            scheduleImmediateChange();
        });
        return removeBtn;
    }

    private static VcfHeaderFieldDef lookupInfoDef(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (VcfHeaderFieldDef def : VcfManager.getInstance().getSessionInfoHeaderFields()) {
            if (id.equalsIgnoreCase(def.id())) {
                return def;
            }
        }
        return null;
    }

    private static VcfHeaderFieldDef lookupFormatDef(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (VcfHeaderFieldDef def : VcfManager.getInstance().getSessionFormatHeaderFields()) {
            if (id.equalsIgnoreCase(def.id())) {
                return def;
            }
        }
        return null;
    }

    private static Double parseOptionalDouble(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stripTrailingDotZero(double v) {
        if (Math.rint(v) == v && Math.abs(v) < 1e12) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    @FunctionalInterface
    private interface CustomFilterRow {
        void collectInto(
            Map<String, String> infoFilters,
            Map<String, String> formatFilters,
            Set<String> allowedFilterTokens,
            Set<String> excludedFilterTokens);
    }

    private record FilterTokenMarker(String token) {}

    private static void attachHeaderTooltip(Label label, VcfHeaderFieldDef.Kind kind, String id) {
        if (label == null || id == null || id.isBlank()) {
            return;
        }
        List<VcfHeaderFieldDef> fields = switch (kind) {
            case INFO -> VcfManager.getInstance().getSessionInfoHeaderFields();
            case FILTER -> VcfManager.getInstance().getSessionFilterHeaderFields();
            case FORMAT -> VcfManager.getInstance().getSessionFormatHeaderFields();
        };
        for (VcfHeaderFieldDef def : fields) {
            if (id.equalsIgnoreCase(def.id())) {
                Tooltip tip = new Tooltip(def.tooltipText());
                tip.setWrapText(true);
                tip.setMaxWidth(420);
                label.setTooltip(tip);
                return;
            }
        }
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
        javafx.beans.value.ChangeListener<Number> afListener = (obs, oldVal, newVal) -> {
            refreshReloadBannerFromFilters();
            scheduleDebouncedChange();
        };
        nodes.alleleFreqRangeSlider().lowValueProperty().addListener(afListener);
        nodes.alleleFreqRangeSlider().highValueProperty().addListener(afListener);
    }

    private void bindThresholdSlider(
            Slider slider,
            TextField field,
            Label valueLabel,
            java.util.function.Function<Double, String> format) {
        slider.valueProperty().addListener((obs, oldVal, newVal) -> {
            String text = format.apply(newVal.doubleValue());
            if (valueLabel != null) {
                valueLabel.setText(text);
            }
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
            for (ColoredToggle cb : new HashSet<>(variantTypeCheckBoxes.values())) {
                cb.setSelected(selectAll);
            }
            localSuppress = false;
            refreshReloadBannerFromFilters();
            scheduleImmediateChange();
        });

        nodes.selectAllEffectsCheckBox().setOnAction(e -> {
            boolean selectAll = nodes.selectAllEffectsCheckBox().isSelected();
            localSuppress = true;
            for (ColoredToggle cb : effectCategoryCheckBoxes.values()) {
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
        if (nodes.minSvLengthField() != null) {
            nodes.minSvLengthField().setOnAction(e -> scheduleImmediateChange());
            nodes.minSvLengthField().focusedProperty().addListener((obs, was, is) -> {
                if (was && !is) {
                    scheduleImmediateChange();
                }
            });
        }
        if (nodes.maxSvLengthField() != null) {
            nodes.maxSvLengthField().setOnAction(e -> scheduleImmediateChange());
            nodes.maxSvLengthField().focusedProperty().addListener((obs, was, is) -> {
                if (was && !is) {
                    scheduleImmediateChange();
                }
            });
        }
    }

    private void updateSelectAllEffectsState() {
        if (nodes == null || nodes.selectAllEffectsCheckBox() == null) {
            return;
        }
        boolean previousLocalSuppress = localSuppress;
        localSuppress = true;
        try {
            boolean allSelected = !effectCategoryCheckBoxes.isEmpty();
            for (ColoredToggle cb : effectCategoryCheckBoxes.values()) {
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
            for (ColoredToggle cb : variantTypeCheckBoxes.values()) {
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
            List<VcfManager.CachedChromosomeVariants> sources,
            VariantTypeVisuals.VariantClass variantClass) {
        Set<VariantEffect> combined = EnumSet.noneOf(VariantEffect.class);
        if (sources == null) {
            return combined;
        }
        for (VcfManager.CachedChromosomeVariants cached : sources) {
            VariantList variants = cached.variants();
            if (variants == null || variants.isEmpty()) {
                continue;
            }
            combined.addAll(variants.collectVariantEffects(variantClass));
        }
        return combined;
    }

    /** Display label for effect checkboxes (SV uses gene-overlap wording). */
    private String effectCategoryLabel(EffectCategory category) {
        if (variantClass == VariantTypeVisuals.VariantClass.STRUCTURAL) {
            return switch (category) {
                case NONCODING -> "Gene-overlapping";
                case INTERGENIC -> "No gene";
                default -> category.label;
            };
        }
        return category.label;
    }
}

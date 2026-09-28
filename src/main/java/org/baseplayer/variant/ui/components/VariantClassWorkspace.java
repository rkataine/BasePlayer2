package org.baseplayer.variant.ui.components;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.baseplayer.io.VcfManager;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.annotation.VariantEffect;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * Per-mode (Point or SV) bundle of shared panel APIs.
 * Point and SV stay separate instances with the same baseline {@code loadFrom}/{@code writeTo} surface.
 * Result tables stay controller-owned (concrete {@code VariantTable} / {@code SvVariantTable}).
 */
public final class VariantClassWorkspace {

    private final VariantTypeVisuals.VariantClass mode;
    private final Tab modeTab;

    private VariantFiltersPanel filters;
    private SampleComparisonPanel comparison;
    private ControlFilesPanel control;
    private TabPane toolTabPane;

    public VariantClassWorkspace(VariantTypeVisuals.VariantClass mode, Tab modeTab) {
        this.mode = mode;
        this.modeTab = modeTab;
    }

    public VariantTypeVisuals.VariantClass mode() {
        return mode;
    }

    public Tab modeTab() {
        return modeTab;
    }

    public VariantFiltersPanel filters() {
        return filters;
    }

    public SampleComparisonPanel comparison() {
        return comparison;
    }

    public ControlFilesPanel control() {
        return control;
    }

    public TabPane toolTabPane() {
        return toolTabPane;
    }

    public void setFilters(VariantFiltersPanel filters) {
        this.filters = filters;
    }

    public void setComparison(SampleComparisonPanel comparison) {
        this.comparison = comparison;
    }

    public void setControl(ControlFilesPanel control) {
        this.control = control;
    }

    public void setToolTabPane(TabPane toolTabPane) {
        this.toolTabPane = toolTabPane;
    }

    /** Build a class slice from this workspace's filter + comparison panels. */
    public VariantFilter writeSlice() {
        VariantFilter slice = new VariantFilter();
        if (filters != null) {
            filters.writeTo(slice);
        } else {
            // No filter UI yet — allow the whole class so future VCFs of this mode can load.
            slice.setAllowedTypes(mode.allTypes());
            if (mode == VariantTypeVisuals.VariantClass.STRUCTURAL) {
                slice.setAllowedEffects(EnumSet.allOf(VariantEffect.class));
            }
        }
        if (comparison != null) {
            comparison.writeTo(slice);
        }
        return slice;
    }

    public void loadSlice(VariantFilter slice) {
        if (slice == null) {
            return;
        }
        if (filters != null) {
            filters.loadFrom(slice);
        }
        if (comparison != null) {
            comparison.loadFrom(slice);
        }
    }

    public void populateTypes(
            List<VcfManager.CachedChromosomeVariants> sources,
            VariantFilter filter) {
        if (filters != null) {
            filters.populateVariantTypes(sources, filter);
        }
    }

    public void captureFilterSnapshot(VariantFilter filter) {
        if (filters != null) {
            filters.captureFilterSnapshot(filter);
        }
    }

    public void hideReloadBanner() {
        if (filters != null) {
            filters.hideReloadBanner();
        }
    }

    public void refreshReloadBannerState(boolean needed, String message) {
        if (filters != null) {
            filters.refreshReloadBannerState(needed, message);
        }
    }

    public boolean anyFilterLooserThanSnapshot() {
        return filters != null && filters.anyFilterLooserThanSnapshot();
    }

    public void clearTypeAndEffectCheckboxes() {
        if (filters != null) {
            filters.hideReloadBanner();
            filters.getVariantTypeCheckBoxes().clear();
            filters.clearEffectCategoryCheckBoxes();
        }
    }

    public Set<VcfVariantType> snapshotShownVariantTypes() {
        return filters != null
            ? filters.snapshotShownVariantTypes()
            : EnumSet.noneOf(VcfVariantType.class);
    }

    public Set<VariantEffect> snapshotShownVariantEffects() {
        return filters != null
            ? filters.snapshotShownVariantEffects()
            : EnumSet.noneOf(VariantEffect.class);
    }

    public void refreshComparisonGroups() {
        if (comparison != null) {
            comparison.refreshGroups();
        }
    }

    public void syncSharedSampleRangeBounds() {
        if (comparison != null) {
            comparison.syncSharedSampleRangeBounds();
        }
    }
}

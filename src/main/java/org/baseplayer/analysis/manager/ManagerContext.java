package org.baseplayer.analysis.manager;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.baseplayer.io.VcfManager;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.ui.components.ControlFilesPanel;
import org.baseplayer.variant.ui.components.VariantClassWorkspace;

import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * State shared by the Analysis Manager shell and its collaborators.
 * The shell controller creates one instance; collaborators read/write fields directly
 * (FX thread only unless a field is {@code volatile}).
 */
final class ManagerContext {

    final ModeHost modeHost;
    final AnalysisBusyGate busyGate;

    VcfManager vcfManager;
    Stage stage;
    volatile boolean disposed;
    boolean suppressFilterApplyEvents;

    VariantList sourceVariants;
    List<VcfManager.CachedChromosomeVariants> sourceVariantLists = List.of();
    String chromosome;
    long lastSeenVariantsRevision = -1;
    String pendingScrollToChromosome;
    VariantFilter pendingReloadFilter;

    ManagerContext(ModeHost modeHost, AnalysisBusyGate busyGate) {
        this.modeHost = modeHost;
        this.busyGate = busyGate;
    }

    VariantFilter buildFilterFromUI() {
        return modeHost.buildFilterFromUI();
    }

    List<VariantClassWorkspace> workspaces() {
        return modeHost.workspaces();
    }

    ControlFilesPanel pointControl() {
        VariantClassWorkspace ws = modeHost.point();
        return ws != null ? ws.control() : null;
    }

    ControlFilesPanel svControl() {
        VariantClassWorkspace ws = modeHost.sv();
        return ws != null ? ws.control() : null;
    }

    boolean isAllChromosomeAnnotationRunning() {
        return busyGate.isAllChromosomeAnnotationRunning();
    }

    boolean isCurrentChromosomeStillLoading() {
        return chromosome != null && !chromosome.isBlank() && vcfManager.isLoadingChromosome(chromosome);
    }

    List<String> referenceChromosomeOrder() {
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        if (!stackManager.isEmpty()) {
            org.baseplayer.draw.DrawStack drawStack = stackManager.getFirst();
            if (drawStack != null && drawStack.chromosomeDropdown != null) {
                return new ArrayList<>(drawStack.chromosomeDropdown.getItems());
            }
        }
        return List.of();
    }

    List<VcfManager.CachedChromosomeVariants> cachedVariantSources() {
        if (vcfManager == null) {
            return List.of();
        }
        return vcfManager.getCachedVariantListsInOrder(referenceChromosomeOrder());
    }

    /** Filter that produced the loaded lists (fallback: current filter). */
    VariantFilter loadedOrCurrentFilter() {
        VariantFilter filter = vcfManager.getCurrentLoadedFilter();
        return filter != null ? filter : vcfManager.getCurrentFilter();
    }

    /** Types actually present in cached chromosome variant lists. */
    Set<VcfVariantType> collectLoadedVariantTypes() {
        Set<VcfVariantType> types = EnumSet.noneOf(VcfVariantType.class);
        List<VcfManager.CachedChromosomeVariants> sources = sourceVariantLists;
        if (sources == null || sources.isEmpty()) {
            sources = cachedVariantSources();
        }
        for (VcfManager.CachedChromosomeVariants cached : sources) {
            if (cached != null && cached.variants() != null) {
                types.addAll(cached.variants().collectVariantTypes());
            }
        }
        return types;
    }

    void refreshReloadBannerState() {
        boolean needed = pendingReloadFilter != null;
        for (VariantClassWorkspace ws : workspaces()) {
            ws.refreshReloadBannerState(needed, "Reload needed");
        }
    }

    void hideReloadBanners() {
        for (VariantClassWorkspace ws : workspaces()) {
            ws.hideReloadBanner();
        }
    }

    void syncSharedSampleRangeBounds() {
        boolean previousSuppress = suppressFilterApplyEvents;
        suppressFilterApplyEvents = true;
        try {
            for (VariantClassWorkspace ws : workspaces()) {
                ws.syncSharedSampleRangeBounds();
            }
        } finally {
            suppressFilterApplyEvents = previousSuppress;
        }
    }

    /** Region-load worker phase: ThreadRunner suffix + Analysis status chrome. */
    void postLoadPhase(ThreadRunner.RunnerTask task, String status) {
        if (task != null && status != null) {
            task.setProgressSuffix("— " + status);
            ThreadRunner.get().notifyDescriptionChanged();
        }
        Platform.runLater(() -> {
            if (!disposed) {
                busyGate.setStatus(status);
            }
        });
    }

    SampleRegistry sampleRegistry() {
        return ServiceRegistry.getInstance().getSampleRegistry();
    }
}

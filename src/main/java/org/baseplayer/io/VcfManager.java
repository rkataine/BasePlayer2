package org.baseplayer.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.services.ViewportState;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantLoader;
import org.baseplayer.variant.annotation.VariantAnnotator;
import org.baseplayer.variant.annotation.TranscriptCdsCache;

import javafx.application.Platform;
import javafx.stage.Window;

/**
 * Singleton manager for VCF variant files.
 * Handles loading multiple VCF files and updating variants when genomic region changes.
 * Supports merging variants from multiple VCFs into a single view.
 * 
 * Strategy: 
 * - Keep a list of all loaded VCF files (VcfData objects)
 * - When loading variants for a chromosome, merge variants from all VCFs
 * - Cache merged variant lists to avoid redundant loads
 */
public class VcfManager {
    
    private static final VcfManager INSTANCE = new VcfManager();
    
    // Track all loaded VCF files (supports multiple concurrent VCFs)
    private final List<VcfData> loadedVcfs = new ArrayList<>();
    
    // Last loaded chromosome (to avoid redundant loads)
    private String lastLoadedChromosome;
    
    // Single variant list for the currently active chromosome (cleared on chromosome change)
    private VariantList currentVariants;

    // How many VcfData objects from loadedVcfs are already in currentVariants
    private int loadedVcfCountForCurrentChromosome = 0;

    // Whether a background load is in progress
    private boolean loading = false;

    // Track currently running chromosome load task so we can preempt on chromosome switch
    private ThreadRunner.RunnerTask activeChromosomeLoadTask;

    // Increments on each chromosome load request; stale workers/results are discarded
    private final AtomicLong chromosomeLoadGeneration = new AtomicLong(0);

    // Last requested chromosome; used to retry immediately after stale/cancelled work exits
    private volatile String pendingChromosome;

    // Whether the current chromosome's variants have been annotated
    private boolean currentAnnotated = false;

    // Current active filter (pass-all by default)
    private VariantFilter currentFilter = new VariantFilter();

    // Stable key for filter used to build currentVariants
    private String currentLoadedFilterKey;

    // Snapshot of filter used to materialize currentVariants
    private VariantFilter currentLoadedFilter;

    // Variant manager dialog reference to avoid opening it twice
    private boolean variantManagerOpen = false;

    // One-shot callback fired on the FX thread after chromosome variants are cached
    private Runnable onChromosomeVariantsReady;

    // Callback fired when new VCF is added (for dialog refresh)
    private Runnable onVcfAdded;

    // Track whether we've set up the update listener
    private boolean updateListenerInitialized = false;

    // Suppresses auto variant loading during bulk sample registration
    private boolean suppressVariantLoading = false;

    // Incremented on every filter change; stale threads discard results when generation has advanced
    private final AtomicLong filterGeneration = new AtomicLong(0);
    
    private VcfManager() {
        // Singleton
    }
    
    public static VcfManager getInstance() {
        return INSTANCE;
    }

    private static String normalizeVcfPath(File file) {
        if (file == null) return "";
        return file.toPath().toAbsolutePath().normalize().toString();
    }

    private boolean isVcfPathLoadedLocked(String normalizedPath) {
        for (VcfData vcfData : loadedVcfs) {
            if (normalizeVcfPath(vcfData.file).equals(normalizedPath)) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean isVcfFileLoaded(File file) {
        String normalizedPath = normalizeVcfPath(file);
        return !normalizedPath.isEmpty() && isVcfPathLoadedLocked(normalizedPath);
    }
    
    /**
     * Register a loaded VCF (used by batch loading to add VcfData to the registry).
     * The reader should already have been opened and parsed.
     * Returns the VcfData so the caller can manage reader lifecycle (close + null).
     */
    public synchronized VcfData registerLoadedVcf(VcfReader reader, VariantLoader loader, File file) {
        String normalizedPath = normalizeVcfPath(file);
        if (!normalizedPath.isEmpty() && isVcfPathLoadedLocked(normalizedPath)) {
            return null;
        }
        VcfData vcfData = new VcfData(reader, loader, file);
        loadedVcfs.add(vcfData);
        initializeUpdateListener();
        return vcfData;
    }
    
    /**
     * Initialize the update listener to automatically reload variants on navigation.
     * Called once during application startup or first VCF load.
     */
    private void initializeUpdateListener() {
        if (updateListenerInitialized) return;
        updateListenerInitialized = true;

        ViewportState viewport = ServiceRegistry.getInstance().getViewportState();
        viewport.currentChromosomeProperty().addListener((obs, oldChrom, newChrom) -> {
            if (!loadedVcfs.isEmpty() && !suppressVariantLoading) {
                loadChromosomeVariants(newChrom);
            }
        });
    }
    
    public void setSuppressVariantLoading(boolean suppress) {
        this.suppressVariantLoading = suppress;
    }
    
    /**
     * Load a VCF file and initialize variant loading.
     * Multiple VCF files can be loaded concurrently; variants are merged.
     * 
     * @param file VCF file (.vcf.gz with index)
     */
    public void loadVcfFile(File file) {
        loadVcfFileWithCallback(file, null);
    }
    
    /**
     * Load a VCF file with a completion callback.
     * Phase 1: Opens VCF and creates sample tracks (fast)
     * Phase 2: Loads variants for current chromosome (slow, separate thread)
     * 
     * @param file VCF file (.vcf.gz with index)
     * @param onComplete Callback invoked when sample tracks are created (before variants load)
     */
    public void loadVcfFileWithCallback(File file, Runnable onComplete) {
        loadVcfFileWithCallback(file, onComplete, false);
    }

    /**
     * @param suppressUiUpdates when true, skips canvas update and variant manager open;
     *                          caller is responsible for triggering those once all files are loaded.
     */
    public void loadVcfFileWithCallback(File file, Runnable onComplete, boolean suppressUiUpdates) {
        if (file == null || !file.exists()) {
            System.err.println("VCF file not found: " + file);
            if (onComplete != null) onComplete.run();
            return;
        }

        if (isVcfFileLoaded(file)) {
            if (onComplete != null) {
                Platform.runLater(onComplete);
            }
            return;
        }
        
        ThreadRunner.get().submit("Opening VCF: " + file.getName() + "\u2026",
            () -> {
                try {
                    Path vcfPath = file.toPath();
                    VcfReader reader = new VcfReader(vcfPath);
                    VariantLoader loader = new VariantLoader(reader);
                    return new VcfData(reader, loader, file);
                } catch (IOException e) {
                    System.err.println("Failed to open VCF: " + file + " - " + e.getMessage());
                    e.printStackTrace();
                    return null;
                }
            },
            vcfData -> {
                if (vcfData == null) {
                    if (onComplete != null) onComplete.run();
                    return;
                }

                String normalizedPath = normalizeVcfPath(vcfData.file);
                synchronized (this) {
                    if (!normalizedPath.isEmpty() && isVcfPathLoadedLocked(normalizedPath)) {
                        try { vcfData.reader.close(); } catch (IOException ignored) {}
                        vcfData.reader = null;
                        vcfData.loader.setVcfReader(null);
                        if (onComplete != null) {
                            Platform.runLater(onComplete);
                        }
                        return;
                    }
                    loadedVcfs.add(vcfData);
                    initializeUpdateListener();
                }
                
                List<String> unmappedSamples = vcfData.loader.getUnmappedSamples();
                if (!unmappedSamples.isEmpty()) {
                    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
                    for (String sampleName : unmappedSamples) {
                        SampleTrack track = new SampleTrack(sampleName);
                        registry.getSampleTracks().add(track);
                        registry.getSampleList().add(sampleName);
                    }
                    if (registry.getFirstVisibleSample() < 0) {
                        registry.setFirstVisibleSample(0);
                    }
                    registry.setLastVisibleSample(registry.getSampleList().size() - 1);
                    vcfData.loader.updateMapping();
                    if (!suppressUiUpdates) {
                        GenomicCanvas.update.set(!GenomicCanvas.update.get());
                    }
                }
                
                if (vcfData.loader.getMappedSampleCount() == 0) {
                    System.err.println("Warning: Could not create or map any VCF samples.");
                }

                // Header fully parsed; close reader and release from loader to free VCFHeader and tabix index
                try { vcfData.reader.close(); } catch (IOException ignored) {}
                vcfData.reader = null;
                vcfData.loader.setVcfReader(null);
                
                UserPreferences.addRecentFile("VCF", file);
                
                if (onComplete != null) {
                    Platform.runLater(onComplete);
                }
            });
    }

    /** Trigger incremental variant load for the current view chromosome when a new VCF is added. */
    void loadVariantsForCurrentView() {
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        if (stackManager.isEmpty()) return;
        DrawStack firstStack = stackManager.getFirst();
        if (firstStack.alignmentCanvas != null) {
            currentAnnotated = false;
            loadChromosomeVariants(firstStack.chromosome);
        }
    }

    private synchronized void loadChromosomeVariants(String chromosome) {
        loadChromosomeVariants(chromosome, false);
    }

    private synchronized void loadChromosomeVariants(String chromosome, boolean forceReload) {
        if (chromosome == null || chromosome.isBlank()) {
            return;
        }
        if (loadedVcfs.isEmpty()) {
            return;
        }

        pendingChromosome = chromosome;

        // If a load is already running and target chromosome changed (or caller forces reload), preempt stale work.
        if (loading && (!chromosome.equals(lastLoadedChromosome) || forceReload)) {
            if (activeChromosomeLoadTask != null && !activeChromosomeLoadTask.isCompleted()) {
                activeChromosomeLoadTask.cancel();
            }
            loading = false;
            activeChromosomeLoadTask = null;
        }

        if (forceReload || !chromosome.equals(lastLoadedChromosome)) {
            // Release old variant list from canvases immediately so it can be GC'd before the new one loads
            DrawStackManager sm = ServiceRegistry.getInstance().getDrawStackManager();
            for (DrawStack stack : sm.getStacks()) {
                if (stack.alignmentCanvas != null) stack.alignmentCanvas.clearVariantList();
            }
            currentVariants = new VariantList(chromosome);
            loadedVcfCountForCurrentChromosome = 0;
            currentAnnotated = false;
            currentLoadedFilterKey = null;
            currentLoadedFilter = null;
            lastLoadedChromosome = chromosome;
        }

        // If another load for the same chromosome is already running, keep it.
        if (loading) {
            return;
        }

        if (loadedVcfCountForCurrentChromosome >= loadedVcfs.size()) {
            updateCanvasesWithVariants(currentVariants);
            return;
        }

        final int fromIndex = loadedVcfCountForCurrentChromosome;
        final List<VcfData> vcfsToLoad = List.copyOf(loadedVcfs.subList(fromIndex, loadedVcfs.size()));
        final List<Integer> mappedSamplesPerVcf = vcfsToLoad.stream()
            .map(vcfData -> Math.max(1, vcfData.loader.getMappedSampleCount()))
            .toList();
        final int totalMappedSamples = Math.max(1,
            mappedSamplesPerVcf.stream().mapToInt(Integer::intValue).sum());
        final VariantList mergedList = currentVariants;
        final VariantFilter loadFilter = currentFilter.copy();
        final String loadFilterKey = loadFilter.toStableKey();
        final long loadGeneration = chromosomeLoadGeneration.incrementAndGet();
        final String targetChromosome = chromosome;
        loading = true;
        final int vcfCountBefore = loadedVcfs.size();

        activeChromosomeLoadTask = ThreadRunner.get().submit("Loading variants…",
            () -> {
                try {
                    int[] completedSamples = {0};
                    org.baseplayer.variant.VariantNode cursor = null;
                    org.baseplayer.services.LoadingManager.get().setProgress(0, totalMappedSamples);
                    for (int i = 0; i < vcfsToLoad.size(); i++) {
                        if (loadGeneration != chromosomeLoadGeneration.get()) {
                            throw new InterruptedException("Stale chromosome load discarded");
                        }
                        VcfData vcfData = vcfsToLoad.get(i);
                        int vcfSampleTotal = mappedSamplesPerVcf.get(i);
                        if (Thread.currentThread().isInterrupted())
                            throw new InterruptedException("Variant loading cancelled");
                        try (VcfReader reader = new VcfReader(vcfData.file.toPath())) {
                            vcfData.loader.setVcfReader(reader);
                            // No progress callback - update only after each VCF completes
                            cursor = vcfData.loader.streamChromosomeVariantsToList(
                                targetChromosome, mergedList, cursor, null, loadFilter);
                        } catch (IOException e) {
                            //System.err.println("Skipping variants for " + vcfData.file.getName() + ": " + e.getMessage());
                        } finally {
                            vcfData.loader.setVcfReader(null);
                        }

                        completedSamples[0] = Math.min(totalMappedSamples,
                            completedSamples[0] + vcfSampleTotal);
                        org.baseplayer.services.LoadingManager.get()
                            .setProgress(completedSamples[0], totalMappedSamples);

                        if (hasVisibleSamples(vcfData) && loadGeneration == chromosomeLoadGeneration.get()) {
                            final VariantList snapshot = mergedList;
                            Platform.runLater(() -> {
                                if (loadGeneration != chromosomeLoadGeneration.get()) {
                                    return;
                                }
                                updateCanvasesWithVariants(snapshot);
                                if (onVcfAdded != null) onVcfAdded.run();
                            });
                        }
                    }
                    VariantList result = mergedList;
                    if (loadFilter.requiresPostAnnotationFiltering()) {
                        VariantAnnotator annotator = new VariantAnnotator(
                            ServiceRegistry.getInstance().getReferenceGenomeService());
                        annotator.annotate(result, targetChromosome);
                        result.retainSamples((node, call) -> loadFilter.passes(node, call.trackIndex));
                    }

                    return result;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            },
            result -> {
                if (loadGeneration != chromosomeLoadGeneration.get()) {
                    return;
                }

                loading = false;
                activeChromosomeLoadTask = null;
                if (result == null) {
                    if (pendingChromosome != null && !pendingChromosome.equals(targetChromosome)) {
                        Platform.runLater(() -> loadChromosomeVariants(pendingChromosome));
                    }
                    return;
                }

                loadedVcfCountForCurrentChromosome = loadedVcfs.size();
                currentVariants = result;
                currentLoadedFilterKey = loadFilterKey;
                currentLoadedFilter = loadFilter.copy();
                currentAnnotated = loadFilter.requiresPostAnnotationFiltering();

                // If new VCFs were added during loading, schedule a reload
                if (loadedVcfs.size() > vcfCountBefore) {
                    Platform.runLater(() -> loadChromosomeVariants(targetChromosome));
                    return;
                }

                updateCanvasesWithVariants(result);
                // Calculate density immediately after variants are loaded
                calculateDensityOnAllCanvases();
                // Keep annotation state when post-annotation filtering already annotated this list.
                // Otherwise leave false so UI can annotate lazily as before.
                if (!loadFilter.requiresPostAnnotationFiltering()) {
                    currentAnnotated = false;
                }
                GenomicCanvas.update.set(!GenomicCanvas.update.get());

                Runnable cb = onChromosomeVariantsReady;
                if (cb != null) {
                    onChromosomeVariantsReady = null;
                    Platform.runLater(cb);
                }
            });
    }
    
    /**
     * Update all alignment canvases with the given variant list.
     */
    private void updateCanvasesWithVariants(VariantList variantList) {
        if (variantList == null) return;
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        for (DrawStack stack : stackManager.getStacks()) {
            if (stack.alignmentCanvas != null) {
                stack.alignmentCanvas.setVariantList(variantList);
                stack.alignmentCanvas.draw();
            }
        }
    }

    /**
     * Force immediate density calculation on all canvases.
     * Called after variants are fully loaded for a chromosome.
     */
    private void calculateDensityOnAllCanvases() {
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        for (DrawStack stack : stackManager.getStacks()) {
            if (stack.alignmentCanvas != null) {
                stack.alignmentCanvas.forceCalculateDensity();
            }
        }
    }

    /** Returns true if any of this VCF's samples are currently in the visible track range. */
    private boolean hasVisibleSamples(VcfData vcfData) {
        SampleRegistry reg = ServiceRegistry.getInstance().getSampleRegistry();
        int first = reg.getFirstVisibleSample();
        int last  = reg.getLastVisibleSample();
        if (first < 0 || last < 0) return false;
        List<Integer> displayed = reg.getDisplayedTrackIndices();
        Collection<Integer> trackIndices = vcfData.loader.getTrackIndices();
        for (int slot = Math.max(0, first); slot <= last && slot < displayed.size(); slot++) {
            if (trackIndices.contains(displayed.get(slot))) return true;
        }
        return false;
    }
    
    /**
     * @deprecated Use loadChromosomeVariants() instead. 
     * Kept for backward compatibility but should not be called directly.
     */
    @Deprecated
    public void updateVariants(String chromosome, long start, long end) {
        // Redirect to chromosome-level loading
        loadChromosomeVariants(chromosome);
    }
    
    /**
     * Close all currently loaded VCF files.
     */
    public void closeCurrentVcf() {
        if (activeChromosomeLoadTask != null && !activeChromosomeLoadTask.isCompleted()) {
            activeChromosomeLoadTask.cancel();
        }
        for (VcfData vcfData : loadedVcfs) {
            try {
                if (vcfData.reader != null) vcfData.reader.close();
            } catch (IOException e) {
                System.err.println("Error closing VCF: " + e.getMessage());
            }
        }
        loadedVcfs.clear();
        lastLoadedChromosome = null;
        currentVariants = null;
        loadedVcfCountForCurrentChromosome = 0;
        loading = false;
        activeChromosomeLoadTask = null;
        pendingChromosome = null;
        suppressVariantLoading = false;
        currentAnnotated = false;
        currentFilter = new VariantFilter();
        currentLoadedFilterKey = null;
        currentLoadedFilter = null;
        variantManagerOpen = false;
        onChromosomeVariantsReady = null;
        TranscriptCdsCache.getInstance().clearMemory();
        
        // Clear variants from all canvases
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        for (DrawStack stack : stackManager.getStacks()) {
            if (stack.alignmentCanvas != null) {
                stack.alignmentCanvas.clearVariantList();
            }
        }
    }
    
    /**
     * Check if any VCF files are currently loaded.
     */
    public boolean hasVcfLoaded() {
        return !loadedVcfs.isEmpty();
    }
    
    /**
     * Get the number of VCF files currently loaded.
     */
    public int getLoadedVcfCount() {
        return loadedVcfs.size();
    }
    
    /**
     * Get the list of currently loaded VCF files.
     */
    public List<File> getLoadedVcfFiles() {
        List<File> files = new ArrayList<>();
        for (VcfData vcfData : loadedVcfs) {
            files.add(vcfData.file);
        }
        return files;
    }
    
    // ── Annotation and filtering API ─────────────────────────────────────────

    /** Annotate all variants for the chromosome if not already done; call from a background thread. */
    public void ensureAnnotated(String chromosome) {
        if (currentAnnotated) return;
        if (currentVariants == null || !chromosome.equals(lastLoadedChromosome)) return;

        long startTime = System.currentTimeMillis();
        VariantAnnotator annotator = new VariantAnnotator(
            ServiceRegistry.getInstance().getReferenceGenomeService());
        annotator.annotate(currentVariants, chromosome);
        currentAnnotated = true;
        
        long endTime = System.currentTimeMillis();
        TranscriptCdsCache cache = TranscriptCdsCache.getInstance();
        long hits = cache.getHitCount();
        long misses = cache.getMissCount();
        long builds = cache.getBuildCount();
        System.out.println("[VCF Annotation] " + chromosome + ": " + (endTime - startTime) + "ms");
        System.out.println("  CDS Cache: " + hits + " hits, " + misses + " misses, " + builds + " builds");
    }

    /** Returns true if variants for this chromosome have already been annotated. */
    public boolean isAnnotated(String chromosome) {
        return currentAnnotated && chromosome.equals(lastLoadedChromosome);
    }

    /**
     * Apply a filter, update canvases, and cache the filter for future chromosome loads.
     * If chromosome is provided, filter variants for that chromosome specifically.
     * Otherwise, filters the last loaded chromosome.
     * Safe to call from any thread.
     */
    public void applyFilter(VariantFilter filter, String chromosome) {
        this.currentFilter = filter;
        filterGeneration.incrementAndGet();
        // The filter is applied at draw-time in VariantDrawer; just trigger a redraw.
        Platform.runLater(() -> {
            DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
            for (DrawStack stack : stackManager.getStacks()) {
                if (stack.alignmentCanvas != null) stack.alignmentCanvas.draw();
            }
        });
    }

    /**
     * Apply a filter to the last loaded chromosome variants.
     * Safe to call from any thread.
     */
    public void applyFilter(VariantFilter filter) {
        applyFilter(filter, lastLoadedChromosome);
    }

    /** Reset to no filter and restore the full variant list on canvases. */
    public void clearFilter() {
        applyFilter(new VariantFilter());
    }

    public VariantFilter getCurrentFilter() {
        return currentFilter;
    }

    /** Set filter state for future loads only; does not redraw current data. */
    public synchronized void setCurrentFilterForNextLoad(VariantFilter filter) {
        if (filter != null) {
            this.currentFilter = filter;
            filterGeneration.incrementAndGet();
        }
    }

    /** True when current chromosome data was materialized with this exact filter. */
    public synchronized boolean isCurrentChromosomeLoadedForFilter(VariantFilter filter, String chromosome) {
        if (filter == null || chromosome == null || chromosome.isBlank()) return false;
        if (currentVariants == null) return false;
        if (!chromosome.equals(lastLoadedChromosome)) return false;
        String key = filter.toStableKey();
        return key.equals(currentLoadedFilterKey);
    }

    /**
     * Returns true if applying {@code filter} can be done in-memory without reloading.
     * This is true when requested filter is equal or stricter than the filter used at load-time.
     */
    public synchronized boolean canApplyFilterWithoutReload(VariantFilter filter, String chromosome) {
        if (filter == null) return true;
        if (chromosome == null || chromosome.isBlank()) return true;
        if (currentVariants == null) return true;
        if (!chromosome.equals(lastLoadedChromosome)) return true;
        if (currentLoadedFilter == null) return false;
        return filter.isAtLeastAsStrictAs(currentLoadedFilter);
    }

    /** Get a copy of the filter used to load current chromosome variants. */
    public synchronized VariantFilter getCurrentLoadedFilter() {
        return currentLoadedFilter == null ? null : currentLoadedFilter.copy();
    }

    /** Update filter and reload chromosome variants from VCF to materialize with new filter settings. */
    public synchronized void reloadChromosomeForFilter(String chromosome, VariantFilter filter) {
        if (filter == null) return;
        this.currentFilter = filter;
        filterGeneration.incrementAndGet();
        loadChromosomeVariants(chromosome, true);
    }

    /**
     * Reload currently active chromosome variants using the same chromosome-load path
     * used by navigation-based chromosome changes.
     */
    public synchronized void reloadCurrentChromosome() {
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        if (stackManager.isEmpty()) return;
        DrawStack firstStack = stackManager.getFirst();
        if (firstStack == null || firstStack.chromosome == null || firstStack.chromosome.isBlank()) return;
        loadChromosomeVariants(firstStack.chromosome, true);
    }

    /**
     * Clear currently cached chromosome variants from memory and canvases.
     * Intended for explicit reload flows that must start from an empty state.
     */
    public synchronized void clearCurrentChromosomeVariants() {
        if (activeChromosomeLoadTask != null && !activeChromosomeLoadTask.isCompleted()) {
            activeChromosomeLoadTask.cancel();
        }
        chromosomeLoadGeneration.incrementAndGet();
        loading = false;
        activeChromosomeLoadTask = null;
        pendingChromosome = null;

        if (currentVariants != null) {
            currentVariants.clear();
        }
        currentVariants = null;
        loadedVcfCountForCurrentChromosome = 0;
        currentAnnotated = false;
        currentLoadedFilterKey = null;
        currentLoadedFilter = null;

        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        for (DrawStack stack : stackManager.getStacks()) {
            if (stack.alignmentCanvas != null) {
                stack.alignmentCanvas.clearVariantList();
            }
        }
    }

    public int getFilterGeneration() {
        return (int) filterGeneration.get();
    }

    public String getLastLoadedChromosome() {
        return lastLoadedChromosome;
    }

    public VariantList getCachedVariants(String chromosome) {
        return chromosome.equals(lastLoadedChromosome) ? currentVariants : null;
    }

    public boolean hasLoadedVcf() {
        return !loadedVcfs.isEmpty();
    }

    /** True if a chromosome load task is currently running for this chromosome. */
    public synchronized boolean isLoadingChromosome(String chromosome) {
        if (chromosome == null || chromosome.isBlank()) return false;
        return loading && chromosome.equals(lastLoadedChromosome);
    }

    /** Register a one-shot callback invoked on the FX thread once chromosome variants are cached. */
    public void setOnChromosomeVariantsReady(Runnable callback) {
        this.onChromosomeVariantsReady = callback;
    }

    /** Register a callback invoked when a new VCF is added while dialog is already open. */
    public void setOnVcfAdded(Runnable callback) {
        this.onVcfAdded = callback;
    }

    /** Open the Variant Manager dialog; no-op if already open or no VCF loaded. */
    public void openVariantManager(Window owner) {
        if (loadedVcfs.isEmpty() || variantManagerOpen) return;
        variantManagerOpen = true;
        org.baseplayer.variant.ui.VariantManagerWindow.show(owner, this,
            () -> variantManagerOpen = false);
    }

    /** Auto-open the Variant Manager using the window from the active DrawStack. */
    public void autoOpenVariantManager() {
        if (variantManagerOpen) return;
        DrawStackManager sm = ServiceRegistry.getInstance().getDrawStackManager();
        if (sm.isEmpty()) return;
        DrawStack stack = sm.getFirst();
        if (stack.alignmentCanvas == null) return;
        javafx.scene.Scene scene = stack.alignmentCanvas.getScene();
        if (scene == null) return;
        Window window = scene.getWindow();
        if (window == null) return;
        openVariantManager(window);
    }

    /**
     * Get the first loaded VCF file, or null if none loaded.
     * Kept for backward compatibility.
     */
    public File getCurrentFile() {
        return loadedVcfs.isEmpty() ? null : loadedVcfs.get(0).file;
    }
    
    /**
     * Get the first loaded VCF reader (for advanced usage).
     * Kept for backward compatibility.
     */
    public VcfReader getCurrentReader() {
        return loadedVcfs.isEmpty() ? null : loadedVcfs.get(0).reader;
    }
    
    /**
     * Helper class to bundle VCF data.
     */
    public static class VcfData {
        public VcfReader reader; // public: closed after header parse, null thereafter
        final VariantLoader loader;
        final File file;
        
        public VcfData(VcfReader reader, VariantLoader loader, File file) {
            this.reader = reader;
            this.loader = loader;
            this.file = file;
        }
    }
}

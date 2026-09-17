package org.baseplayer.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.RegionFetchCache;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantLoader;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.annotation.VariantAnnotator;
import org.baseplayer.variant.annotation.TranscriptCdsCache;
import org.baseplayer.variant.ui.VariantManagerController;
import org.baseplayer.variant.ui.VariantManagerWindow;

import javafx.application.Platform;

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
    
    private final Map<String, VariantList> variantCache = new HashMap<>();
    
    // Last loaded chromosome (used to optimize quick access to "current" chromosome)
    private String lastLoadedChromosome;
    
    // Current active filter (pass-all by default)
    private VariantFilter currentFilter = new VariantFilter();

    // Whether a background load is in progress
    private boolean loading = false;

    // Track currently running chromosome load task so we can preempt on chromosome switch
    private ThreadRunner.RunnerTask activeChromosomeLoadTask;

    // Increments on each chromosome load request; stale workers/results are discarded
    private final AtomicLong chromosomeLoadGeneration = new AtomicLong(0);

    // Incremented on every filter change; stale threads discard results when generation has advanced
    private final AtomicLong filterGeneration = new AtomicLong(0);

    // Monotonic revision for visible variant data. Controllers use this to detect
    // in-place list growth during progressive loading.
    private final AtomicLong variantsRevision = new AtomicLong(0);

    // One-shot callback fired on the FX thread after chromosome variants are cached
    private Runnable onChromosomeVariantsReady;

    // Callback fired when new VCF is added (for dialog refresh)
    private Runnable onVcfAdded;

    // One-shot marker set by manual chromosome dropdown navigation.
    private String pendingManualChromosomeScrollTarget;

    // Tracks the source of the latest loadRegionVariants request.
    private boolean lastLoadManualChromosomeSelection;

    // Track whether we've set up the update listener
    private boolean updateListenerInitialized = false;
    
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
                    // Fire callback for dialogs listening to new VCF additions
                    if (onVcfAdded != null) {
                        Runnable cb = onVcfAdded;
                        onVcfAdded = null;  // one-shot callback
                        Platform.runLater(cb);
                    }
                }
                
                List<String> unmappedSamples = vcfData.loader.getUnmappedSamples();
                if (!unmappedSamples.isEmpty()) {
                    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
                    for (String sampleName : unmappedSamples) {
                        SampleTrack track = new SampleTrack(sampleName);
                        registry.getSampleTracks().add(track);
                        registry.getSampleList().add(sampleName);
                    }
                    registry.includeNewTracksAtEndResetHeight();
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
                ProjectSessionState.get().markDirty();
                
                if (onComplete != null) {
                    Platform.runLater(onComplete);
                }
            });
    }

    /** Trigger variant load for current chromosome when a new VCF is added. */
    public void loadVariantsForCurrentView() {
        syncCurrentFilterFromOpenVariantManager();
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        if (stackManager.isEmpty()) return;
        DrawStack firstStack = stackManager.getFirst();
        if (firstStack.sampleTrackCanvas != null) {
            loadRegionVariants(firstStack.getChromosome(), 1, (long)(firstStack.chromSize + 1));
        }
    }

    
    /**
     * Load variants for a specific genomic region using VCF index for efficient seeking.
     * Useful for gene searches or narrowly-focused region navigation.
     * Uses the same filtering and caching as chromosome loads but only queries the specified region.
     * 
     * @param chromosome chromosome name
     * @param start region start (1-based inclusive)
     * @param end region end (1-based inclusive)
     */
    public synchronized void loadRegionVariants(String chromosome, long start, long end) {
        loadRegionVariants(chromosome, start, end, false);
    }

    public synchronized void loadRegionVariants(
        String chromosome,
        long start,
        long end,
        boolean manualChromosomeSelection) {
        syncCurrentFilterFromOpenVariantManager();
        if (!manualChromosomeSelection) {
            pendingManualChromosomeScrollTarget = null;
        }
        lastLoadManualChromosomeSelection = false;

        if (chromosome == null || chromosome.isBlank() || loadedVcfs.isEmpty()) {
            return;
        }

        if (manualChromosomeSelection) {
            pendingManualChromosomeScrollTarget = chromosome;
            lastLoadManualChromosomeSelection = true;
        }

        VariantFilter requestFilterSnapshot = currentFilter.copy();
        VariantList cachedVariants = findCachedVariantList(chromosome);
        
        if (shouldReuseCachedVariants(chromosome, start, end, cachedVariants, requestFilterSnapshot)) {
            putVariantList(chromosome, cachedVariants);
            displayCachedVariants(chromosome, cachedVariants);
            return;
        }

        // No valid cache - proceed to load variants from VCF files
        RegionFetchCache cache = ServiceRegistry.getInstance().getRegionFetchCache();

        // Cancel any ongoing chromosome or region load by bumping the generation
        if (activeChromosomeLoadTask != null && !activeChromosomeLoadTask.isCompleted()) {
            activeChromosomeLoadTask.cancel();
            activeChromosomeLoadTask = null;
        }
        loading = false;

        // Prefer the existing aliased list; never create a parallel empty list under chr/non-chr.
        VariantList variantList = cachedVariants;
        if (variantList == null) {
            variantList = new VariantList(chromosome);
        }
        putVariantList(chromosome, variantList);

        boolean fullChromCached = isFullChromosomeCached(variantList);
        boolean partialRequest = !isFullChromosomeRequest(chromosome, start, end);
        boolean vcfCountMatches = variantList.getVcfCountWhenLoaded() == loadedVcfs.size();

        if (fullChromCached
            && partialRequest
            && variantList.isAnnotated()
            && vcfCountMatches
            && !variantList.isEmpty()) {
            displayCachedVariants(chromosome, variantList);
            return;
        }

        if (shouldResetVariantListBeforeLoad(variantList, requestFilterSnapshot)) {
            // If we must reload, expand partial requests to full chromosome so we keep a reusable cache.
            if (partialRequest) {
                long fullEnd = resolveChromosomeLoadEnd(chromosome);
                start = 1;
                end = fullEnd;
            }
            variantList.clear();
        }
        
        // Clear canvases and prepare for new load
        DrawStackManager sm = ServiceRegistry.getInstance().getDrawStackManager();
        for (DrawStack stack : sm.getStacks()) {
            if (stack.sampleTrackCanvas != null) stack.sampleTrackCanvas.clearVariantList();
            if (stack.sampleAggregateCanvas != null) stack.sampleAggregateCanvas.clearVariantList();
        }
        lastLoadedChromosome = chromosome;
        variantsRevision.incrementAndGet();
        Platform.runLater(() -> GenomicCanvas.update.set(!GenomicCanvas.update.get()));

        final long regionLoadGeneration = chromosomeLoadGeneration.incrementAndGet();
        final String targetChromosome = chromosome;
        final long regionStart = start;
        final long regionEnd = end;
        final RegionFetchCache finalCache = cache;
        final VariantFilter loadFilterSnapshot = currentFilter.copy();
        final String loadFilterKeySnapshot = loadFilterSnapshot.toStableKey();
        loading = true;
        final int vcfCountBefore = loadedVcfs.size();
        final VariantList mergedList = variantList;
        mergedList.setAnnotated(false);

        activeChromosomeLoadTask = ThreadRunner.get().submit("Loading variants for " + chromosome + ":" + start + "-" + end + "…",
            () -> {
                try {
                    final int totalMappedSamples = Math.max(1,
                        loadedVcfs.stream().mapToInt(vcf -> Math.max(1, vcf.loader.getMappedSampleCount())).sum());

                    org.baseplayer.variant.VariantNode cursor = null;
                    org.baseplayer.services.LoadingManager.get().setProgress(0, totalMappedSamples);

                    int completedSamples = 0;
                    for (VcfData vcfData : loadedVcfs) {
                        if (regionLoadGeneration != chromosomeLoadGeneration.get()) {
                            throw new InterruptedException("Stale region load discarded");
                        }
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException("Region load cancelled");
                        }

                        try (VcfReader reader = new VcfReader(vcfData.file.toPath())) {
                            vcfData.loader.setVcfReader(reader);
                            cursor = vcfData.loader.streamRegionVariantsToList(
                                targetChromosome, regionStart, regionEnd, mergedList, cursor, null, loadFilterSnapshot);
                        } catch (IOException e) {
                        } finally {
                            vcfData.loader.setVcfReader(null);
                        }

                        int vcfSampleCount = Math.max(1, vcfData.loader.getMappedSampleCount());
                        completedSamples = Math.min(totalMappedSamples, completedSamples + vcfSampleCount);
                        org.baseplayer.services.LoadingManager.get().setProgress(completedSamples, totalMappedSamples);

                    }

                    VariantList result = mergedList;
                    
                    // ALWAYS annotate before filtering, so we can apply effect-based filters
                    VariantAnnotator annotator = new VariantAnnotator(
                        ServiceRegistry.getInstance().getReferenceGenomeService());
                    annotator.annotate(result, targetChromosome);
                    
                    // Filter variants: keep only those that pass the complete filter (samples + effects)
                    // retainVariants removes variants that don't match the predicate
                    result.retainVariants(node -> {
                        // Keep a variant if it has at least one sample passing the filter
                        for (VariantNode.SampleCall call : node.getSamples()) {
                            if (loadFilterSnapshot.passes(node, call)) {
                                return true;
                            }
                        }
                        return false;
                    });
                    result.rebuildVisibleChain(loadFilterSnapshot);

                    return result;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            },
            result -> {
                if (regionLoadGeneration != chromosomeLoadGeneration.get()) {
                    return;
                }

                loading = false;
                activeChromosomeLoadTask = null;
                if (result == null) {
                    return;
                }

                if (loadedVcfs.size() > vcfCountBefore) {
                    Platform.runLater(() -> loadRegionVariants(targetChromosome, regionStart, regionEnd));
                    return;
                }

                finalCache.markFetched("VCF", targetChromosome, regionStart, regionEnd);
                long fullLoadEnd = resolveChromosomeLoadEnd(targetChromosome);
                if (regionStart <= 1 && regionEnd >= (fullLoadEnd - 1)) {
                    // Mark as full-chromosome coverage to maximize future cache reuse checks.
                    result.addLoadedRegion(1, Long.MAX_VALUE);
                } else {
                    result.addLoadedRegion(regionStart, regionEnd);
                }
                result.setLoadedFilterKey(loadFilterKeySnapshot);
                result.setLoadedFilter(loadFilterSnapshot.copy());
                result.setAnnotated(true);
                result.setVcfCountWhenLoaded(loadedVcfs.size());

                updateCanvasesWithVariants(result);
                calculateDensityOnAllCanvases();
                variantsRevision.incrementAndGet();
                GenomicCanvas.update.set(!GenomicCanvas.update.get());

                fireAndClearChromosomeReadyCallback();
            });
    }

    private void syncCurrentFilterFromOpenVariantManager() {
        VariantManagerController controller = VariantManagerWindow.getCurrentController();
        if (controller == null) {
            return;
        }
        VariantFilter fromUi = controller.snapshotUiFilter();
        if (fromUi != null) {
            this.currentFilter = fromUi;
        }
    }

    private void fireAndClearChromosomeReadyCallback() {
        Runnable cb = onChromosomeVariantsReady;
        if (cb != null) {
            onChromosomeVariantsReady = null;
            Platform.runLater(cb);
        }
    }

    private boolean shouldResetVariantListBeforeLoad(VariantList cachedVariants, VariantFilter requestedFilter) {
        if (cachedVariants == null || cachedVariants.isEmpty()) {
            return false;
        }

        if (cachedVariants.getVcfCountWhenLoaded() != loadedVcfs.size()) {
            return true;
        }

        String requestedKey = requestedFilter != null ? requestedFilter.toStableKey() : null;
        return !java.util.Objects.equals(cachedVariants.getLoadedFilterKey(), requestedKey);
    }

    private boolean shouldReuseCachedVariants(String chromosome, long start, long end,
                                              VariantList cachedVariants, VariantFilter requestedFilter) {
        if (cachedVariants == null) {
            return false;  // No variants cached for this chromosome
        }

        if (!cachedVariants.isRegionLoaded(start, end)) {
            return false;  // Requested region not yet loaded
        }

        if (cachedVariants.getVcfCountWhenLoaded() != loadedVcfs.size()) {
            return false;  // VCF count changed - must reload to merge with new VCFs
        }

        String requestedKey = requestedFilter != null ? requestedFilter.toStableKey() : null;
        String loadedKey = cachedVariants.getLoadedFilterKey();
        if (!java.util.Objects.equals(requestedKey, loadedKey)) {
            return false;
        }

        return true;
    }

    private static boolean isFullChromosomeCached(VariantList list) {
        if (list == null) {
            return false;
        }
        // Session install and full-chrom loads mark coverage with end = Long.MAX_VALUE.
        return list.isRegionLoaded(1, Long.MAX_VALUE / 4);
    }

    private boolean isFullChromosomeRequest(String chromosome, long start, long end) {
        if (start > 1) {
            return false;
        }
        long fullEnd = resolveChromosomeLoadEnd(chromosome);
        return end >= (fullEnd - 1);
    }

    /**
     * Store {@code list} under a single canonical chromosome key.
     * Alias resolution belongs in {@link #findCachedVariantList} — never dual-index
     * the same instance (that duplicates rows in Variant Manager).
     */
    private void putVariantList(String requestedChromosome, VariantList list) {
        if (list == null) {
            return;
        }
        String canonical = canonicalChromosomeKey(requestedChromosome, list);
        // Drop any stale alias keys that still point at this instance.
        variantCache.entrySet().removeIf(entry -> entry.getValue() == list);
        variantCache.put(canonical, list);
    }

    private static String canonicalChromosomeKey(String requestedChromosome, VariantList list) {
        if (list != null) {
            String fromList = list.getChromosome();
            if (fromList != null && !fromList.isBlank()) {
                return fromList;
            }
        }
        return requestedChromosome;
    }

    private void displayCachedVariants(String chromosome, VariantList cachedVariants) {
        lastLoadedChromosome = chromosome;
        updateCanvasesWithVariants(cachedVariants);
        calculateDensityOnAllCanvases();
        variantsRevision.incrementAndGet();
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
        fireAndClearChromosomeReadyCallback();
    }

		private void updateCanvasesWithVariants(VariantList variantList) {
        if (variantList == null) return;
        variantList.ensureVisibleChain(currentFilter);
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        for (DrawStack stack : stackManager.getStacks()) {
            if (stack.sampleTrackCanvas != null) {
                stack.sampleTrackCanvas.setVariantList(variantList);
                stack.sampleTrackCanvas.draw();
            }
            if (stack.sampleAggregateCanvas != null) {
                stack.sampleAggregateCanvas.setVariantList(variantList);
                stack.sampleAggregateCanvas.draw();
            }
        }
    }

    private void calculateDensityOnAllCanvases() {
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        for (DrawStack stack : stackManager.getStacks()) {
            if (stack.sampleAggregateCanvas != null) {
                stack.sampleAggregateCanvas.forceCalculateDensity();
            }
        }
    }

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
        variantCache.clear();
        lastLoadedChromosome = null;
        loading = false;
        activeChromosomeLoadTask = null;
        // New Project / Clear All: return to pass-all so the next VCF is not hidden by
        // the previous project's type/effect/comparison thresholds.
        currentFilter = new VariantFilter();
        filterGeneration.incrementAndGet();
        onChromosomeVariantsReady = null;
        TranscriptCdsCache.getInstance().clearMemory();
        ServiceRegistry.getInstance().getRegionFetchCache().clear("VCF");
        
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        for (DrawStack stack : stackManager.getStacks()) {
            if (stack.sampleTrackCanvas != null) {
                stack.sampleTrackCanvas.clearVariantList();
            }
            if (stack.sampleAggregateCanvas != null) {
                stack.sampleAggregateCanvas.clearVariantList();
            }
        }
    }
    
    /**
     * Check if any VCF files are currently loaded.
     */
    public boolean hasVcfLoaded() {
        return !loadedVcfs.isEmpty();
    }
    
    public int getLoadedVcfCount() {
        return loadedVcfs.size();
    }
    
    public List<File> getLoadedVcfFiles() {
        List<File> files = new ArrayList<>();
        for (VcfData vcfData : loadedVcfs) {
            files.add(vcfData.file);
        }
        return files;
    }

    /**
     * VCF files whose sample columns map to the given sample-track index.
     */
    public synchronized List<File> getVcfFilesForTrackIndex(int trackIndex) {
        List<File> files = new ArrayList<>();
        if (trackIndex < 0) return files;
        for (VcfData vcfData : loadedVcfs) {
            if (vcfData == null || vcfData.loader == null || vcfData.file == null) continue;
            if (vcfData.loader.getTrackIndices().contains(trackIndex)) {
                files.add(vcfData.file);
            }
        }
        return files;
    }

    public ThreadRunner.RunnerTask annotateAllReferenceChromosomes(
        VariantFilter filter,
				List<String> chromosomes,
        Consumer<AllChromosomeProgress> onProgress,
        Consumer<AllChromosomeAnnotationResult> onComplete) {

        final VariantFilter filterSnapshot = filter == null ? new VariantFilter() : filter.copy();
        final List<File> filesSnapshot = getLoadedVcfFiles();
        final int totalChromosomes = chromosomes.size();
        final int samplesPerChromosome = Math.max(1,
            loadedVcfs.stream().mapToInt(vcf -> Math.max(1, vcf.loader.getMappedSampleCount())).sum());
        final int globalSampleTotal = Math.max(1, totalChromosomes * samplesPerChromosome);

        if (filesSnapshot.isEmpty() || chromosomes.isEmpty()) {
            if (onComplete != null) {
                Platform.runLater(() -> onComplete.accept(
                    new AllChromosomeAnnotationResult(false, 0, 0, 0, List.of())));
            }
            return null;
        }

        final ThreadRunner.RunnerTask[] taskRef = new ThreadRunner.RunnerTask[1];
        taskRef[0] = ThreadRunner.get().submit("Annotating all chromosomes…",
            () -> {
                List<String> warnings = new ArrayList<>();

                VariantAnnotator annotator = new VariantAnnotator(
                    ServiceRegistry.getInstance().getReferenceGenomeService());

                int completedChromosomes = 0;
                int totalRows = 0;
                final int[] progressState = { 0, 0 }; // [completedChroms, totalRows]

                org.baseplayer.services.LoadingManager.get().setProgress(0, globalSampleTotal);
                updateAnnotateAllTaskSuffix(taskRef[0],
                    "0/" + totalChromosomes + " chromosomes, rows: 0");

                for (String chromosome : chromosomes) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }

                    final int chromBase = completedChromosomes * samplesPerChromosome;
                    final String chromLabel = chromosome;
                    final int[] lastNotifiedSample = { -1 };
                    int chromosomeRows = buildChromosomeCacheForAnnotation(
                        chromosome,
                        filesSnapshot,
                        filterSnapshot,
                        annotator,
                        warnings,
                        (samplesDoneInChrom, samplesTotalInChrom) -> {
                            int clamped = Math.min(samplesPerChromosome,
                                Math.max(0, samplesDoneInChrom));
                            org.baseplayer.services.LoadingManager.get().setProgress(
                                chromBase + clamped, globalSampleTotal);
                            // Throttle message updates so FX isn't flooded.
                            if (clamped != lastNotifiedSample[0]
                                && (clamped == 0
                                    || clamped >= samplesPerChromosome
                                    || clamped - lastNotifiedSample[0] >= 1)) {
                                lastNotifiedSample[0] = clamped;
                                updateAnnotateAllTaskSuffix(taskRef[0],
                                    chromLabel
                                        + " (" + progressState[0] + "/" + totalChromosomes + ")"
                                        + ", rows: " + progressState[1]);
                            }
                        });

                    completedChromosomes++;
                    totalRows += chromosomeRows;
                    progressState[0] = completedChromosomes;
                    progressState[1] = totalRows;
                    org.baseplayer.services.LoadingManager.get().setProgress(
                        completedChromosomes * samplesPerChromosome, globalSampleTotal);
                    updateAnnotateAllTaskSuffix(taskRef[0],
                        chromosome
                            + " (" + completedChromosomes + "/" + totalChromosomes + ")"
                            + ", rows: " + totalRows);

                    if (onProgress != null) {
                        int completedSnapshot = completedChromosomes;
                        int totalRowsSnapshot = totalRows;
                        String chromosomeSnapshot = chromosome;
                        Platform.runLater(() -> onProgress.accept(new AllChromosomeProgress(
                            chromosomeSnapshot,
                            completedSnapshot,
                            totalChromosomes,
                            totalRowsSnapshot,
                            samplesPerChromosome,
                            globalSampleTotal)));
                    }
                }

                boolean cancelled = Thread.currentThread().isInterrupted();
                if (!cancelled) {
                    // Ensure the chromosome currently on screen matches the annotate-all filter.
                    // Soft filter changes leave loadedFilterKey stale; skip-compatible chroms are fine,
                    // but the active view must never keep pre-filter rows after annotate-all.
                    String active = lastLoadedChromosome;
                    if (active != null && !active.isBlank()) {
                        VariantList activeList = findCachedVariantList(active);
                        String requestedKey = filterSnapshot.toStableKey();
                        boolean needsRefresh = activeList == null
                            || !java.util.Objects.equals(requestedKey, activeList.getLoadedFilterKey())
                            || !activeList.isAnnotated();
                        if (needsRefresh) {
                            buildChromosomeCacheForAnnotation(
                                active,
                                filesSnapshot,
                                filterSnapshot,
                                annotator,
                                warnings,
                                null);
                        } else {
                            variantsRevision.incrementAndGet();
                        }
                    } else {
                        variantsRevision.incrementAndGet();
                    }
                }
                return new AllChromosomeAnnotationResult(
                    cancelled,
                    completedChromosomes,
                    totalChromosomes,
                    totalRows,
                    List.copyOf(warnings));
            },
            result -> {
                if (onComplete != null) {
                    AllChromosomeAnnotationResult safeResult = result;
                    if (safeResult == null) {
                        safeResult = new AllChromosomeAnnotationResult(
                            true,
                            0,
                            totalChromosomes,
                            0,
                            List.of("Annotation failed before completion"));
                    }
                    onComplete.accept(safeResult);
                }
            });
        return taskRef[0];
    }

    private static void updateAnnotateAllTaskSuffix(ThreadRunner.RunnerTask task, String suffix) {
        if (task == null) return;
        task.setProgressSuffix(suffix);
        ThreadRunner.get().notifyDescriptionChanged();
    }

    private int buildChromosomeCacheForAnnotation(
        String chromosome,
        List<File> files,
        VariantFilter filter,
        VariantAnnotator annotator,
        List<String> warnings,
        java.util.function.BiConsumer<Integer, Integer> onSampleProgress) {

        long loadEnd = resolveChromosomeLoadEnd(chromosome);
        VariantList cachedVariants = findCachedVariantList(chromosome);
        boolean hasCache = cachedVariants != null;
        boolean annotated = hasCache && cachedVariants.isAnnotated();
        boolean nonEmpty = hasCache && !cachedVariants.isEmpty();
        boolean vcfCountMatches = hasCache && cachedVariants.getVcfCountWhenLoaded() == files.size();
        String requestedKey = filter != null ? filter.toStableKey() : null;
        boolean filterCompatible = hasCache
            && java.util.Objects.equals(requestedKey, cachedVariants.getLoadedFilterKey());
        boolean fullRegionLoaded = hasCache && isChromosomeFullyLoadedForCache(cachedVariants, loadEnd);

        if (hasCache && annotated && nonEmpty && vcfCountMatches && filterCompatible && fullRegionLoaded) {
            if (onSampleProgress != null) {
                int samples = Math.max(1,
                    loadedVcfs.stream().mapToInt(vcf -> Math.max(1, vcf.loader.getMappedSampleCount())).sum());
                onSampleProgress.accept(samples, samples);
            }
            return cachedVariants.size();
        }

        VariantList variants = cachedVariants != null ? cachedVariants : new VariantList(chromosome);
        variants.clear();
        putVariantList(chromosome, variants);
        VariantNode cursor = null;

        int samplesDoneBeforeFile = 0;
        int samplesTotalInChrom = Math.max(1,
            loadedVcfs.stream().mapToInt(vcf -> Math.max(1, vcf.loader.getMappedSampleCount())).sum());

        for (File file : files) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }

            VcfData matched = null;
            synchronized (this) {
                for (VcfData vcfData : loadedVcfs) {
                    if (vcfData.file != null && vcfData.file.equals(file)) {
                        matched = vcfData;
                        break;
                    }
                }
            }

            int mappedForFile = 1;
            try (VcfReader reader = new VcfReader(file.toPath())) {
                VariantLoader loader;
                if (matched != null) {
                    matched.loader.setVcfReader(reader);
                    loader = matched.loader;
                } else {
                    loader = new VariantLoader(reader);
                }
                mappedForFile = Math.max(1, loader.getMappedSampleCount());
                final int fileBase = samplesDoneBeforeFile;
                cursor = loader.streamRegionVariantsToList(
                    chromosome, 1, loadEnd, variants, cursor,
                    onSampleProgress == null ? null : (cur, tot) -> {
                        onSampleProgress.accept(
                            Math.min(samplesTotalInChrom, fileBase + Math.max(0, cur)),
                            samplesTotalInChrom);
                    },
                    filter);
            } catch (IOException e) {
                warnings.add("Load failed for " + file.getName() + " (" + chromosome + "): " + e.getMessage());
            } finally {
                if (matched != null) {
                    matched.loader.setVcfReader(null);
                }
            }
            samplesDoneBeforeFile = Math.min(samplesTotalInChrom, samplesDoneBeforeFile + mappedForFile);
            if (onSampleProgress != null) {
                onSampleProgress.accept(samplesDoneBeforeFile, samplesTotalInChrom);
            }
        }

        if (Thread.currentThread().isInterrupted() || variants.isEmpty()) {
            return 0;
        }

        try {
            annotator.annotate(variants, chromosome);
        } catch (Throwable t) {
            warnings.add("Annotation failed for " + chromosome + ": " + t.getMessage());
            return 0;
        }

        variants.retainVariants(node -> {
            // Keep a variant if it has at least one sample passing the filter
            for (VariantNode.SampleCall call : node.getSamples()) {
                if (filter.passes(node, call)) {
                    return true;
                }
            }
            return false;
        });
        variants.rebuildVisibleChain(filter);

        // Mark the variants as annotated and store the filter info
        String filterKey = filter.toStableKey();
        variants.setLoadedFilterKey(filterKey);
        variants.setLoadedFilter(filter.copy());
        variants.setAnnotated(true);
        variants.addLoadedRegion(1, loadEnd);
        variants.setVcfCountWhenLoaded(files.size());
        variantsRevision.incrementAndGet();

        if (onSampleProgress != null) {
            onSampleProgress.accept(samplesTotalInChrom, samplesTotalInChrom);
        }
        return variants.size();
    }

    private boolean isChromosomeFullyLoadedForCache(VariantList variants, long loadEnd) {
        if (variants == null) {
            return false;
        }
        if (variants.isRegionLoaded(1, loadEnd)) {
            return true;
        }
        // Be tolerant of inclusive/exclusive boundary differences at chromosome end.
        return loadEnd > 1 && variants.isRegionLoaded(1, loadEnd - 1);
    }

    private long resolveChromosomeLoadEnd(String chromosome) {
        ReferenceGenomeService refService = ServiceRegistry.getInstance().getReferenceGenomeService();
        long chromLength = refService.hasGenome()
            ? refService.getCurrentGenome().getChromosomeLength(chromosome)
            : 1_000_000_000L;
        return chromLength + 1;
    }

    public static record AllChromosomeProgress(
        String chromosome,
        int completedChromosomes,
        int totalChromosomes,
        int totalRows,
        int samplesPerChromosome,
        int globalSampleTotal) {
    }

    public static record AllChromosomeAnnotationResult(
        boolean cancelled,
        int completedChromosomes,
        int totalChromosomes,
        int totalRows,
        List<String> warnings) {
    }
    
    // ── Annotation and filtering API ─────────────────────────────────────────

    /** Annotate all variants for the chromosome if not already done; call from a background thread. */
    public synchronized void ensureAnnotated(String chromosome) {
        if (chromosome == null || chromosome.isBlank()) return;

        VariantList variants = variantCache.get(chromosome);
        if (variants == null || variants.isEmpty()) {
            return;
        }
        if (variants.isAnnotated()) {
            return;
        }

        VariantAnnotator annotator = new VariantAnnotator(
            ServiceRegistry.getInstance().getReferenceGenomeService());
        annotator.annotate(variants, chromosome);
        variants.setAnnotated(true);
    }

    /** Returns true if variants for this chromosome have already been annotated. */
    public boolean isAnnotated(String chromosome) {
        if (chromosome == null || chromosome.isBlank()) return false;
        VariantList variants = findCachedVariantList(chromosome);
        return variants != null && variants.isAnnotated();
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
        // Rebuild visible skip chains for cached lists, then redraw.
        Platform.runLater(() -> {
            rebuildVisibleChainsForCache(filter);
            DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
            for (DrawStack stack : stackManager.getStacks()) {
                if (stack.sampleTrackCanvas != null) stack.sampleTrackCanvas.draw();
            }
        });
    }

    /** Rebuild drawable skip chains on all cached chromosome lists for {@code filter}. */
    private void rebuildVisibleChainsForCache(VariantFilter filter) {
        java.util.IdentityHashMap<VariantList, Boolean> seen = new java.util.IdentityHashMap<>();
        for (VariantList list : variantCache.values()) {
            if (list == null || list.isEmpty() || seen.put(list, Boolean.TRUE) != null) {
                continue;
            }
            list.rebuildVisibleChain(filter);
        }
    }

    /**
     * Apply a filter to the last loaded chromosome variants.
     * Safe to call from any thread.
     */
    public void applyFilter(VariantFilter filter) {
        applyFilter(filter, lastLoadedChromosome);
    }

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

    /** Get a copy of the filter used to load current chromosome variants. */
    public synchronized VariantFilter getCurrentLoadedFilter() {
        if (lastLoadedChromosome == null) return null;
        VariantList variants = variantCache.get(lastLoadedChromosome);
        if (variants == null) return null;
        VariantFilter filter = variants.getLoadedFilter();
        return filter == null ? null : filter.copy();
    }

    /** Update filter and reload chromosome variants from VCF to materialize with new filter settings. */
    public synchronized void reloadChromosomeForFilter(String chromosome, VariantFilter filter) {
        if (filter == null) return;
        this.currentFilter = filter;
        filterGeneration.incrementAndGet();
        DrawStackManager sm = ServiceRegistry.getInstance().getDrawStackManager();
        DrawStack stack = sm.getStacks().stream()
            .filter(s -> s.getChromosome().equals(chromosome))
            .findFirst().orElse(null);
        if (stack != null) {
            loadRegionVariants(chromosome, 1, (long)(stack.chromSize + 1));
        }
    }

    public synchronized void reloadCurrentChromosome() {
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        if (stackManager.isEmpty()) return;
        DrawStack firstStack = stackManager.getFirst();
        if (firstStack == null || firstStack.getChromosome() == null || firstStack.getChromosome().isBlank()) return;
        
        // Check if the current viewport is viewing the full chromosome or a specific region
        // If it's a specific region (like from gene search), don't override it with full chromosome load
        var currentRegion = firstStack.getRegion();
        if (currentRegion != null) {
            long regionStart = currentRegion.start();
            long regionEnd = currentRegion.end();
            
            // If current region is NOT the full chromosome, don't reload
            // (it's likely a focused region like a gene that shouldn't be overwritten)
            boolean isFullChromosomeView = (regionStart <= 1 && regionEnd >= (long)firstStack.chromSize);
            if (!isFullChromosomeView) {
                // Current region is a specific focused view (e.g., gene) - don't override it
                return;
            }
        }
        
        // Only reload if viewing full chromosome
        loadRegionVariants(firstStack.getChromosome(), 1, (long)(firstStack.chromSize + 1));
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

        if (lastLoadedChromosome != null) {
            variantCache.remove(lastLoadedChromosome);
        }
        lastLoadedChromosome = null;

        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        for (DrawStack stack : stackManager.getStacks()) {
            if (stack.sampleTrackCanvas != null) {
                stack.sampleTrackCanvas.clearVariantList();
            }
            if (stack.sampleAggregateCanvas != null) {
                stack.sampleAggregateCanvas.clearVariantList();
            }
        }
    }

    public int getFilterGeneration() {
        return (int) filterGeneration.get();
    }

    public long getVariantsRevision() {
        return variantsRevision.get();
    }

    public String getLastLoadedChromosome() {
        return lastLoadedChromosome;
    }

    public VariantList getCachedVariants(String chromosome) {
        if (chromosome == null || chromosome.isBlank()) return null;
        return findCachedVariantList(chromosome);
    }

    /** Snapshot of chromosome → variant list for session cache write (one entry per list). */
    public synchronized Map<String, VariantList> snapshotVariantCache() {
        Map<String, VariantList> snapshot = new HashMap<>();
        java.util.IdentityHashMap<VariantList, Boolean> seen = new java.util.IdentityHashMap<>();
        for (Map.Entry<String, VariantList> entry : variantCache.entrySet()) {
            VariantList list = entry.getValue();
            if (list == null || seen.put(list, Boolean.TRUE) != null) {
                continue;
            }
            String key = canonicalChromosomeKey(entry.getKey(), list);
            snapshot.put(key, list);
        }
        return snapshot;
    }

    /**
     * Install pre-built chromosome lists from session cache and display the
     * list matching the current view (if any).
     *
     * <p>Each installed list is marked fully reusable so chromosome switches
     * hit {@link #variantCache} instead of re-streaming VCFs.
     *
     * @return true if at least one chromosome was installed
     */
    public boolean installCachedVariantLists(Map<String, VariantList> lists) {
        if (lists == null || lists.isEmpty()) return false;

        VariantFilter filterSnapshot = currentFilter.copy();
        String filterKey = filterSnapshot.toStableKey();
        int vcfCount = loadedVcfs.size();

        synchronized (this) {
            variantCache.clear();
            for (Map.Entry<String, VariantList> entry : lists.entrySet()) {
                VariantList list = entry.getValue();
                if (list == null) continue;
                prepareSessionCachedListForReuse(list, filterSnapshot, filterKey, vcfCount);
                putVariantList(entry.getKey(), list);
            }
        }

        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        String preferredChrom = null;
        if (!stackManager.isEmpty()) {
            preferredChrom = stackManager.getFirst().getChromosome();
        }

        VariantList toDisplay = preferredChrom != null ? findCachedVariantList(preferredChrom) : null;

        // Only skip VCF streaming when the active chromosome is in the cache.
        if (toDisplay == null) {
            return false;
        }

        String chrom = preferredChrom != null ? preferredChrom : toDisplay.getChromosome();
        lastLoadedChromosome = chrom;
        toDisplay.ensureVisibleChain(currentFilter);
        displayCachedVariants(chrom, toDisplay);
        return true;
    }

    /**
     * Mark a session-restored list so {@link #shouldReuseCachedVariants} accepts it
     * for full-chromosome requests under the filter that was used when the cache was written.
     * Do not overwrite that identity with the project UI filter — content must match the key.
     */
    private static void prepareSessionCachedListForReuse(
        VariantList list,
        VariantFilter filter,
        String filterKey,
        int vcfCount) {
        if (list == null) return;
        // Session caches are full annotated chromosome snapshots.
        list.addLoadedRegion(1, Long.MAX_VALUE);
        list.setAnnotated(true);
        list.setVcfCountWhenLoaded(vcfCount);
        // Keep the sidecar's baked key when present — it describes how the list was pruned.
        String bakedKey = list.getLoadedFilterKey();
        if (bakedKey == null || bakedKey.isBlank()) {
            if (filterKey != null && !filterKey.isBlank()) {
                list.setLoadedFilterKey(filterKey);
            }
        }
        if (list.getLoadedFilter() == null && filter != null) {
            list.setLoadedFilter(filter.copy());
        }
    }

    /** Lookup cached list allowing {@code chr} / non-{@code chr} aliases. */
    private VariantList findCachedVariantList(String chromosome) {
        if (chromosome == null || chromosome.isBlank()) {
            return null;
        }
        VariantList direct = variantCache.get(chromosome);
        if (direct != null) {
            return direct;
        }
        if (chromosome.regionMatches(true, 0, "chr", 0, 3) && chromosome.length() > 3) {
            return variantCache.get(chromosome.substring(3));
        }
        return variantCache.get("chr" + chromosome);
    }

    /**
     * Return cached chromosome names in a deterministic order.
     * Preferred order is taken from the provided chromosome list (typically UI dropdown order),
     * then any extra cached chromosomes are appended in lexicographic order.
     * Each {@link VariantList} instance appears at most once (alias-safe).
     */
    public synchronized List<String> getCachedChromosomesInOrder(List<String> preferredOrder) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        java.util.IdentityHashMap<VariantList, Boolean> seenLists = new java.util.IdentityHashMap<>();

        if (preferredOrder != null) {
            for (String chromosome : preferredOrder) {
                if (chromosome == null || chromosome.isBlank()) {
                    continue;
                }
                VariantList variants = findCachedVariantList(chromosome);
                if (variants != null && !variants.isEmpty() && seenLists.put(variants, Boolean.TRUE) == null) {
                    ordered.add(chromosome);
                }
            }
        }

        List<String> remaining = new ArrayList<>();
        for (Map.Entry<String, VariantList> entry : variantCache.entrySet()) {
            String chromosome = entry.getKey();
            VariantList variants = entry.getValue();
            if (chromosome == null || chromosome.isBlank() || variants == null || variants.isEmpty()) {
                continue;
            }
            if (seenLists.put(variants, Boolean.TRUE) != null) {
                continue;
            }
            remaining.add(chromosome);
        }
        remaining.sort(Comparator.naturalOrder());
        ordered.addAll(remaining);

        return new ArrayList<>(ordered);
    }

    /**
     * Return non-empty cached variant lists paired with chromosome in deterministic order.
     * Guarantees one entry per list instance.
     */
    public synchronized List<CachedChromosomeVariants> getCachedVariantListsInOrder(List<String> preferredOrder) {
        List<String> chromosomes = getCachedChromosomesInOrder(preferredOrder);
        if (chromosomes.isEmpty()) {
            return Collections.emptyList();
        }

        List<CachedChromosomeVariants> result = new ArrayList<>(chromosomes.size());
        java.util.IdentityHashMap<VariantList, Boolean> seenLists = new java.util.IdentityHashMap<>();
        for (String chromosome : chromosomes) {
            VariantList variants = findCachedVariantList(chromosome);
            if (variants != null && !variants.isEmpty() && seenLists.put(variants, Boolean.TRUE) == null) {
                result.add(new CachedChromosomeVariants(chromosome, variants));
            }
        }
        return result;
    }

    /** Returns the currently loaded variants for the lastLoadedChromosome. */
    public VariantList getCachedVariants() {
        if (lastLoadedChromosome == null) return null;
        return findCachedVariantList(lastLoadedChromosome);
    }

    public boolean hasLoadedVcf() {
        return !loadedVcfs.isEmpty();
    }

    public static record CachedChromosomeVariants(String chromosome, VariantList variants) {
    }

    /**
     * Consume manual chromosome scroll request if it matches the expected chromosome.
     */
    public synchronized boolean consumeManualChromosomeScrollRequest(String expectedChromosome) {
        if (expectedChromosome == null || expectedChromosome.isBlank()) {
            return false;
        }
        if (!java.util.Objects.equals(pendingManualChromosomeScrollTarget, expectedChromosome)) {
            return false;
        }
        pendingManualChromosomeScrollTarget = null;
        return true;
    }

    public synchronized boolean wasLastLoadManualChromosomeSelection() {
        return lastLoadManualChromosomeSelection;
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

    /**
     * Get the first loaded VCF file, or null if none loaded.
     * Kept for backward compatibility.
     */
    public File getCurrentFile() {
        return loadedVcfs.isEmpty() ? null : loadedVcfs.get(0).file;
    }
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

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

    // Whether the current chromosome's variants have been annotated
    private boolean currentAnnotated = false;

    // Current active filter (pass-all by default)
    private VariantFilter currentFilter = new VariantFilter();

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
    
    /**
     * Register a loaded VCF (used by batch loading to add VcfData to the registry).
     * The reader should already have been opened and parsed.
     * Returns the VcfData so the caller can manage reader lifecycle (close + null).
     */
    public VcfData registerLoadedVcf(VcfReader reader, VariantLoader loader, File file) {
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
    
    /**
     * Called after all VCF samples are registered; loads variants for the current chromosome.
     */
    private void checkAndUpdateVariants() {
        if (suppressVariantLoading) return;
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        if (stackManager.isEmpty()) return;
        DrawStack firstStack = stackManager.getFirst();
        if (firstStack.alignmentCanvas != null) {
            loadChromosomeVariants(firstStack.chromosome);
        }
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
                
                loadedVcfs.add(vcfData);
                initializeUpdateListener();
                
                List<String> unmappedSamples = vcfData.loader.getUnmappedSamples();
                if (!unmappedSamples.isEmpty()) {
                    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
                    for (String sampleName : unmappedSamples) {
                        SampleTrack track = new SampleTrack(sampleName);
                        registry.getSampleTracks().add(track);
                        registry.getSampleList().add(sampleName);
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

    private void loadChromosomeVariants(String chromosome) {
        if (loadedVcfs.isEmpty()) return;
        if (loading) return;

        if (!chromosome.equals(lastLoadedChromosome)) {
            // Release old variant list from canvases immediately so it can be GC'd before the new one loads
            DrawStackManager sm = ServiceRegistry.getInstance().getDrawStackManager();
            for (DrawStack stack : sm.getStacks()) {
                if (stack.alignmentCanvas != null) stack.alignmentCanvas.clearVariantList();
            }
            currentVariants = new VariantList(chromosome);
            loadedVcfCountForCurrentChromosome = 0;
            currentAnnotated = false;
            lastLoadedChromosome = chromosome;
        }

        if (loadedVcfCountForCurrentChromosome >= loadedVcfs.size()) {
            updateCanvasesWithVariants(currentVariants);
            return;
        }

        final int fromIndex = loadedVcfCountForCurrentChromosome;
        final List<VcfData> vcfsToLoad = List.copyOf(loadedVcfs.subList(fromIndex, loadedVcfs.size()));
        final VariantList mergedList = currentVariants;
        loading = true;
        final int vcfCountBefore = loadedVcfs.size();

        String taskDesc = vcfsToLoad.size() == 1
            ? "Loading variants for " + chromosome
            : "Loading variants for " + chromosome + " from " + vcfsToLoad.size() + " files";

        ThreadRunner.get().submit(taskDesc,
            () -> {
                try {
                    for (VcfData vcfData : vcfsToLoad) {
                        if (Thread.currentThread().isInterrupted())
                            throw new InterruptedException("Variant loading cancelled");
                        try (VcfReader reader = new VcfReader(vcfData.file.toPath())) {
                            vcfData.loader.setVcfReader(reader);
                            vcfData.loader.streamChromosomeVariantsToList(chromosome, mergedList, null);
                        } catch (IOException e) {
                            System.err.println("Skipping variants for " + vcfData.file.getName() + ": " + e.getMessage());
                        } finally {
                            vcfData.loader.setVcfReader(null);
                        }
                        if (hasVisibleSamples(vcfData)) {
                            final VariantList snapshot = mergedList;
                            Platform.runLater(() -> {
                                updateCanvasesWithVariants(snapshot);
                                if (onVcfAdded != null) onVcfAdded.run();
                            });
                        }
                    }
                    return mergedList;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            },
            result -> {
                loading = false;
                if (result == null) return;

                loadedVcfCountForCurrentChromosome = loadedVcfs.size();

                if (loadedVcfs.size() > vcfCountBefore) {
                    loadChromosomeVariants(chromosome);
                    return;
                }

                updateCanvasesWithVariants(result);
                // Reset so the dialog re-annotates the full dataset (partial annotation may have run earlier)
                currentAnnotated = false;
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

    /** Returns true if any of this VCF's samples are currently in the visible track range. */
    private boolean hasVisibleSamples(VcfData vcfData) {
        SampleRegistry reg = ServiceRegistry.getInstance().getSampleRegistry();
        int first = reg.getFirstVisibleSample();
        int last  = reg.getLastVisibleSample();
        List<Integer> displayed = reg.getDisplayedTrackIndices();
        Collection<Integer> trackIndices = vcfData.loader.getTrackIndices();
        for (int slot = first; slot <= last && slot < displayed.size(); slot++) {
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
        currentAnnotated = false;
        currentFilter = new VariantFilter();
        variantManagerOpen = false;
        onChromosomeVariantsReady = null;
        
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

        VariantAnnotator annotator = new VariantAnnotator(
            ServiceRegistry.getInstance().getReferenceGenomeService());
        annotator.annotate(currentVariants, chromosome);
        currentAnnotated = true;
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

    public String getLastLoadedChromosome() {
        return lastLoadedChromosome;
    }

    public VariantList getCachedVariants(String chromosome) {
        return chromosome.equals(lastLoadedChromosome) ? currentVariants : null;
    }

    public boolean hasLoadedVcf() {
        return !loadedVcfs.isEmpty();
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

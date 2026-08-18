package org.baseplayer.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantLoader;
import org.baseplayer.variant.VariantNode;

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
    
    // Last loaded chromosome (to avoid redundant loads)
    private String lastLoadedChromosome;
    
    // Cache of chromosome-level merged variant lists
    private final Map<String, VariantList> chromosomeVariantCache = new HashMap<>();
    
    // Track chromosomes currently being loaded to prevent concurrent loads
    private final Set<String> chromosomesLoading = new HashSet<>();
    
    // Track whether we've set up the update listener
    private boolean updateListenerInitialized = false;
    
    private VcfManager() {
        // Singleton
    }
    
    public static VcfManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Initialize the update listener to automatically reload variants on navigation.
     * Called once during application startup or first VCF load.
     */
    private void initializeUpdateListener() {
        if (updateListenerInitialized) return;
        updateListenerInitialized = true;
        
        // Listen for GenomicCanvas updates and check if we need to reload variants
        GenomicCanvas.update.addListener((obs, oldVal, newVal) -> {
            if (!loadedVcfs.isEmpty()) {
                // Defer check slightly to let navigation settle
                Platform.runLater(this::checkAndUpdateVariants);
            }
        });
    }
    
    /**
     * Check if the chromosome has changed and load variants if needed.
     * Called automatically on GenomicCanvas updates.
     */
    private void checkAndUpdateVariants() {
        DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
        if (stackManager.isEmpty()) return;
        
        DrawStack firstStack = stackManager.getFirst();
        if (firstStack.alignmentCanvas == null) return;
        
        String chromosome = firstStack.chromosome;
        
        // Only load if we've switched to a different chromosome
        if (!chromosome.equals(lastLoadedChromosome)) {
            loadChromosomeVariants(chromosome);
        }
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
     * This version allows sequential loading of multiple VCF files by chaining callbacks.
     * Multiple VCF files can be loaded concurrently; variants are merged.
     * 
     * @param file VCF file (.vcf.gz with index)
     * @param onComplete Callback invoked when the VCF file is fully loaded and ready
     */
    public void loadVcfFileWithCallback(File file, Runnable onComplete) {
        if (file == null || !file.exists()) {
            System.err.println("VCF file not found: " + file);
            if (onComplete != null) onComplete.run();
            return;
        }
        
        ThreadRunner.get().submit("Loading VCF: " + file.getName() + "\u2026",
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
                
                // Add this VCF to the list (don't replace, append for multi-VCF support)
                loadedVcfs.add(vcfData);
                
                // Initialize update listener on first load
                initializeUpdateListener();
                
                // Create sample tracks for unmapped VCF samples
                List<String> unmappedSamples = vcfData.loader.getUnmappedSamples();
                if (!unmappedSamples.isEmpty()) {
                    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
                    
                    for (String sampleName : unmappedSamples) {
                        SampleTrack track = new SampleTrack(sampleName);
                        registry.getSampleTracks().add(track);
                        registry.getSampleList().add(sampleName);
                    }
                    
                    // Update visible range to include new samples
                    registry.setLastVisibleSample(registry.getSampleList().size() - 1);
                    
                    // Update the loader's mapping with the newly created tracks
                    vcfData.loader.updateMapping();
                    
                    // Trigger UI update to show new tracks
                    GenomicCanvas.update.set(!GenomicCanvas.update.get());
                }
                
                if (vcfData.loader.getMappedSampleCount() == 0) {
                    System.err.println("Warning: Could not create or map any VCF samples.");
                }
                
                UserPreferences.addRecentFile("VCF", file);
                
                // Reload cached variants to include the newly added VCF's data
                if (lastLoadedChromosome != null) {
                    chromosomeVariantCache.remove(lastLoadedChromosome);
                }
                
                // Load variants for current chromosome to include all VCFs
                DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
                if (!stackManager.isEmpty()) {
                    DrawStack firstStack = stackManager.getFirst();
                    if (firstStack.alignmentCanvas != null) {
                        loadChromosomeVariants(firstStack.chromosome);
                    }
                }
                
                // Invoke completion callback
                if (onComplete != null) {
                    Platform.runLater(onComplete);
                }
            });
    }
    
    /**
     * Load all variants for an entire chromosome from all loaded VCFs.
     * Results are cached to avoid reloading the same chromosome.
     * Variants from multiple VCFs are merged into a single VariantList.
     * 
     * @param chromosome Chromosome name
     */
    private void loadChromosomeVariants(String chromosome) {
        if (loadedVcfs.isEmpty()) {
            return; // No VCFs loaded
        }
        
        // Check if already loading this chromosome
        if (chromosomesLoading.contains(chromosome)) {
            return;
        }
        
        // Check cache first
        if (chromosomeVariantCache.containsKey(chromosome)) {
            VariantList cachedList = chromosomeVariantCache.get(chromosome);
            updateCanvasesWithVariants(cachedList);
            lastLoadedChromosome = chromosome;
            return;
        }
        
        // Mark as loading
        chromosomesLoading.add(chromosome);
        
        String taskDescription = loadedVcfs.size() == 1 
            ? "Loading variants for " + chromosome 
            : "Loading variants for " + chromosome + " from " + loadedVcfs.size() + " files";
        
        ThreadRunner.get().submit(taskDescription,
            () -> {
                try {
                    // Merge variants from all loaded VCFs with incremental updates
                    return mergeVariantsFromAllVcfsWithProgress(chromosome);
                } catch (IOException e) {
                    System.err.println("Failed to load variants for chromosome " + chromosome + ": " + e.getMessage());
                    e.printStackTrace();
                    return null;
                }
            },
            mergedVariantList -> {
                // Always remove from loading set when done (success or failure)
                chromosomesLoading.remove(chromosome);
                
                if (mergedVariantList == null) return;
                
                // Cache the merged results
                chromosomeVariantCache.put(chromosome, mergedVariantList);
                lastLoadedChromosome = chromosome;
                
                GenomicCanvas.update.set(!GenomicCanvas.update.get());
            });
    }
    
    /**
     * Merge variants from all loaded VCF files for a specific chromosome,
     * with incremental canvas updates to show progress.
     */
    private VariantList mergeVariantsFromAllVcfsWithProgress(String chromosome) throws IOException {
        VariantList mergedList = new VariantList(chromosome);
        
        for (VcfData vcfData : loadedVcfs) {
            // Load variants from this VCF for this chromosome
            VariantList vcfVariants = vcfData.loader.loadChromosomeVariants(chromosome);
            
            if (vcfVariants == null || vcfVariants.isEmpty()) {
                continue;
            }
            
            
            // Merge each variant from this VCF into the merged list
            VariantNode node = vcfVariants.getFirst();
            while (node != null) {
                BitSet sampleIndices = node.getSamplePresence();
                for (int i = sampleIndices.nextSetBit(0); i >= 0; i = sampleIndices.nextSetBit(i + 1)) {
                    VariantNode.GenotypeInfo genoInfo = node.getGenotype(i);
                    mergedList.addVariant(node.position, node.ref, node.alt, 
                                        node.type, i, genoInfo);
                }
                node = node.next;
            }
            
            final VariantList partialList = mergedList;
            javafx.application.Platform.runLater(() -> {
                updateCanvasesWithVariants(partialList);
            });
        }
        
        return mergedList;
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
                // Trigger canvas redraw to display variants
                Platform.runLater(() -> stack.alignmentCanvas.draw());
            }
        }
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
                vcfData.reader.close();
            } catch (IOException e) {
                System.err.println("Error closing VCF: " + e.getMessage());
            }
        }
        loadedVcfs.clear();
        lastLoadedChromosome = null;
        chromosomeVariantCache.clear();
        chromosomesLoading.clear();
        
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
    private static class VcfData {
        final VcfReader reader;
        final VariantLoader loader;
        final File file;
        
        VcfData(VcfReader reader, VariantLoader loader, File file) {
            this.reader = reader;
            this.loader = loader;
            this.file = file;
        }
    }
}

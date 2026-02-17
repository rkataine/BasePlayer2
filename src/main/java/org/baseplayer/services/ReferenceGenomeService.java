package org.baseplayer.services;

import org.baseplayer.io.ReferenceGenome;

/**
 * Manages reference genome access.
 * Replaces SharedModel.referenceGenome static field.
 * 
 * This service is the single source of truth for the currently loaded
 * reference genome and provides convenient access methods.
 */
public class ReferenceGenomeService {
    
    private ReferenceGenome currentGenome;
    
    public ReferenceGenomeService() {
        // Initially no genome loaded
        this.currentGenome = null;
    }
    
    /**
     * Get the currently loaded reference genome.
     * 
     * @return the current genome, or null if none is loaded
     */
    public ReferenceGenome getCurrentGenome() {
        return currentGenome;
    }
    
    /**
     * Set the current reference genome.
     * 
     * @param genome the reference genome to use (can be null to unload)
     */
    public void setCurrentGenome(ReferenceGenome genome) {
        this.currentGenome = genome;
    }
    
    /**
     * Check if a reference genome is currently loaded.
     */
    public boolean hasGenome() {
        return currentGenome != null;
    }
    
    /**
     * Get sequence from the reference genome.
     * 
     * @param chrom chromosome name
     * @param start start position (1-based, inclusive)
     * @param end end position (1-based, inclusive)
     * @return the sequence string
     * @throws IllegalStateException if no genome is loaded
     */
    public String getBases(String chrom, int start, int end) {
        if (currentGenome == null) {
            throw new IllegalStateException("No reference genome loaded");
        }
        return currentGenome.getBases(chrom, start, end);
    }
    
    /**
     * Get chromosome length from the reference genome.
     * 
     * @param chrom chromosome name
     * @return the chromosome length in base pairs
     * @throws IllegalStateException if no genome is loaded
     */
    public long getChromosomeLength(String chrom) {
        if (currentGenome == null) {
            throw new IllegalStateException("No reference genome loaded");
        }
        return currentGenome.getChromosomeLength(chrom);
    }
}

package org.baseplayer.services;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Manages viewport state (current chromosome, active view regions).
 * Replaces SharedModel viewport-related fields.
 * 
 * Thread-safe: All operations use JavaFX properties which handle thread safety.
 */
public class ViewportState {
    private final StringProperty currentChromosome = new SimpleStringProperty("1");
    
    public ViewportState() {
        // Initialize with default chromosome
    }
    
    /**
     * Get the currently displayed chromosome.
     */
    public String getCurrentChromosome() {
        return currentChromosome.get();
    }
    
    /**
     * Set the current chromosome for display.
     * 
     * @param chromosome the chromosome name (e.g., "1", "X", "MT")
     */
    public void setCurrentChromosome(String chromosome) {
        if (chromosome == null || chromosome.isEmpty()) {
            throw new IllegalArgumentException("Chromosome cannot be null or empty");
        }
        this.currentChromosome.set(chromosome);
    }
    
    /**
     * Get the observable property for the current chromosome.
     * Useful for binding to UI components.
     */
    public StringProperty currentChromosomeProperty() {
        return currentChromosome;
    }
}

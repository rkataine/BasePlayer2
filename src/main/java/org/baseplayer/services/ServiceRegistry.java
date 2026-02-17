package org.baseplayer.services;

/**
 * Simple service registry for dependency injection.
 * 
 * This singleton provides centralized access to all application services,
 * replacing the static fields in SharedModel with proper service instances.
 * 
 * Usage:
 * <pre>
 * ServiceRegistry services = ServiceRegistry.getInstance();
 * ViewportState viewport = services.getViewportState();
 * viewport.setCurrentChromosome("5");
 * </pre>
 * 
 * Future improvement: Replace with a proper DI framework (e.g., Spring, Guice)
 * if the application grows larger.
 */
public class ServiceRegistry {
    
    private static ServiceRegistry instance;
    
    private final ViewportState viewportState;
    private final SampleRegistry sampleRegistry;
    private final ReferenceGenomeService referenceGenomeService;
    
    /**
     * Private constructor - use getInstance() to access.
     */
    private ServiceRegistry() {
        this.viewportState = new ViewportState();
        this.sampleRegistry = new SampleRegistry();
        this.referenceGenomeService = new ReferenceGenomeService();
    }
    
    /**
     * Get the singleton instance of the service registry.
     * Thread-safe lazy initialization.
     */
    public static synchronized ServiceRegistry getInstance() {
        if (instance == null) {
            instance = new ServiceRegistry();
        }
        return instance;
    }
    
    /**
     * Get the viewport state service.
     */
    public ViewportState getViewportState() {
        return viewportState;
    }
    
    /**
     * Get the sample registry service.
     */
    public SampleRegistry getSampleRegistry() {
        return sampleRegistry;
    }
    
    /**
     * Get the reference genome service.
     */
    public ReferenceGenomeService getReferenceGenomeService() {
        return referenceGenomeService;
    }
    
    /**
     * Reset the service registry (useful for testing).
     * This clears all service state and creates new instances.
     */
    public static synchronized void reset() {
        instance = null;
    }
}

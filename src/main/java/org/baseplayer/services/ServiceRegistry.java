package org.baseplayer.services;

import org.baseplayer.genome.ReferenceGenomeService;

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
    private final FeatureTrackViewportRegistry featureTrackViewportRegistry;
    private final ReferenceGenomeService referenceGenomeService;
    private final DrawStackManager drawStackManager;
    private final RegionFetchCache regionFetchCache;
    
    private ServiceRegistry() {
        this.viewportState = new ViewportState();
        this.sampleRegistry = new SampleRegistry();
        this.featureTrackViewportRegistry = new FeatureTrackViewportRegistry();
        this.referenceGenomeService = new ReferenceGenomeService();
        this.drawStackManager = new DrawStackManager();
        this.regionFetchCache = new RegionFetchCache();
        // Eagerly initialise LoadingManager so it registers with ThreadRunner before any tasks are submitted.
        LoadingManager.get();
    }
    
    public static synchronized ServiceRegistry getInstance() {
        if (instance == null) {
            instance = new ServiceRegistry();
        }
        return instance;
    }
    
    public ViewportState getViewportState() {
        return viewportState;
    }
    
    public SampleRegistry getSampleRegistry() {
        return sampleRegistry;
    }

    public FeatureTrackViewportRegistry getFeatureTrackViewportRegistry() {
        return featureTrackViewportRegistry;
    }
    
    public ReferenceGenomeService getReferenceGenomeService() {
        return referenceGenomeService;
    }
    
    public DrawStackManager getDrawStackManager() {
        return drawStackManager;
    }

    public RegionFetchCache getRegionFetchCache() {
        return regionFetchCache;
    }

    
    public static synchronized void reset() {
        instance = null;
    }
}

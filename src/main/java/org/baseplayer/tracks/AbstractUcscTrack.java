package org.baseplayer.tracks;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.baseplayer.alignment.FetchManager;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.io.APIs.UcscApiClient.ConservationData;
import org.baseplayer.utils.AppFonts;

import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

/**
 * Abstract base class for tracks that fetch data from UCSC API.
 * Handles common functionality: debouncing, caching, loading states, FetchManager integration.
 * 
 * Subclasses need to implement:
 * - fetchDataFromApi() - specify which UCSC endpoint to call
 * - drawTrackData() - render the data
 * - getZoomMessage() - message shown when zoomed out too far
 */
public abstract class AbstractUcscTrack extends AbstractTrack {
  
  protected static final int MAX_REGION_SIZE = 100_000;
  protected static final int FETCH_DELAY_MS = 1500;  // Wait before fetching
  
  protected ConservationData currentData = null;
  protected String cachedChromosome = null;
  protected CompletableFuture<?> pendingFetch = null;
  protected ScheduledFuture<?> scheduledFetch = null;
  protected boolean loading = false;
  protected boolean fetching = false;
  protected Runnable onDataLoaded;
  
  private static final ScheduledExecutorService scheduler = 
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AbstractUcscTrack-Scheduler");
        t.setDaemon(true);
        return t;
      });
  
  protected AbstractUcscTrack(String name, String source) {
    super(name, source);
  }
  
  /**
   * Fetch data from UCSC API for the specified region.
   * Subclasses implement this to call the appropriate UCSC API endpoint.
   */
  protected abstract CompletableFuture<ConservationData> fetchDataFromApi(
      String chromosome, long start, long end, int bins);
  
  /**
   * Draw the track data. Called after checking region size and loading state.
   */
  protected abstract void drawTrackData(GraphicsContext gc, double x, double y, 
      double width, double height, double viewStart, double viewEnd);
  
  /**
   * Get the message to display when zoomed out too far.
   * Default: "Zoom to < 100kb to see data"
   */
  protected String getZoomMessage() {
    return "Zoom to < 100kb to see data";
  }
  
  /**
   * Set callback for when data is loaded (to trigger redraw).
   */
  public void setOnDataLoaded(Runnable callback) {
    this.onDataLoaded = callback;
  }
  
  @Override
  public void onRegionChanged(String chromosome, long start, long end) {
    if (!visible) return;
    
    // Block during zoom animation
    if (GenomicCanvas.animationRunning) return;
    
    // Too large to fetch
    if (end - start > MAX_REGION_SIZE) {
      currentData = null;
      return;
    }
    
    // Cancel any scheduled fetch (debounce)
    if (scheduledFetch != null && !scheduledFetch.isDone()) {
      scheduledFetch.cancel(false);
    }
    
    // Don't schedule new fetch if already fetching
    if (fetching) {
      return;
    }
    
    // Schedule fetch with delay (debounce)
    // Note: We rely on UcscApiClient's internal caching to avoid redundant API calls
    // The debouncing here is just to avoid making rapid calls during scrolling/zooming
    final String fetchChrom = chromosome;
    final long fetchStart = start;
    final long fetchEnd = end;
    final int bins = (int) Math.min(1000, end - start);
    
    scheduledFetch = scheduler.schedule(() -> {
      executeFetch(fetchChrom, fetchStart, fetchEnd, bins);
    }, FETCH_DELAY_MS, TimeUnit.MILLISECONDS);
  }
  
  private void executeFetch(String chromosome, long start, long end, int bins) {
    // Double-check we're not already fetching
    if (fetching) return;

    // Check FetchManager before fetching
    FetchManager fm = FetchManager.get();
    if (!fm.canFetch(FetchManager.FetchType.FEATURE_TRACK, (int)(end - start))) {
      return; // memory too low
    }
    
    // Actually need to fetch - show loading indicator
    loading = true;
    fetching = true;
    
    System.out.println(name + ": Requesting data for " + chromosome + ":" + start + "-" + end + 
                      " (UcscApiClient will use cache if available)");
    
    // Trigger UI update to show loading indicator
    if (onDataLoaded != null) {
      Platform.runLater(onDataLoaded);
    }
    
    // Cancel any pending fetch
    if (pendingFetch != null && !pendingFetch.isDone()) {
      pendingFetch.cancel(true);
    }

    FetchManager.FetchTicket ticket = fm.acquire(
        FetchManager.FetchType.FEATURE_TRACK, this, null, chromosome, (int) start, (int) end);
    
    // Call subclass implementation to fetch data
    pendingFetch = fetchDataFromApi(chromosome, start, end, bins)
        .thenAccept(data -> {
          Platform.runLater(() -> {
            if (!ticket.isCancelled()) {
              currentData = data;
              cachedChromosome = chromosome;
              if (data.hasData()) {
                System.out.println(name + ": Received data for " + chromosome + ":" + 
                                  data.start() + "-" + data.end() + 
                                  " (requested: " + start + "-" + end + ")");
              } else if (data.hasError()) {
                System.err.println(name + ": Error: " + data.errorMessage());
              }
            }
            loading = false;
            fetching = false;
            fm.release(ticket);
            if (onDataLoaded != null) {
              onDataLoaded.run();
            }
          });
        })
        .exceptionally(e -> {
          Platform.runLater(() -> {
            loading = false;
            fetching = false;
            fm.release(ticket);
          });
          return null;
        });
  }
  
  @Override
  public boolean isLoading() {
    return loading;
  }
  
  @Override
  public boolean hasDataForRegion(String chromosome, long start, long end) {
    return (end - start) <= MAX_REGION_SIZE;
  }
  
  @Override
  public void draw(GraphicsContext gc, double x, double y, double width, double height,
                   String chromosome, double start, double end) {
    
    // Track background
    gc.setFill(Color.rgb(25, 25, 30));
    gc.fillRect(x, y, width, height);
    
    // Track label
    gc.setFill(Color.GRAY);
    gc.setFont(AppFonts.getUIFont(9));
    gc.setTextAlign(TextAlignment.LEFT);
    gc.fillText(name, x + 4, y + 10);
    
    // Check region size
    if (end - start > MAX_REGION_SIZE) {
      gc.setFill(Color.rgb(80, 80, 80));
      gc.setFont(AppFonts.getUIFont(10));
      gc.setTextAlign(TextAlignment.CENTER);
      gc.fillText(getZoomMessage(), x + width / 2, y + height / 2 + 4);
      gc.setTextAlign(TextAlignment.LEFT);
      return;
    }
    
    double barTop = y + 14;
    double barHeight = height - 18;
    
    // Loading indicator
    if (loading) {
      gc.setFill(Color.rgb(100, 100, 100));
      gc.setFont(AppFonts.getUIFont(10));
      gc.setTextAlign(TextAlignment.CENTER);
      gc.fillText("Loading...", x + width / 2, y + height / 2 + 4);
      gc.setTextAlign(TextAlignment.LEFT);
      return;
    }
    
    // Check for data/errors
    if (currentData != null && currentData.hasData()) {
      // Draw the actual data (delegate to subclass)
      drawTrackData(gc, x, barTop, width, barHeight, start, end);
    } else if (currentData != null && !currentData.hasData()) {
      gc.setFont(AppFonts.getUIFont(10));
      gc.setTextAlign(TextAlignment.CENTER);
      if (currentData.hasError()) {
        gc.setFill(Color.rgb(180, 100, 100));
        gc.fillText(currentData.errorMessage(), x + width / 2, y + height / 2 + 4);
      } else {
        gc.setFill(Color.rgb(80, 80, 80));
        gc.fillText("No data for this region", x + width / 2, y + height / 2 + 4);
      }
      gc.setTextAlign(TextAlignment.LEFT);
    }
    // If currentData is null and not loading, nothing is shown (waiting for fetch to start)
  }
  
  @Override
  public void dispose() {
    if (scheduledFetch != null) {
      scheduledFetch.cancel(false);
    }
    if (pendingFetch != null) {
      pendingFetch.cancel(true);
    }
    currentData = null;
    cachedChromosome = null;
  }
}

package org.baseplayer.tracks;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.baseplayer.io.UcscApiClient;
import org.baseplayer.io.UcscApiClient.ConservationData;
import org.baseplayer.utils.AppFonts;

import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

/**
 * Track displaying PhyloP conservation scores from UCSC API.
 * Shows conservation (positive scores) and fast-evolving regions (negative scores).
 */
public class ConservationTrack extends AbstractTrack {
  
  private static final int MAX_REGION_SIZE = 100_000;
  private static final int FETCH_DELAY_MS = 1500;  // Wait before fetching
  
  private ConservationData currentData = null;
  private String cachedChromosome = null;  // Track which chromosome the data is for
  private CompletableFuture<?> pendingFetch = null;
  private ScheduledFuture<?> scheduledFetch = null;
  private boolean loading = false;
  private boolean fetching = false;  // True while actually fetching from API
  private Runnable onDataLoaded;
  
  private static final ScheduledExecutorService scheduler = 
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ConservationTrack-Scheduler");
        t.setDaemon(true);
        return t;
      });
  
  public ConservationTrack() {
    super("PhyloP Conservation", "UCSC API");
    this.preferredHeight = 35;
    this.color = Color.rgb(100, 180, 100);
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
    
    // Too large to fetch
    if (end - start > MAX_REGION_SIZE) {
      currentData = null;
      return;
    }
    
    long regionSize = end - start;
    boolean needsBaseLevelData = regionSize <= UcscApiClient.getBaseLevelThreshold();
    
    // Check if current data already covers this region
    if (currentData != null && !currentData.hasError()) {
      boolean sameChromosome = cachedChromosome != null && cachedChromosome.equals(chromosome);
      boolean coversRegion = currentData.start() <= start && currentData.end() >= end;
      boolean hasCorrectResolution = !needsBaseLevelData || currentData.isBaseLevelData();
      
      if (sameChromosome && coversRegion && hasCorrectResolution) {
        // Data already covers this region with correct resolution - no fetch needed
        return;
      }
    }
    
    // Cancel any scheduled fetch (debounce)
    if (scheduledFetch != null && !scheduledFetch.isDone()) {
      scheduledFetch.cancel(false);
    }
    
    // Don't schedule new fetch if already fetching
    if (fetching) {
      return;
    }
    
    // NOTE: Don't set loading=true here - only set it in executeFetch when actually fetching from API
    // This prevents "Loading..." appearing during debounce or when using cached data
    
    // Schedule fetch with delay (debounce)
    final String fetchChrom = chromosome;
    final long fetchStart = start;
    final long fetchEnd = end;
    final int bins = (int) Math.min(1000, end - start);
    final boolean fetchBaseLevelData = needsBaseLevelData;
    
    scheduledFetch = scheduler.schedule(() -> {
      executeFetch(fetchChrom, fetchStart, fetchEnd, bins, fetchBaseLevelData);
    }, FETCH_DELAY_MS, TimeUnit.MILLISECONDS);
  }
  
  private void executeFetch(String chromosome, long start, long end, int bins, boolean needsBaseLevelData) {
    // Double-check we're not already fetching
    if (fetching) return;
    
    // Re-check if we still need data (another fetch might have completed)
    if (currentData != null && !currentData.hasError()) {
      boolean sameChromosome = cachedChromosome != null && cachedChromosome.equals(chromosome);
      boolean coversRegion = currentData.start() <= start && currentData.end() >= end;
      boolean hasCorrectResolution = !needsBaseLevelData || currentData.isBaseLevelData();
      
      if (sameChromosome && coversRegion && hasCorrectResolution) {
        // Data already cached - no fetch needed, no loading indicator
        return;
      }
    }
    
    // Actually need to fetch from API - now show loading indicator
    loading = true;
    fetching = true;
    
    // Trigger UI update to show loading indicator
    if (onDataLoaded != null) {
      Platform.runLater(onDataLoaded);
    }
    
    // Cancel any pending fetch
    if (pendingFetch != null && !pendingFetch.isDone()) {
      pendingFetch.cancel(true);
    }
    
    // UcscApiClient will automatically use per-base data for small regions (<10kb)
    // and binned data for larger regions, with smart caching
    pendingFetch = UcscApiClient.fetchConservation(chromosome, start, end, bins)
        .thenAccept(data -> {
          Platform.runLater(() -> {
            currentData = data;
            cachedChromosome = chromosome;  // Track which chromosome the data is for
            loading = false;
            fetching = false;
            if (onDataLoaded != null) {
              onDataLoaded.run();
            }
          });
        })
        .exceptionally(e -> {
          Platform.runLater(() -> {
            loading = false;
            fetching = false;
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
      gc.fillText("Zoom to < 100kb to see conservation", x + width / 2, y + height / 2 + 4);
      gc.setTextAlign(TextAlignment.LEFT);
      return;
    }
    
    double barTop = y + 14;
    double barHeight = height - 18;
    double barCenter = barTop + barHeight / 2;
    
    // Draw zero line
    gc.setStroke(Color.rgb(60, 60, 60));
    gc.setLineWidth(0.5);
    gc.strokeLine(x, barCenter, x + width, barCenter);
    
    // Loading indicator
    if (loading) {
      gc.setFill(Color.rgb(100, 100, 100));
      gc.setFont(AppFonts.getUIFont(10));
      gc.setTextAlign(TextAlignment.CENTER);
      gc.fillText("Loading...", x + width / 2, y + height / 2 + 4);
      gc.setTextAlign(TextAlignment.LEFT);
      return;
    }
    
    // Draw data
    if (currentData != null && currentData.hasData()) {
      drawBars(gc, x, barTop, barCenter, width, barHeight, start, end);
    } else if (currentData != null && !currentData.hasData()) {
      gc.setFont(AppFonts.getUIFont(10));
      gc.setTextAlign(TextAlignment.CENTER);
      if (currentData.hasError()) {
        // Show error message (network offline, API error, etc.)
        gc.setFill(Color.rgb(180, 100, 100));
        gc.fillText(currentData.errorMessage(), x + width / 2, y + height / 2 + 4);
      } else {
        // Genuinely no data for this region
        gc.setFill(Color.rgb(80, 80, 80));
        gc.fillText("No data for this region", x + width / 2, y + height / 2 + 4);
      }
      gc.setTextAlign(TextAlignment.LEFT);
    }
    // If currentData is null and not loading, nothing is shown (waiting for fetch to start)
  }
  
  private void drawBars(GraphicsContext gc, double x, double barTop, double barCenter, 
                        double width, double barHeight, double viewStart, double viewEnd) {
    
    double[] scores = currentData.scores();
    double dataStart = (double) currentData.start();
    double dataEnd = (double) currentData.end();
    int bins = scores.length;
    
    double dataBinSize = (dataEnd - dataStart) / bins;
    double halfHeight = barHeight / 2;
    
    // Scale factor for scores - use track settings if provided, otherwise auto-scale
    double displayMin = minValue != null ? minValue : currentData.minScore();
    double displayMax = maxValue != null ? maxValue : currentData.maxScore();
    double maxAbsScore = Math.max(Math.abs(displayMin), Math.abs(displayMax));
    if (maxAbsScore < 1) maxAbsScore = 1;
    
    // Map data to screen
    double viewLength = viewEnd - viewStart;
    double pixelsPerBase = width / viewLength;
    
    // For per-base data at high zoom, use optimized drawing
    boolean isBaseLevelData = currentData.isBaseLevelData();
    
    // Offset to align with reference bases (centered on the base position)
    double baseOffset = pixelsPerBase;
    
    for (int i = 0; i < bins; i++) {
      double score = scores[i];
      if (score == 0) continue;
      
      // Calculate genomic position for this bin/base - keep as double for smooth scrolling
      double binGenomicStart = dataStart + (i * dataBinSize);
      double binGenomicEnd = isBaseLevelData ? (binGenomicStart + 1.0) : (dataStart + ((i + 1) * dataBinSize));
      
      // Skip if outside view
      if (binGenomicEnd < viewStart || binGenomicStart > viewEnd) continue;
      
      // Map to screen coordinates with offset for base alignment
      double screenX1 = x + ((binGenomicStart - viewStart) / viewLength) * width + baseOffset;
      double screenX2 = x + ((binGenomicEnd - viewStart) / viewLength) * width + baseOffset;
      double binWidth = Math.max(1, screenX2 - screenX1);
      
      double barDisplayHeight = Math.abs(score) / maxAbsScore * halfHeight;
      barDisplayHeight = Math.min(barDisplayHeight, halfHeight);
      
      if (score > 0) {
        // Conserved - draw upward in green
        gc.setFill(getConservedColor(score / maxAbsScore));
        gc.fillRect(screenX1, barCenter - barDisplayHeight, binWidth, barDisplayHeight);
      } else {
        // Fast-evolving - draw downward in red
        gc.setFill(getFastEvolvingColor(Math.abs(score) / maxAbsScore));
        gc.fillRect(screenX1, barCenter, binWidth, barDisplayHeight);
      }
    }
    
    // Scale labels (show display range)
    gc.setFill(Color.rgb(80, 80, 80));
    gc.setFont(AppFonts.getUIFont(8));
    gc.fillText(String.format("+%.1f", displayMax), x + 4, barTop + 8);
    gc.fillText(String.format("%.1f", displayMin), x + 4, barTop + barHeight - 2);
    
    // Indicator for per-base data
    if (isBaseLevelData) {
      gc.setFill(Color.rgb(100, 100, 150));
      gc.setFont(AppFonts.getUIFont(8));
      gc.fillText("(bp)", x + width - 25, barTop + 8);
    }
  }
  
  private Color getConservedColor(double intensity) {
    intensity = Math.min(1.0, Math.max(0.0, intensity));
    int green = (int)(100 + 155 * intensity);
    return Color.rgb(30, green, 60);
  }
  
  private Color getFastEvolvingColor(double intensity) {
    intensity = Math.min(1.0, Math.max(0.0, intensity));
    int red = (int)(100 + 155 * intensity);
    return Color.rgb(red, 40, 40);
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

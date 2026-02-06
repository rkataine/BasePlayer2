package org.baseplayer.tracks;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.baseplayer.io.GnomadApiClient;
import org.baseplayer.io.GnomadApiClient.Variant;
import org.baseplayer.io.GnomadApiClient.VariantData;
import org.baseplayer.utils.AppFonts;

import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;

/**
 * Track displaying gnomAD population variant frequency data.
 * Shows variants color-coded by consequence:
 * - Red: Loss-of-function (HIGH impact)
 * - Orange: Missense variants (MODERATE impact)
 * - Blue: Synonymous variants (LOW impact)
 * - Gray: Other variants (MODIFIER)
 * 
 * Variant height indicates allele frequency (taller = more common).
 */
public class GnomadTrack extends AbstractTrack {
  
  private static final int MAX_REGION_SIZE = 50_000;
  private static final int FETCH_DELAY_MS = 1500;  // Wait before fetching
  
  // Colors for different variant types
  private static final Color LOF_COLOR = Color.rgb(200, 50, 50);      // Red - loss of function
  private static final Color MISSENSE_COLOR = Color.rgb(220, 140, 40); // Orange - missense
  private static final Color SYNONYMOUS_COLOR = Color.rgb(80, 140, 200); // Blue - synonymous
  private static final Color OTHER_COLOR = Color.rgb(120, 120, 120);   // Gray - other
  
  private VariantData currentData = null;
  private String cachedChromosome = null;
  private CompletableFuture<?> pendingFetch = null;
  private ScheduledFuture<?> scheduledFetch = null;
  private boolean loading = false;
  private boolean fetching = false;
  private Runnable onDataLoaded;
  
  // Click handling
  private final GnomadVariantPopup popup = new GnomadVariantPopup();
  private double lastDrawX, lastDrawY, lastDrawWidth, lastDrawHeight;
  private double lastViewStart, lastViewEnd;
  
  private static final ScheduledExecutorService scheduler = 
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "GnomadTrack-Scheduler");
        t.setDaemon(true);
        return t;
      });
  
  public GnomadTrack() {
    super("gnomAD Variants", "gnomAD v4");
    this.preferredHeight = 40;
    this.color = Color.rgb(180, 100, 100);
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
    
    // Check if current data already covers this region
    if (currentData != null && !currentData.hasError()) {
      boolean sameChromosome = cachedChromosome != null && cachedChromosome.equals(chromosome);
      boolean coversRegion = currentData.start() <= start && currentData.end() >= end;
      
      if (sameChromosome && coversRegion) {
        // Data already covers this region - no fetch needed
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
    
    // Schedule fetch with delay (debounce)
    final String fetchChrom = chromosome;
    final long fetchStart = start;
    final long fetchEnd = end;
    
    scheduledFetch = scheduler.schedule(() -> {
      executeFetch(fetchChrom, fetchStart, fetchEnd);
    }, FETCH_DELAY_MS, TimeUnit.MILLISECONDS);
  }
  
  private void executeFetch(String chromosome, long start, long end) {
    // Double-check we're not already fetching
    if (fetching) return;
    
    // Re-check if we still need data
    if (currentData != null && !currentData.hasError()) {
      boolean sameChromosome = cachedChromosome != null && cachedChromosome.equals(chromosome);
      boolean coversRegion = currentData.start() <= start && currentData.end() >= end;
      
      if (sameChromosome && coversRegion) {
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
    
    pendingFetch = GnomadApiClient.fetchVariants(chromosome, start, end)
        .thenAccept(data -> {
          Platform.runLater(() -> {
            currentData = data;
            cachedChromosome = chromosome;
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
    
    // Save draw parameters for hit detection
    lastDrawX = x;
    lastDrawY = y;
    lastDrawWidth = width;
    lastDrawHeight = height;
    lastViewStart = start;
    lastViewEnd = end;
    
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
      gc.fillText("Zoom to < 50kb to see gnomAD variants", x + width / 2, y + height / 2 + 4);
      gc.setTextAlign(TextAlignment.LEFT);
      return;
    }
    
    double barTop = y + 14;
    double barHeight = height - 18;
    
    // Draw baseline
    gc.setStroke(Color.rgb(60, 60, 60));
    gc.setLineWidth(0.5);
    gc.strokeLine(x, y + height - 4, x + width, y + height - 4);
    
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
      drawVariants(gc, x, barTop, width, barHeight, start, end);
    } else if (currentData != null && !currentData.hasData()) {
      gc.setFont(AppFonts.getUIFont(10));
      gc.setTextAlign(TextAlignment.CENTER);
      if (currentData.hasError()) {
        gc.setFill(Color.rgb(180, 100, 100));
        gc.fillText(currentData.errorMessage(), x + width / 2, y + height / 2 + 4);
      } else {
        gc.setFill(Color.rgb(80, 80, 80));
        gc.fillText("No variants in this region", x + width / 2, y + height / 2 + 4);
      }
      gc.setTextAlign(TextAlignment.LEFT);
    }
    
    // Draw legend
    drawLegend(gc, x + width - 180, y + 2);
  }
  
  private void drawVariants(GraphicsContext gc, double x, double barTop, 
                           double width, double barHeight, double viewStart, double viewEnd) {
    
    List<Variant> variants = currentData.variants();
    if (variants.isEmpty()) return;
    
    double viewLength = viewEnd - viewStart;
    double pixelsPerBase = width / viewLength;
    
    // Find max AF for scaling (use log scale for better visualization)
    double maxLogAf = Math.log10(0.5);  // Cap at 50%
    double minLogAf = Math.log10(0.00001);  // Floor at 0.001%
    
    for (Variant v : variants) {
      long pos = v.position();
      
      // Skip if outside view
      if (pos < viewStart || pos > viewEnd) continue;
      
      // Map to screen coordinates
      double screenX = x + ((pos - viewStart) / viewLength) * width;
      
      // Calculate height based on allele frequency (log scale)
      double af = Math.max(v.alleleFrequency(), 0.00001);
      double logAf = Math.log10(af);
      double heightFraction = (logAf - minLogAf) / (maxLogAf - minLogAf);
      heightFraction = Math.min(1.0, Math.max(0.1, heightFraction));  // Min 10% height
      
      double variantHeight = barHeight * heightFraction;
      double variantTop = barTop + barHeight - variantHeight;
      
      // Choose color based on variant type
      Color variantColor = getVariantColor(v);
      
      // Draw variant as vertical line/bar
      double barWidth = Math.max(1, pixelsPerBase);
      if (barWidth > 3) {
        // Draw as rectangle when zoomed in
        gc.setFill(variantColor);
        gc.fillRect(screenX, variantTop, barWidth - 1, variantHeight);
      } else {
        // Draw as line when zoomed out
        gc.setStroke(variantColor);
        gc.setLineWidth(1);
        gc.strokeLine(screenX, variantTop, screenX, barTop + barHeight);
      }
    }
  }
  
  private Color getVariantColor(Variant v) {
    if (v.isLoF()) {
      return LOF_COLOR;
    } else if (v.isMissense()) {
      return MISSENSE_COLOR;
    } else if (v.isSynonymous()) {
      return SYNONYMOUS_COLOR;
    }
    return OTHER_COLOR;
  }
  
  private void drawLegend(GraphicsContext gc, double x, double y) {
    gc.setFont(AppFonts.getUIFont(8));
    
    double boxSize = 6;
    double spacing = 40;
    
    // LoF
    gc.setFill(LOF_COLOR);
    gc.fillRect(x, y + 2, boxSize, boxSize);
    gc.setFill(Color.rgb(150, 150, 150));
    gc.fillText("LoF", x + boxSize + 2, y + 8);
    
    // Missense
    gc.setFill(MISSENSE_COLOR);
    gc.fillRect(x + spacing, y + 2, boxSize, boxSize);
    gc.setFill(Color.rgb(150, 150, 150));
    gc.fillText("Mis", x + spacing + boxSize + 2, y + 8);
    
    // Synonymous
    gc.setFill(SYNONYMOUS_COLOR);
    gc.fillRect(x + spacing * 2, y + 2, boxSize, boxSize);
    gc.setFill(Color.rgb(150, 150, 150));
    gc.fillText("Syn", x + spacing * 2 + boxSize + 2, y + 8);
    
    // Other
    gc.setFill(OTHER_COLOR);
    gc.fillRect(x + spacing * 3, y + 2, boxSize, boxSize);
    gc.setFill(Color.rgb(150, 150, 150));
    gc.fillText("Oth", x + spacing * 3 + boxSize + 2, y + 8);
  }
  
  // ============= Click handling =============
  
  @Override
  public boolean supportsClick() {
    return true;
  }
  
  @Override
  public boolean handleClick(double clickX, double clickY, double trackWidth, double trackHeight,
                             String chromosome, double viewStart, double viewEnd,
                             Window owner, double screenX, double screenY) {
    
    if (currentData == null || !currentData.hasData() || currentData.variants().isEmpty()) {
      return false;
    }
    
    // Find variant at click position
    Variant clicked = findVariantAtPosition(clickX, trackWidth, viewStart, viewEnd);
    
    if (clicked != null) {
      popup.setVariant(clicked);
      popup.show(owner, screenX, screenY);
      return true;
    }
    
    return false;
  }
  
  /**
   * Find a variant at the given screen X position.
   */
  private Variant findVariantAtPosition(double clickX, double trackWidth, 
                                        double viewStart, double viewEnd) {
    
    List<Variant> variants = currentData.variants();
    double viewLength = viewEnd - viewStart;
    double pixelsPerBase = trackWidth / viewLength;
    
    // Calculate genomic position from click
    double genomicPos = viewStart + (clickX / trackWidth) * viewLength;
    
    // Find closest variant within tolerance
    double tolerance = Math.max(3, pixelsPerBase * 2);  // At least 3 pixels
    double toleranceBases = tolerance / pixelsPerBase;
    
    Variant closest = null;
    double closestDist = Double.MAX_VALUE;
    
    for (Variant v : variants) {
      double dist = Math.abs(v.position() - genomicPos);
      if (dist < toleranceBases && dist < closestDist) {
        closest = v;
        closestDist = dist;
      }
    }
    
    return closest;
  }
  
  @Override
  public void hidePopup() {
    popup.hide();
  }
  
  @Override
  public void dispose() {
    popup.hide();
    if (scheduledFetch != null) {
      scheduledFetch.cancel(false);
    }
    if (pendingFetch != null) {
      pendingFetch.cancel(true);
    }
  }
}

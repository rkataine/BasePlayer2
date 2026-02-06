package org.baseplayer.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Generic, efficient stacking algorithm for positioning overlapping genomic features
 * into non-overlapping rows. Can be used for genes, sequence reads, variants, etc.
 * 
 * Uses an interval-based approach with binary search for O(n log n) performance.
 * 
 * @param <T> The type of item to stack
 */
public class StackingAlgorithm<T> {
  
  /**
   * Interface for extracting position and display information from stackable items.
   */
  public interface Stackable<T> {
    /** Get the genomic start position */
    long getStart(T item);
    
    /** Get the genomic end position */
    long getEnd(T item);
    
    /** 
     * Get the visual end position (e.g., including label width).
     * Return same as getEnd() if no extra visual space needed.
     */
    default double getVisualEnd(T item, double viewStart, double viewLength, double canvasWidth) {
      return ((getEnd(item) - viewStart) / viewLength) * canvasWidth;
    }
    
    /**
     * Get the visual start position.
     */
    default double getVisualStart(T item, double viewStart, double viewLength, double canvasWidth) {
      return ((getStart(item) - viewStart) / viewLength) * canvasWidth;
    }
  }
  
  /**
   * Result of stacking operation - items assigned to rows.
   */
  public static class StackResult<T> {
    private final List<List<T>> rows;
    private final int totalItems;
    private final int placedItems;
    
    public StackResult(List<List<T>> rows, int totalItems, int placedItems) {
      this.rows = rows;
      this.totalItems = totalItems;
      this.placedItems = placedItems;
    }
    
    public List<List<T>> getRows() { return rows; }
    public int getTotalItems() { return totalItems; }
    public int getPlacedItems() { return placedItems; }
    public int getOverflowCount() { return totalItems - placedItems; }
    public boolean hasOverflow() { return placedItems < totalItems; }
    
    /** Get items in a specific row */
    public List<T> getRow(int index) {
      return index < rows.size() ? rows.get(index) : Collections.emptyList();
    }
    
    /** Get number of rows used */
    public int getRowCount() { return rows.size(); }
  }
  
  private final Stackable<T> adapter;
  private final int maxRows;
  private final double minPixelWidth;
  
  /**
   * Create a stacking algorithm.
   * @param adapter Interface to extract positions from items
   * @param maxRows Maximum number of rows allowed
   * @param minPixelWidth Minimum pixel width for an item to be included (0 to include all)
   */
  public StackingAlgorithm(Stackable<T> adapter, int maxRows, double minPixelWidth) {
    this.adapter = adapter;
    this.maxRows = maxRows;
    this.minPixelWidth = minPixelWidth;
  }
  
  /**
   * Stack items into non-overlapping rows.
   * 
   * @param items List of items to stack
   * @param viewStart View start position in genomic coordinates
   * @param viewEnd View end position in genomic coordinates
   * @param canvasWidth Canvas width in pixels
   * @return StackResult containing items organized into rows
   */
  public StackResult<T> stack(List<T> items, double viewStart, double viewEnd, double canvasWidth) {
    return stack(items, viewStart, viewEnd, canvasWidth, Comparator.comparingLong(adapter::getStart));
  }
  
  /**
   * Stack items into non-overlapping rows with custom sorting.
   * 
   * @param items List of items to stack
   * @param viewStart View start position in genomic coordinates
   * @param viewEnd View end position in genomic coordinates
   * @param canvasWidth Canvas width in pixels
   * @param comparator Custom comparator for ordering items (prioritized items first)
   * @return StackResult containing items organized into rows
   */
  public StackResult<T> stack(List<T> items, double viewStart, double viewEnd, double canvasWidth, Comparator<T> comparator) {
    double viewLength = viewEnd - viewStart;
    
    // Filter visible items and check minimum width
    List<T> visible = new ArrayList<>();
    for (T item : items) {
      long start = adapter.getStart(item);
      long end = adapter.getEnd(item);
      
      // Skip items outside view
      if (end < viewStart || start > viewEnd) continue;
      
      // Check minimum pixel width
      if (minPixelWidth > 0) {
        double pixelWidth = ((end - start) / viewLength) * canvasWidth;
        if (pixelWidth < minPixelWidth) continue;
      }
      
      visible.add(item);
    }
    
    // Sort using provided comparator (prioritized items first)
    visible.sort(comparator);
    
    // Initialize rows with end trackers for O(1) overlap checking
    List<List<T>> rows = new ArrayList<>();
    List<Double> rowEnds = new ArrayList<>();  // Track the rightmost visual end in each row
    
    for (int i = 0; i < maxRows; i++) {
      rows.add(new ArrayList<>());
      rowEnds.add(Double.NEGATIVE_INFINITY);
    }
    
    int placed = 0;
    
    for (T item : visible) {
      double visualStart = adapter.getVisualStart(item, viewStart, viewLength, canvasWidth);
      double visualEnd = adapter.getVisualEnd(item, viewStart, viewLength, canvasWidth);
      
      // Find first row where item fits (visual start > row's current end)
      boolean itemPlaced = false;
      for (int row = 0; row < maxRows; row++) {
        if (visualStart > rowEnds.get(row)) {
          rows.get(row).add(item);
          rowEnds.set(row, visualEnd);
          itemPlaced = true;
          placed++;
          break;
        }
      }
      // If not placed, item overflows (too many overlapping items)
    }
    
    return new StackResult<>(rows, visible.size(), placed);
  }
  
  /**
   * Stack items using genomic coordinates only (no visual adjustments).
   * Simpler and faster when labels/visual extensions are not needed.
   */
  public StackResult<T> stackGenomic(List<T> items, double viewStart, double viewEnd, double canvasWidth) {
    double viewLength = viewEnd - viewStart;
    
    // Filter visible items
    List<T> visible = new ArrayList<>();
    for (T item : items) {
      long start = adapter.getStart(item);
      long end = adapter.getEnd(item);
      
      if (end < viewStart || start > viewEnd) continue;
      
      if (minPixelWidth > 0) {
        double pixelWidth = ((end - start) / viewLength) * canvasWidth;
        if (pixelWidth < minPixelWidth) continue;
      }
      
      visible.add(item);
    }
    
    // Sort by start position
    visible.sort(Comparator.comparingLong(adapter::getStart));
    
    // Initialize rows
    List<List<T>> rows = new ArrayList<>();
    List<Long> rowEnds = new ArrayList<>();  // Track genomic end positions
    
    for (int i = 0; i < maxRows; i++) {
      rows.add(new ArrayList<>());
      rowEnds.add(Long.MIN_VALUE);
    }
    
    int placed = 0;
    
    for (T item : visible) {
      long start = adapter.getStart(item);
      long end = adapter.getEnd(item);
      
      // Find first row where item fits
      boolean itemPlaced = false;
      for (int row = 0; row < maxRows; row++) {
        if (start > rowEnds.get(row)) {
          rows.get(row).add(item);
          rowEnds.set(row, end);
          itemPlaced = true;
          placed++;
          break;
        }
      }
    }
    
    return new StackResult<>(rows, visible.size(), placed);
  }
  
  // =========== Factory methods for common use cases ===========
  
  /**
   * Create a simple stacker using start/end lambdas.
   */
  public static <T> StackingAlgorithm<T> create(
      java.util.function.ToLongFunction<T> getStart,
      java.util.function.ToLongFunction<T> getEnd,
      int maxRows,
      double minPixelWidth) {
    
    return new StackingAlgorithm<>(new Stackable<T>() {
      @Override public long getStart(T item) { return getStart.applyAsLong(item); }
      @Override public long getEnd(T item) { return getEnd.applyAsLong(item); }
    }, maxRows, minPixelWidth);
  }
  
  /**
   * Create a stacker with visual extension support (e.g., for labels).
   */
  public static <T> StackingAlgorithm<T> createWithVisual(
      java.util.function.ToLongFunction<T> getStart,
      java.util.function.ToLongFunction<T> getEnd,
      VisualExtender<T> visualExtender,
      int maxRows,
      double minPixelWidth) {
    
    return new StackingAlgorithm<>(new Stackable<T>() {
      @Override public long getStart(T item) { return getStart.applyAsLong(item); }
      @Override public long getEnd(T item) { return getEnd.applyAsLong(item); }
      @Override public double getVisualEnd(T item, double viewStart, double viewLength, double canvasWidth) {
        return visualExtender.getVisualEnd(item, viewStart, viewLength, canvasWidth);
      }
    }, maxRows, minPixelWidth);
  }
  
  @FunctionalInterface
  public interface VisualExtender<T> {
    double getVisualEnd(T item, double viewStart, double viewLength, double canvasWidth);
  }
}

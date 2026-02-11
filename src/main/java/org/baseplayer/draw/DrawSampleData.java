package org.baseplayer.draw;

import java.util.List;

import org.baseplayer.SharedModel;
import org.baseplayer.reads.bam.BAMRecord;
import org.baseplayer.reads.bam.SampleFile;
import org.baseplayer.variant.Variant;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

public class DrawSampleData extends DrawFunctions {
  
  public Image snapshot;
  private GraphicsContext gc;

  // Colors for BAM read rendering
  private static final Color READ_FORWARD = Color.rgb(120, 160, 200, 0.85);
  private static final Color READ_REVERSE = Color.rgb(200, 120, 130, 0.85);
  private static final Color READ_FORWARD_STROKE = Color.rgb(90, 130, 170);
  private static final Color READ_REVERSE_STROKE = Color.rgb(170, 90, 100);

  // Overlay colors (slightly different tint)
  private static final Color OVERLAY_FORWARD = Color.rgb(160, 200, 140, 0.7);
  private static final Color OVERLAY_REVERSE = Color.rgb(200, 180, 100, 0.7);
  private static final Color OVERLAY_FORWARD_STROKE = Color.rgb(130, 170, 110);
  private static final Color OVERLAY_REVERSE_STROKE = Color.rgb(170, 150, 70);

  // Don't query BAM when view is wider than this (too many reads)
  private static final double MAX_BAM_VIEW_LENGTH = 60_000;

  public DrawSampleData(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack) {
    super(reactiveCanvas, parent, drawStack);
    widthProperty().addListener((obs, oldVal, newVal) -> { resizing = true; setStartEnd(drawStack.start, drawStack.end); resizing = false; });
    gc = getGraphicsContext2D();
    gc.setLineWidth(1);

    Platform.runLater(() -> { draw(); });
  }
  void drawSnapShot() { if (snapshot != null) gc.drawImage(snapshot, 0, 0, getWidth(), getHeight()); }
  @Override
  public void draw() {
    gc.setFill(backgroundColor);
    gc.fillRect(0, 0, getWidth()+1, getHeight()+1);
    if (drawStack.variants != null) drawVariants();
    drawBamReads();
    super.draw();
  }
  void drawVariants() {    
    for (Variant variant : drawStack.variants) {
      if (variant.line.getEndX() < drawStack.start-1) continue;
      if (variant.line.getStartX() > drawStack.end) break;

      drawLine(variant, lineColor, gc);    
    }
  }
  void drawLine(Variant variant, Color color, GraphicsContext gc) {
    if (variant.index < SharedModel.firstVisibleSample || variant.index > SharedModel.lastVisibleSample + 1) return;
   
    gc.setStroke(color);
    gc.setFill(color);
    double screenPos = chromPosToScreenPos.apply(variant.line.getStartX());
    double ypos = SharedModel.MASTER_TRACK_HEIGHT + SharedModel.sampleHeight * variant.index - SharedModel.scrollBarPosition;
    double height = heightToScreen.apply(variant.line.getEndY());
    
    if (drawStack.pixelSize > 1) 
         gc.fillRect(screenPos, ypos - height, drawStack.pixelSize, height);
    else gc.strokeLine(screenPos, ypos, screenPos, ypos-height);
  }

  /**
   * Draw BAM reads for all loaded sample files.
   * Each sample occupies a horizontal strip (sampleHeight pixels tall).
   * Reads are packed into rows within that strip.
   */
  void drawBamReads() {
    if (SharedModel.bamFiles.isEmpty()) return;
    
    double canvasHeight = getHeight();
    int numSamples = SharedModel.sampleList.size();
    if (numSamples == 0) return;
    
    double masterOffset = SharedModel.MASTER_TRACK_HEIGHT;
    double availableHeight = canvasHeight - masterOffset;
    double sampleH = availableHeight / Math.max(1, SharedModel.visibleSamples().getAsInt());
    SharedModel.sampleHeight = sampleH;
    
    // Don't query when zoomed out too far
    if (drawStack.viewLength > MAX_BAM_VIEW_LENGTH) {
      gc.setFill(Color.web("#888888"));
      gc.setFont(javafx.scene.text.Font.font("Segoe UI", 11));
      for (int sampleIdx = 0; sampleIdx < SharedModel.bamFiles.size(); sampleIdx++) {
        if (sampleIdx < SharedModel.firstVisibleSample || sampleIdx > SharedModel.lastVisibleSample) continue;
        double sampleY = masterOffset + sampleIdx * sampleH - SharedModel.scrollBarPosition;
        gc.fillText("Zoom in to view reads", 10, sampleY + sampleH / 2 + 4);
      }
      return;
    }
    
    String chrom = drawStack.chromosome;
    int start = Math.max(0, (int) drawStack.start);
    int end = (int) drawStack.end;
    
    try {
      for (int sampleIdx = 0; sampleIdx < SharedModel.bamFiles.size(); sampleIdx++) {
        if (sampleIdx < SharedModel.firstVisibleSample || sampleIdx > SharedModel.lastVisibleSample) continue;
        
        SampleFile sample = SharedModel.bamFiles.get(sampleIdx);
        if (!sample.visible) continue;
        
        double sampleY = masterOffset + sampleIdx * sampleH - SharedModel.scrollBarPosition;
        
        // Draw main sample reads
        drawSampleFile(sample, chrom, start, end, sampleY, sampleH, canvasHeight);
        
        // Draw additional data files on the same track
        for (SampleFile sub : sample.getOverlays()) {
          if (!sub.visible) continue;
          drawSampleFile(sub, chrom, start, end, sampleY, sampleH, canvasHeight);
        }
      }
    } catch (Exception e) {
      System.err.println("Error drawing BAM reads: " + e.getMessage());
    }
  }

  // Minimum read height in pixels — prevents reads from becoming invisible
  private static final double MIN_READ_HEIGHT = 3;
  // Vertical spacing between read rows
  private static final double READ_GAP = 2.5;

  /**
   * Draw one SampleFile's reads in the given track strip, choosing colors based on its overlay flag.
   */
  private void drawSampleFile(SampleFile sf, String chrom, int start, int end,
                               double sampleY, double sampleH, double canvasHeight) {
    // Show loading indicator if this file is currently fetching for this stack
    boolean isHoverStack = (drawStack == org.baseplayer.controllers.MainController.hoverStack);
    boolean shouldShowLoading = sf.isLoading(drawStack) && (isHoverStack || !DrawFunctions.navigating);
    
    if (shouldShowLoading) {
      drawLoadingIndicator(sampleY, sampleH);
    }

    // Pass drawStack so SampleFile caches per-stack; allow non-hover stacks to fetch during navigation
    List<BAMRecord> reads = sf.getReads(chrom, start, end, drawStack, isHoverStack);
    if (reads.isEmpty()) return;
    
    int maxRow = sf.getMaxRow(drawStack) + 1;
    // Use minimum height per read - if coverage is very high, reads will go offscreen
    // and be culled by the bounds check in drawReadList
    double readHeight = Math.max(MIN_READ_HEIGHT, Math.min(8, (sampleH - 2) / Math.max(1, maxRow)));
    
    if (sf.overlay) {
      drawReadList(reads, sampleY, readHeight, READ_GAP, canvasHeight, OVERLAY_FORWARD, OVERLAY_REVERSE, OVERLAY_FORWARD_STROKE, OVERLAY_REVERSE_STROKE);
    } else {
      drawReadList(reads, sampleY, readHeight, READ_GAP, canvasHeight, READ_FORWARD, READ_REVERSE, READ_FORWARD_STROKE, READ_REVERSE_STROKE);
    }
  }

  /** Animated loading dots indicator */
  private long loadingFrame = 0;

  private void drawLoadingIndicator(double sampleY, double sampleH) {
    loadingFrame++;
    int dots = (int)(loadingFrame % 4) + 1;
    String text = "Loading" + ".".repeat(dots);
    gc.setFill(Color.web("#aaaaaa"));
    gc.setFont(javafx.scene.text.Font.font("Segoe UI", 11));
    gc.fillText(text, 10, sampleY + sampleH / 2 + 4);
  }

  /**
   * Draw a list of BAM reads with the given colors.
   */
  private void drawReadList(List<BAMRecord> reads, double sampleY, double readHeight, double gap,
                            double canvasHeight, Color fwdFill, Color revFill, Color fwdStroke, Color revStroke) {
    for (BAMRecord read : reads) {
      double x1 = chromPosToScreenPos.apply((double) read.pos);
      double x2 = chromPosToScreenPos.apply((double) read.end);
      double width = Math.max(1, x2 - x1);
      double y = sampleY + read.row * (readHeight + gap) + 1;

      if (y + readHeight < 0 || y > canvasHeight) continue;
      if (x1 + width < 0 || x1 > getWidth()) continue;

      if (read.isReverseStrand()) {
        gc.setFill(revFill);
        gc.setStroke(revStroke);
      } else {
        gc.setFill(fwdFill);
        gc.setStroke(fwdStroke);
      }

      if (readHeight >= 3) {
        gc.fillRect(x1, y, width, readHeight - gap);
        gc.strokeRect(x1, y, width, readHeight - gap);
      } else {
        gc.fillRect(x1, y, width, readHeight);
      }
    }
  }

}

package org.baseplayer.draw;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.SharedModel;
import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.AnnotationLoader;
import org.baseplayer.annotation.CosmicGenes;
import org.baseplayer.annotation.Gene;
import org.baseplayer.controllers.MainController;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.BaseColors;
import org.baseplayer.utils.GeneColors;
import org.baseplayer.utils.StackingAlgorithm;

import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

public class DrawChromData extends DrawFunctions {
  private final GraphicsContext gc;
  
  private static final int BASE_DISPLAY_THRESHOLD = 100000;
  private static final int REFERENCE_BUFFER = 50000;
  
  // Reference base cache
  private String cachedBases = "";
  private int cachedStart = 0;
  private int cachedEnd = 0;
  private String cachedChromosome = "";
  
  // Gene drawing constants
  private static final double GENE_AREA_TOP = 8;
  private static final double GENE_HEIGHT = 12;
  private static final double GENE_ROW_HEIGHT = 22;
  private static final double GENE_LABEL_HEIGHT = 10;
  private static final int MAX_GENE_ROWS = 8;  // Increased to show more genes
  private static final double GENE_CHAR_WIDTH = 6.0;  // Smaller font width
  private static final double GENE_PADDING = 15.0;  // Padding between genes in pixels
  private static final int GENE_FONT_SIZE = 10;  // Smaller gene name font
  
  // Gene stacking algorithm
  private final StackingAlgorithm<Gene> geneStacker;
  
  // Gene display settings
  private boolean showManeOnly = true;  // Show only MANE transcripts by default
  
  // For click detection and hover
  private final GeneInfoPopup genePopup = new GeneInfoPopup();
  private List<GeneHitBox> visibleGeneHitBoxes = new ArrayList<>();
  private Gene hoveredGene = null;
  
  private record GeneHitBox(Gene gene, double x1, double y1, double x2, double y2) {
    boolean contains(double x, double y) {
      return x >= x1 && x <= x2 && y >= y1 && y <= y2;
    }
  }

  public DrawChromData(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack) {
    super(reactiveCanvas, parent, drawStack);
    reactiveCanvas.setOnMouseEntered(event -> { MainController.hoverStack = drawStack; resizing = true; update.set(!update.get()); resizing = false; });
  
    gc = getGraphicsContext2D();
    gc.setFont(AppFonts.getUIFont());
    
    // Initialize gene stacker with label-aware visual extension
    geneStacker = StackingAlgorithm.createWithVisual(
      Gene::start,
      Gene::end,
      (gene, viewStart, viewLength, canvasWidth) -> {
        double geneX1 = ((gene.start() - viewStart) / viewLength) * canvasWidth;
        double geneX2 = ((gene.end() - viewStart) / viewLength) * canvasWidth;
        boolean isCancerGene = CosmicGenes.isCosmicGene(gene.name());
        boolean showLabel = isCancerGene || viewLength < 10_000_000 || "protein_coding".equals(gene.biotype());
        double labelWidth = showLabel ? gene.name().length() * GENE_CHAR_WIDTH : 0;
        return Math.max(geneX2, Math.max(geneX1, 0) + labelWidth) + GENE_PADDING;
      },
      MAX_GENE_ROWS,
      0.0  // No minimum width - show cancer genes even if tiny
    );
    
    if (!AnnotationData.isGenesLoaded() && !AnnotationData.isGenesLoading()) {
      AnnotationLoader.loadGenesBackground();
    }
    
    // Double-click to zoom in, single click to show gene info
    reactiveCanvas.setOnMouseClicked(event -> {
      if (event.getClickCount() == 2) {
        genePopup.hide();
        double mouseX = event.getX();
        double genomicPos = drawStack.start + (mouseX / getWidth()) * drawStack.viewLength;
        
        double newViewLength = drawStack.viewLength * 0.1;
        double newStart = genomicPos - newViewLength / 2;
        double newEnd = genomicPos + newViewLength / 2;
        
        drawStack.drawCanvas.zoomAnimation(newStart, newEnd);
      } else if (event.getClickCount() == 1) {
        // Show popup only if a gene is currently hovered
        if (hoveredGene != null) {
          double screenX = event.getScreenX();
          double screenY = event.getScreenY();
          genePopup.show(hoveredGene, drawStack, getScene().getWindow(), screenX + 10, screenY + 10);
        } else {
          genePopup.hide();
        }
      }
    });
    
    // Mouse move for hover effect
    reactiveCanvas.setOnMouseMoved(event -> {
      Gene geneAtMouse = findGeneAt(event.getX(), event.getY());
      if (geneAtMouse != hoveredGene) {
        hoveredGene = geneAtMouse;
        reactiveCanvas.setCursor(hoveredGene != null ? Cursor.HAND : Cursor.DEFAULT);
        drawReactive();
      }
    });
    
    // Clear hover when mouse exits
    reactiveCanvas.setOnMouseExited(event -> {
      if (hoveredGene != null) {
        hoveredGene = null;
        reactiveCanvas.setCursor(Cursor.DEFAULT);
        drawReactive();
      }
    });
  }
  
  private Gene findGeneAt(double x, double y) {
    for (GeneHitBox hitBox : visibleGeneHitBoxes) {
      if (hitBox.contains(x, y)) {
        return hitBox.gene();
      }
    }
    return null;
  }
  
  public void setShowManeOnly(boolean maneOnly) {
    this.showManeOnly = maneOnly;
    draw();
  }
  
  public boolean isShowManeOnly() {
    return showManeOnly;
  }
  
  /**
   * Draw hover highlight on the reactive canvas
   */
  private void drawReactive() {
    // Clear the reactive canvas
    reactivegc.clearRect(0, 0, getWidth(), getHeight());
    
    if (hoveredGene == null) return;
    
    double viewStart = drawStack.start;
    double viewLength = drawStack.viewLength;
    double canvasWidth = getWidth();
    
    // Find the hit box for the hovered gene to get its row position
    for (GeneHitBox hitBox : visibleGeneHitBoxes) {
      if (hitBox.gene() == hoveredGene) {
        List<long[]> exonsToShow = hoveredGene.getDisplayExons(showManeOnly);
        
        // Calculate body line based on visible exons (not full gene)
        long bodyStart = hoveredGene.start();
        long bodyEnd = hoveredGene.end();
        if (!exonsToShow.isEmpty()) {
          bodyStart = exonsToShow.get(0)[0];
          bodyEnd = exonsToShow.get(exonsToShow.size() - 1)[1];
        }
        
        double x1 = ((bodyStart - viewStart) / viewLength) * canvasWidth;
        double x2 = ((bodyEnd - viewStart) / viewLength) * canvasWidth;
        double clippedX1 = Math.max(0, x1);
        double clippedX2 = Math.min(canvasWidth, x2);
        
        double rowY = hitBox.y1();
        double bodyY = Math.round(rowY + GENE_LABEL_HEIGHT + GENE_HEIGHT / 2) + 0.5;
        
        // Draw white highlight for gene body from first to last visible exon (only if wider than 3px)
        if (clippedX2 - clippedX1 > 3) {
          reactivegc.setStroke(Color.WHITE);
          reactivegc.setLineWidth(2);
          reactivegc.strokeLine(Math.round(clippedX1) + 1, bodyY, Math.round(clippedX2) - 1, bodyY);
          reactivegc.setLineWidth(1);
        }
        
        // Draw white exons with rounded coordinates
        reactivegc.setFill(Color.WHITE);
        boolean showExonNumbers = viewLength < 100000;  // Show exon numbers when zoomed in closer than 100kbp
        boolean isReverse = "-".equals(hoveredGene.strand());
        int exonIndex = 0;
        for (long[] exon : exonsToShow) {
          exonIndex++;
          // For reverse strand, count from the end
          int exonNumber = isReverse ? (exonsToShow.size() - exonIndex + 1) : exonIndex;
          double ex1 = ((exon[0] - viewStart) / viewLength) * canvasWidth;
          double ex2 = ((exon[1] - viewStart) / viewLength) * canvasWidth;
          ex1 = Math.max(0, ex1);
          ex2 = Math.min(canvasWidth, ex2);
          if (ex2 >= ex1) {  // Changed to >= to include 1px exons
            double exonX = Math.round(ex1);
            double exonWidth = Math.round(ex2) - exonX;
            // Ensure exon is at least 1 pixel wide if visible
            if (exonWidth < 1) {
              exonWidth = 1;
            }
            double exonY = Math.round(rowY + GENE_LABEL_HEIGHT);
            reactivegc.fillRect(exonX, exonY, exonWidth, GENE_HEIGHT);
            
            // Draw exon number on top of the exon when zoomed in
            if (showExonNumbers && exonWidth > 10) {  // Only show if exon is wide enough
              reactivegc.setFont(AppFonts.getUIFont(9));
              reactivegc.setFill(Color.rgb(220, 220, 220, 0.9));  // Smoky white
              String exonLabel = String.valueOf(exonNumber);
              double textWidth = exonLabel.length() * 5;  // Approximate text width
              double textX = exonX + (exonWidth - textWidth) / 2;
              reactivegc.fillText(exonLabel, textX, exonY - 2);
              reactivegc.setFill(Color.WHITE);
            }
          }
        }
        
        // Draw white label
        double labelX = Math.max(2, x1);
        reactivegc.setFont(AppFonts.getUIFont(GENE_FONT_SIZE));
        reactivegc.setFill(Color.WHITE);
        reactivegc.fillText(hoveredGene.name(), labelX, rowY + GENE_LABEL_HEIGHT - 2);
        
        break;
      }
    }
  }

  @Override
  public void draw() {
    gc.setFill(backgroundColor);
    gc.fillRect(0, 0, getWidth(), getHeight());
    
    // Clear hover state on redraw
    hoveredGene = null;
    reactivegc.clearRect(0, 0, getWidth(), getHeight());
   
    drawGenes();
    drawIndicators();
		drawReferenceBases();
    super.draw();
  }

  void drawGenes() {
    String currentChrom = drawStack.chromosome;
    
		geneLoadingIndicator();
    
    if (!AnnotationData.isGenesLoaded() || currentChrom == null) return;
    
    List<Gene> genes = AnnotationData.getGenesByChrom().get(currentChrom);
    if (genes == null) return;
    
    double viewStart = drawStack.start;
    double viewEnd = drawStack.end;
    double viewLength = drawStack.viewLength;
    double canvasWidth = getWidth();
    
    // Filter genes: always include cancer genes, others must be >= 1 pixel wide
    List<Gene> filteredGenes = new ArrayList<>();
    for (Gene gene : genes) {
      // Skip genes outside viewport
      if (gene.end() < viewStart || gene.start() > viewEnd) continue;
      
      boolean isCancerGene = CosmicGenes.isCosmicGene(gene.name());
      
      // If showing only cancer genes, filter aggressively
      if (org.baseplayer.controllers.MainController.showOnlyCancerGenes) {
        if (isCancerGene) {
          filteredGenes.add(gene);
        }
      } else {
        // Normal mode: show cancer genes always, others if >= 1 pixel
        if (isCancerGene) {
          filteredGenes.add(gene);
        } else {
          double pixelWidth = ((gene.end() - gene.start()) / viewLength) * canvasWidth;
          if (pixelWidth >= 1.0) {
            filteredGenes.add(gene);
          }
        }
      }
    }
    
    StackingAlgorithm.StackResult<Gene> stacked = geneStacker.stack(filteredGenes, viewStart, viewEnd, canvasWidth);
    
    // Clear and rebuild hit boxes
    visibleGeneHitBoxes.clear();
    
    gc.save();
    gc.setFont(AppFonts.getUIFont(GENE_FONT_SIZE));
    gc.setTextAlign(TextAlignment.LEFT);
    
    // Use stacker's row assignments directly to ensure no overlaps
    for (int row = 0; row < stacked.getRowCount(); row++) {
      for (Gene gene : stacked.getRow(row)) {
        double rowY = GENE_AREA_TOP + row * (GENE_ROW_HEIGHT + GENE_LABEL_HEIGHT);
        drawGene(gene, rowY, viewStart, viewLength, canvasWidth);
      }
    }
    
    gc.restore();
  }
  
  private void drawGene(Gene gene, double rowY, double viewStart, double viewLength, double canvasWidth) {
    Color geneColor = GeneColors.getGeneColor(gene.name(), gene.biotype());
    
    // Get exons to display (MANE if available and enabled)
    List<long[]> exonsToShow = gene.getDisplayExons(showManeOnly);
    
    // Calculate body line based on visible exons (not full gene)
    long bodyStart = gene.start();
    long bodyEnd = gene.end();
    if (!exonsToShow.isEmpty()) {
      bodyStart = exonsToShow.get(0)[0];
      bodyEnd = exonsToShow.get(exonsToShow.size() - 1)[1];
    }
    
    double x1 = ((bodyStart - viewStart) / viewLength) * canvasWidth;
    double x2 = ((bodyEnd - viewStart) / viewLength) * canvasWidth;
    
    double clippedX1 = Math.max(0, x1);
    double clippedX2 = Math.min(canvasWidth, x2);
    if (clippedX2 <= clippedX1) return;
    
    // Draw gene body line from first to last visible exon (only if wider than 3px)
    if (clippedX2 - clippedX1 > 3) {
      gc.setStroke(geneColor);
      gc.setLineWidth(1);
      double bodyY = Math.round(rowY + GENE_LABEL_HEIGHT + GENE_HEIGHT / 2) + 0.5;
      gc.strokeLine(Math.round(clippedX1) + 1, bodyY, Math.round(clippedX2) - 1, bodyY);
    }
    
    // Draw exons with 3D gradient and rounded outer edges
    boolean showExonNumbers = viewLength < 100000;  // Show exon numbers when zoomed in closer than 100kbp
    boolean isReverse = "-".equals(gene.strand());
    for (int i = 0; i < exonsToShow.size(); i++) {
      long[] exon = exonsToShow.get(i);
      // For reverse strand, count from the end
      int exonNumber = isReverse ? (exonsToShow.size() - i) : (i + 1);
      double ex1 = ((exon[0] - viewStart) / viewLength) * canvasWidth;
      double ex2 = ((exon[1] - viewStart) / viewLength) * canvasWidth;
      ex1 = Math.max(0, ex1);
      ex2 = Math.min(canvasWidth, ex2);
      
      if (ex2 >= ex1) {  // Changed to >= to include 1px exons
        double exonX = Math.round(ex1);
        double exonY = Math.round(rowY + GENE_LABEL_HEIGHT);
        double exonWidth = Math.round(ex2) - exonX;
        
        // Ensure exon is at least 1 pixel wide if visible
        if (exonWidth < 1) {
          exonWidth = 1;
        }
        
        // Apply 3D gradient effect
        Color lighter = geneColor.interpolate(Color.WHITE, 0.3);
        Color darker = geneColor.interpolate(Color.BLACK, 0.2);
        javafx.scene.paint.LinearGradient gradient = new javafx.scene.paint.LinearGradient(
          0, exonY, 0, exonY + GENE_HEIGHT, false, javafx.scene.paint.CycleMethod.NO_CYCLE,
          new javafx.scene.paint.Stop(0, lighter),
          new javafx.scene.paint.Stop(0.3, geneColor),
          new javafx.scene.paint.Stop(0.7, geneColor),
          new javafx.scene.paint.Stop(1, darker)
        );
        gc.setFill(gradient);
        
        // Draw rectangle (rounding causes messy rendering, so using sharp edges)
        gc.fillRect(exonX, exonY, exonWidth, GENE_HEIGHT);
        
        // Draw exon number on top of the exon when zoomed in
        if (showExonNumbers && exonWidth > 10) {  // Only show if exon is wide enough
          gc.setFont(AppFonts.getUIFont(9));
          gc.setFill(Color.rgb(220, 220, 220, 0.9));  // Smoky white
          String exonLabel = String.valueOf(exonNumber);
          double textWidth = exonLabel.length() * 5;  // Approximate text width
          double textX = exonX + (exonWidth - textWidth) / 2;
          gc.fillText(exonLabel, textX, exonY - 2);
          gc.setFont(AppFonts.getUIFont());  // Restore font
        }
      }
    }
    
    // Draw label
    boolean isCancerGene = CosmicGenes.isCosmicGene(gene.name());
    boolean showLabel = isCancerGene || viewLength < 10_000_000 || "protein_coding".equals(gene.biotype());
    double labelX = Math.max(2, x1);
    double labelWidth = 0;
    if (showLabel) {
      gc.setFill(geneColor);
      gc.fillText(gene.name(), labelX, rowY + GENE_LABEL_HEIGHT - 2);
      labelWidth = gene.name().length() * GENE_CHAR_WIDTH;
    }
    
    // Add hit box for click detection (includes label area)
    double hitX1 = Math.max(0, Math.min(labelX, clippedX1));
    double hitX2 = Math.max(clippedX2, labelX + labelWidth);
    visibleGeneHitBoxes.add(new GeneHitBox(gene, hitX1, rowY, hitX2, rowY + GENE_LABEL_HEIGHT + GENE_HEIGHT));
  }
  
  void drawReferenceBases() {
    if (drawStack.viewLength > BASE_DISPLAY_THRESHOLD) return;
    if (SharedModel.referenceGenome == null) return;
    
    // Don't fetch reference sequence during cytoband dragging
    if (DrawCytoband.isDragging) return;
    
    int viewStart = (int) drawStack.start;
    int viewEnd = (int)drawStack.end;
    String currentChrom = drawStack.chromosome;
    
    boolean needsFetch = cachedBases.isEmpty() 
        || !currentChrom.equals(cachedChromosome)
        || viewStart < cachedStart 
        || viewEnd > cachedEnd;
    
    if (needsFetch) {
      int fetchStart = viewStart - REFERENCE_BUFFER;
      int fetchEnd = viewEnd + REFERENCE_BUFFER;
      
      cachedBases = SharedModel.referenceGenome.getBases(currentChrom, fetchStart, fetchEnd);
      cachedStart = fetchStart;
      cachedEnd = fetchEnd;
      cachedChromosome = currentChrom;
    }
    
    if (cachedBases.isEmpty()) return;
    
    double baseHeight = 8;
    double yPos = getHeight() - baseHeight - 2;
    
    boolean drawLetters = drawStack.viewLength < 200;
    
    if (drawLetters) {
      gc.setFont(AppFonts.getMonoFont(Math.min(12, drawStack.pixelSize * 0.8)));
      gc.setTextAlign(TextAlignment.CENTER);
    }
    
    int step = Math.max(1, (int) Math.ceil(1.0 / drawStack.pixelSize));
    double lastDrawnX = -1;
    
    for (int chromPos = viewStart; chromPos <= viewEnd; chromPos += step) {
      int cacheIndex = chromPos - cachedStart;
      if (cacheIndex < 0 || cacheIndex >= cachedBases.length()) continue;
      
      char base = cachedBases.charAt(cacheIndex);
      double xPos = chromPosToScreenPos.apply((double) chromPos);
      
      if (!drawLetters && Math.floor(xPos) == Math.floor(lastDrawnX)) continue;
      lastDrawnX = xPos;
      
      Color baseColor = BaseColors.getBaseColor(base);
      gc.setFill(baseColor);
      
      if (drawLetters) {
        gc.fillText(String.valueOf(base), xPos + drawStack.pixelSize / 2, yPos + baseHeight - 3);
      } else {
        gc.fillRect(Math.floor(xPos), yPos, 1, baseHeight);
      }
    }
    
    gc.setTextAlign(TextAlignment.LEFT);
  }

  void geneLoadingIndicator() {
		if (AnnotationData.isGenesLoaded()) return;
		gc.save();
		gc.setFill(Color.LIGHTGRAY);
		gc.setFont(AppFonts.getUIFont());
		gc.setTextAlign(TextAlignment.LEFT);
		gc.fillText("Loading genes...", 10, GENE_AREA_TOP + 12);
		gc.restore();
	}

  void drawIndicators() {
    DrawIndicators.draw(gc, drawStack, getWidth(), getHeight());
  }
}

package org.baseplayer.draw;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.baseplayer.SharedModel;
import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.AnnotationLoader;
import org.baseplayer.annotation.CosmicGenes;
import org.baseplayer.annotation.Gene;
import org.baseplayer.annotation.Transcript;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.BaseColors;
import org.baseplayer.utils.GeneColors;
import org.baseplayer.utils.StackingAlgorithm;

import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;

public class DrawChromData extends DrawFunctions {
  private final GraphicsContext gc;
  
  private static final int BASE_DISPLAY_THRESHOLD = 100000;
  private static final int REFERENCE_BUFFER = 50000;
  
  // Reference base cache
  private String cachedBases = "";
  private int cachedStart = 0;
  private int cachedEnd = 0;
  private String cachedChromosome = "";
  
  // Async loading state
  private final AtomicBoolean isLoadingBases = new AtomicBoolean(false);
  private volatile int pendingFetchStart = -1;
  private volatile int pendingFetchEnd = -1;
  private volatile String pendingFetchChrom = "";
  
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
  private final AminoAcidPopup aminoAcidPopup = new AminoAcidPopup();
  private List<GeneHitBox> visibleGeneHitBoxes = new ArrayList<>();
  private List<AminoAcidHitBox> visibleAminoAcidHitBoxes = new ArrayList<>();
  private Gene hoveredGene = null;
  private AminoAcidHitBox hoveredAminoAcid = null;
  
  private record GeneHitBox(Gene gene, double x1, double y1, double x2, double y2) {
    boolean contains(double x, double y) {
      return x >= x1 && x <= x2 && y >= y1 && y <= y2;
    }
  }
  
  /** Hit box for amino acid click detection */
  private record AminoAcidHitBox(
      char aminoAcid, String codon, int aminoAcidNumber, 
      long genomicStart, long cdsPosition, String geneName, boolean isReverse,
      double x1, double y1, double x2, double y2
  ) {
    boolean contains(double x, double y) {
      return x >= x1 && x <= x2 && y >= y1 && y <= y2;
    }
  }

  public DrawChromData(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack) {
    super(reactiveCanvas, parent, drawStack);
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
    
    // Double-click to zoom in, single click to show gene/amino acid info
    reactiveCanvas.setOnMouseClicked(event -> {
      if (event.getClickCount() == 2) {
        genePopup.hide();
        aminoAcidPopup.hide();
        double mouseX = event.getX();
        double genomicPos = drawStack.start + (mouseX / getWidth()) * drawStack.viewLength;
        
        double newViewLength = drawStack.viewLength * 0.1;
        double newStart = genomicPos - newViewLength / 2;
        double newEnd = genomicPos + newViewLength / 2;
        
        drawStack.drawCanvas.zoomAnimation(newStart, newEnd);
      } else if (event.getClickCount() == 1) {
        double mouseX = event.getX();
        double mouseY = event.getY();
        double screenX = event.getScreenX();
        double screenY = event.getScreenY();
        
        // Get window owner for popups
        Window owner = null;
        if (getScene() != null) {
          owner = getScene().getWindow();
        } else if (reactiveCanvas.getScene() != null) {
          owner = reactiveCanvas.getScene().getWindow();
        }
        
        if (owner == null) {
          System.err.println("Warning: Cannot show popup - no window available");
          return;
        }
        
        // Find what's at the click position directly (don't rely on hover state)
        AminoAcidHitBox aaHit = findAminoAcidAt(mouseX, mouseY);
        Gene geneHit = findGeneAt(mouseX, mouseY);
        
        if (aaHit != null) {
          genePopup.hide();
          aminoAcidPopup.setData(aaHit.aminoAcid(), aaHit.codon(), aaHit.aminoAcidNumber(),
                                  aaHit.genomicStart(), aaHit.cdsPosition(), aaHit.geneName(), aaHit.isReverse());
          aminoAcidPopup.show(owner, screenX + 10, screenY + 10);
        } else if (geneHit != null) {
          // Show gene popup if no amino acid hit
          aminoAcidPopup.hide();
          genePopup.show(geneHit, drawStack, owner, screenX + 10, screenY + 10);
        } else {
          genePopup.hide();
          aminoAcidPopup.hide();
        }
      }
    });
    
    // Mouse move for hover effect
    reactiveCanvas.setOnMouseMoved(event -> {
      double mouseX = event.getX();
      double mouseY = event.getY();

      // Check for amino acid hover first (when zoomed close)
      AminoAcidHitBox aaAtMouse = findAminoAcidAt(mouseX, mouseY);
      Gene geneAtMouse = findGeneAt(mouseX, mouseY);

      // When zoomed to amino-acid level, suppress gene-level hover entirely
      double viewLength = drawStack.viewLength;
      if (viewLength < 500) {
        geneAtMouse = null;
      }

      boolean needsRedraw = false;

      if (aaAtMouse != hoveredAminoAcid) {
        hoveredAminoAcid = aaAtMouse;
        needsRedraw = true;
      }

      if (geneAtMouse != hoveredGene) {
        hoveredGene = geneAtMouse;
        needsRedraw = true;
      }

      reactiveCanvas.setCursor((hoveredAminoAcid != null || hoveredGene != null) ? Cursor.HAND : Cursor.DEFAULT);

      if (needsRedraw) {
        drawReactive();
      }
    });
    
    // Clear hover when mouse exits
    reactiveCanvas.setOnMouseExited(event -> {
      if (hoveredGene != null || hoveredAminoAcid != null) {
        hoveredGene = null;
        hoveredAminoAcid = null;
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
  
  private AminoAcidHitBox findAminoAcidAt(double x, double y) {
    for (AminoAcidHitBox hitBox : visibleAminoAcidHitBoxes) {
      if (hitBox.contains(x, y)) {
        return hitBox;
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
    
    double viewLength = drawStack.viewLength;
    
    // If hovering an amino acid at close zoom, only highlight the codon
    if (hoveredAminoAcid != null && viewLength < 500) {
      drawAminoAcidHighlight();
      return;
    }
    
    if (hoveredGene == null) return;
    
    double viewStart = drawStack.start;
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
  
  /**
   * Draw highlight for hovered amino acid (codon) only
   */
  private void drawAminoAcidHighlight() {
    if (hoveredAminoAcid == null) return;
    
    double cx1 = hoveredAminoAcid.x1();
    double cx2 = hoveredAminoAcid.x2();
    double cy1 = hoveredAminoAcid.y1();
    double codonWidth = cx2 - cx1;
    double ovalHeight = GENE_HEIGHT - 2;
    
    // Draw white oval highlight around the codon
    reactivegc.setStroke(Color.WHITE);
    reactivegc.setLineWidth(2);
    reactivegc.strokeOval(cx1 - 1, cy1, codonWidth + 2, ovalHeight + 2);
    reactivegc.setLineWidth(1);
  }

  @Override
  public void draw() {
    gc.setFill(backgroundColor);
    gc.fillRect(0, 0, getWidth(), getHeight());
    
    // Clear hover state on redraw
    hoveredGene = null;
    hoveredAminoAcid = null;
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
    visibleAminoAcidHitBoxes.clear();
    
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
    
    // Get CDS bounds from MANE transcript (if available)
    Transcript maneTranscript = gene.getManeSelectTranscript();
    if (maneTranscript == null && gene.transcripts() != null && !gene.transcripts().isEmpty()) {
      maneTranscript = gene.transcripts().get(0);  // Use first transcript if no MANE
    }
    long cdsStart = maneTranscript != null ? maneTranscript.cdsStart() : 0;
    long cdsEnd = maneTranscript != null ? maneTranscript.cdsEnd() : 0;
    boolean hasCDS = cdsStart > 0 && cdsEnd > 0;
    
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
    
    // Draw exons with 3D gradient, distinguishing UTR (gray) from CDS
    boolean showExonNumbers = viewLength < 100000;
    boolean showAminoAcids = viewLength < 500;  // Show amino acids when very zoomed in
    boolean isReverse = "-".equals(gene.strand());
    
    // Calculate CDS sizes for each exon to compute cumulative offsets
    // For forward strand: offset = CDS bases in preceding exons
    // For reverse strand: offset = CDS bases in following exons (since transcription is reversed)
    // Note: coordinates are 1-based inclusive, so size = end - start + 1
    long[] cdsExonSizes = new long[exonsToShow.size()];
    for (int i = 0; i < exonsToShow.size(); i++) {
      long[] exon = exonsToShow.get(i);
      if (hasCDS && exon[1] >= cdsStart && exon[0] <= cdsEnd) {
        long regionStart = Math.max(exon[0], cdsStart);
        long regionEnd = Math.min(exon[1], cdsEnd);
        cdsExonSizes[i] = regionEnd - regionStart + 1;  // +1 for inclusive coordinates
      }
    }
    
    // Calculate cumulative CDS offset for each exon
    long[] cdsOffsets = new long[exonsToShow.size()];
    if (isReverse) {
      // For reverse strand, transcription goes from high coords to low
      // So cumulative offset comes from exons with HIGHER indices (genomically after)
      long cumulative = 0;
      for (int i = exonsToShow.size() - 1; i >= 0; i--) {
        cdsOffsets[i] = cumulative;
        cumulative += cdsExonSizes[i];
      }
    } else {
      // For forward strand, cumulative offset comes from preceding exons
      long cumulative = 0;
      for (int i = 0; i < exonsToShow.size(); i++) {
        cdsOffsets[i] = cumulative;
        cumulative += cdsExonSizes[i];
      }
    }
    
    for (int i = 0; i < exonsToShow.size(); i++) {
      long[] exon = exonsToShow.get(i);
      int exonNumber = isReverse ? (exonsToShow.size() - i) : (i + 1);
      
      // Draw UTR and CDS portions separately
      if (hasCDS && (exon[0] < cdsStart || exon[1] > cdsEnd)) {
        // This exon has UTR region(s)
        
        // 5' UTR (before CDS start) - draw up to cdsStart-1 (inclusive)
        if (exon[0] < cdsStart) {
          long utrEnd = Math.min(exon[1], cdsStart - 1);
          if (utrEnd >= exon[0]) {
            drawExonRegion(exon[0], utrEnd, viewStart, viewLength, canvasWidth, rowY, 
                           GeneColors.UTR_COLOR, false, null, isReverse, 0);
          }
        }
        
        // CDS portion
        if (exon[1] >= cdsStart && exon[0] <= cdsEnd) {
          long regionStart = Math.max(exon[0], cdsStart);
          long regionEnd = Math.min(exon[1], cdsEnd);
          drawExonRegion(regionStart, regionEnd, viewStart, viewLength, canvasWidth, rowY, 
                         geneColor, showAminoAcids, gene, isReverse, cdsOffsets[i]);
        }
        
        // 3' UTR (after CDS end) - start at cdsEnd+1 (inclusive)
        if (exon[1] > cdsEnd) {
          long utrStart = Math.max(exon[0], cdsEnd + 1);
          if (utrStart <= exon[1]) {
            drawExonRegion(utrStart, exon[1], viewStart, viewLength, canvasWidth, rowY, 
                           GeneColors.UTR_COLOR, false, null, isReverse, 0);
          }
        }
      } else if (hasCDS) {
        // Entire exon is CDS
        drawExonRegion(exon[0], exon[1], viewStart, viewLength, canvasWidth, rowY, 
                       geneColor, showAminoAcids, gene, isReverse, cdsOffsets[i]);
      } else {
        // Non-coding gene - draw in gene color
        drawExonRegion(exon[0], exon[1], viewStart, viewLength, canvasWidth, rowY, 
                       geneColor, false, null, isReverse, 0);
      }
      
      // Draw exon number on top
      if (showExonNumbers) {
        double ex1 = ((exon[0] - viewStart) / viewLength) * canvasWidth;
        double ex2 = ((exon[1] - viewStart) / viewLength) * canvasWidth;
        double exonWidth = Math.max(0, Math.min(canvasWidth, ex2) - Math.max(0, ex1));
        double exonX = Math.max(0, ex1);
        double exonY = Math.round(rowY + GENE_LABEL_HEIGHT);
        
        if (exonWidth > 10) {
          gc.setFont(AppFonts.getUIFont(9));
          gc.setFill(Color.rgb(220, 220, 220, 0.9));
          String exonLabel = String.valueOf(exonNumber);
          double textWidth = exonLabel.length() * 5;
          double textX = exonX + (exonWidth - textWidth) / 2;
          gc.fillText(exonLabel, textX, exonY - 2);
          gc.setFont(AppFonts.getUIFont());
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
  
  private void drawExonRegion(long regionStart, long regionEnd, double viewStart, double viewLength, 
                               double canvasWidth, double rowY, Color color, 
                               boolean showAminoAcids, Gene gene, boolean isReverse, long cdsOffset) {
    double ex1 = ((regionStart - viewStart) / viewLength) * canvasWidth;
    double ex2 = ((regionEnd - viewStart) / viewLength) * canvasWidth;
    ex1 = Math.max(0, ex1);
    ex2 = Math.min(canvasWidth, ex2);
    
    if (ex2 < ex1) return;
    
    double exonX = Math.round(ex1);
    double exonY = Math.round(rowY + GENE_LABEL_HEIGHT);
    double exonWidth = Math.round(ex2) - exonX;
    if (exonWidth < 1) exonWidth = 1;
    
    // Draw amino acids if zoomed in close enough and this is a CDS region
    // When showing amino acids, skip the exon rectangle
    if (showAminoAcids && gene != null && SharedModel.referenceGenome != null) {
      drawAminoAcidsInRegion(regionStart, regionEnd, viewStart, viewLength, canvasWidth, 
                              rowY, gene, isReverse, cdsOffset);
    } else {
      // Apply 3D gradient effect for normal exon display
      Color lighter = color.interpolate(Color.WHITE, 0.3);
      Color darker = color.interpolate(Color.BLACK, 0.2);
      javafx.scene.paint.LinearGradient gradient = new javafx.scene.paint.LinearGradient(
        0, exonY, 0, exonY + GENE_HEIGHT, false, javafx.scene.paint.CycleMethod.NO_CYCLE,
        new javafx.scene.paint.Stop(0, lighter),
        new javafx.scene.paint.Stop(0.3, color),
        new javafx.scene.paint.Stop(0.7, color),
        new javafx.scene.paint.Stop(1, darker)
      );
      gc.setFill(gradient);
      gc.fillRect(exonX, exonY, exonWidth, GENE_HEIGHT);
    }
  }
  
  private void drawAminoAcidsInRegion(long regionStart, long regionEnd, double viewStart, 
                                       double viewLength, double canvasWidth, double rowY,
                                       Gene gene, boolean isReverse, long cdsOffset) {
    // Get reference sequence from cache (don't do sync fetch here to avoid blocking)
    // The cache should be populated by drawReferenceBases() which runs first
    // Note: regionEnd is INCLUSIVE (same as getBases convention)
    String bases;
    if (!cachedBases.isEmpty() && regionStart >= cachedStart && regionEnd <= cachedEnd 
        && drawStack.chromosome.equals(cachedChromosome)) {
      // Extract bases from cache
      // cachedBases uses inclusive end, substring needs exclusive end
      int startIndex = (int) (regionStart - cachedStart);
      int endIndex = (int) (regionEnd - cachedStart + 1);  // +1 because regionEnd is inclusive
      if (startIndex >= 0 && endIndex <= cachedBases.length()) {
        bases = cachedBases.substring(startIndex, endIndex);
      } else {
        return; // Cache doesn't cover this region
      }
    } else {
      return; // Cache not ready, will redraw when async load completes
    }
    
    if (bases.isEmpty()) return;
    
    // For reverse strand, reverse complement to get mRNA sequence (5'→3')
    if (isReverse) {
      bases = reverseComplement(bases);
    }
    
    double exonY = Math.round(rowY + GENE_LABEL_HEIGHT);
    gc.setTextAlign(TextAlignment.CENTER);
    
    // Calculate reading frame offset
    // cdsOffset = total CDS bases in all preceding exons (in transcription order)
    // If cdsOffset % 3 != 0, we're continuing a partial codon from the previous exon
    // Skip the remaining bases of that partial codon to find first complete codon
    int frameOffset = (int) ((3 - (cdsOffset % 3)) % 3);
    
    // Draw codon by codon (3 bases = 1 amino acid)
    for (int i = frameOffset; i + 2 < bases.length(); i += 3) {
      String codon = bases.substring(i, i + 3);
      char aminoAcid = translateCodon(codon);
      String threeLetterCode = GeneColors.getAminoAcidThreeLetter(aminoAcid);
      
      // Calculate genomic codon positions
      // For forward strand: codon at bases[i..i+2] is at genomic regionStart+i to regionStart+i+2
      // For reverse strand: after reverse complement, bases[i] came from genomic regionEnd-i
      //   So codon spans genomic regionEnd-i-2 to regionEnd-i (inclusive)
      long codonStart, codonEnd;
      if (isReverse) {
        // codonStart = lowest genomic coord, codonEnd = exclusive (one past highest)
        codonStart = regionEnd - i - 2;
        codonEnd = regionEnd - i + 1;
      } else {
        codonStart = regionStart + i;
        codonEnd = regionStart + i + 3;
      }
      
      // Calculate cDNA position (1-based) for this codon
      long cdsPosition = cdsOffset + i + 1; // +1 for 1-based
      // Amino acid number (1-based)
      int aminoAcidNumber = (int) ((cdsPosition - 1) / 3) + 1;
      
      double cx1 = ((codonStart - viewStart) / viewLength) * canvasWidth;
      double cx2 = ((codonEnd - viewStart) / viewLength) * canvasWidth;
      cx1 = Math.max(0, cx1);
      cx2 = Math.min(canvasWidth, cx2);
      
      if (cx2 > cx1) {
        double codonWidth = cx2 - cx1;
        double codonX = cx1 + codonWidth / 2;
        double ovalHeight = GENE_HEIGHT - 2;
        
        // Draw amino acid as oval with its color
        Color aaColor = GeneColors.getAminoAcidColor(aminoAcid);
        gc.setFill(aaColor);
        gc.fillOval(cx1, exonY + 1, codonWidth, ovalHeight);
        
        // Add hit box for click detection
        visibleAminoAcidHitBoxes.add(new AminoAcidHitBox(
            aminoAcid, codon, aminoAcidNumber, codonStart, cdsPosition, 
            gene.name(), isReverse, cx1, exonY, cx2, exonY + GENE_HEIGHT
        ));
        
        // Draw three-letter amino acid code with contrasting text
        if (codonWidth > 15) {  // Only show text if wide enough
          gc.setFont(AppFonts.getMonoFont(Math.min(9, GENE_HEIGHT * 0.6)));
          gc.setFill(GeneColors.getContrastingTextColor(aaColor));
          gc.fillText(threeLetterCode, codonX, exonY + GENE_HEIGHT - 3);
        }
      }
    }
    
    gc.setTextAlign(TextAlignment.LEFT);
  }
  
  private String reverseComplement(String sequence) {
    StringBuilder sb = new StringBuilder(sequence.length());
    for (int i = sequence.length() - 1; i >= 0; i--) {
      char base = sequence.charAt(i);
      sb.append(switch (Character.toUpperCase(base)) {
        case 'A' -> 'T';
        case 'T' -> 'A';
        case 'G' -> 'C';
        case 'C' -> 'G';
        default -> 'N';
      });
    }
    return sb.toString();
  }
  
  private char translateCodon(String codon) {
    if (codon == null || codon.length() != 3) return '?';
    codon = codon.toUpperCase();
    return switch (codon) {
      case "TTT", "TTC" -> 'F';  // Phenylalanine
      case "TTA", "TTG", "CTT", "CTC", "CTA", "CTG" -> 'L';  // Leucine
      case "ATT", "ATC", "ATA" -> 'I';  // Isoleucine
      case "ATG" -> 'M';  // Methionine (start)
      case "GTT", "GTC", "GTA", "GTG" -> 'V';  // Valine
      case "TCT", "TCC", "TCA", "TCG", "AGT", "AGC" -> 'S';  // Serine
      case "CCT", "CCC", "CCA", "CCG" -> 'P';  // Proline
      case "ACT", "ACC", "ACA", "ACG" -> 'T';  // Threonine
      case "GCT", "GCC", "GCA", "GCG" -> 'A';  // Alanine
      case "TAT", "TAC" -> 'Y';  // Tyrosine
      case "TAA", "TAG", "TGA" -> '*';  // Stop
      case "CAT", "CAC" -> 'H';  // Histidine
      case "CAA", "CAG" -> 'Q';  // Glutamine
      case "AAT", "AAC" -> 'N';  // Asparagine
      case "AAA", "AAG" -> 'K';  // Lysine
      case "GAT", "GAC" -> 'D';  // Aspartic acid
      case "GAA", "GAG" -> 'E';  // Glutamic acid
      case "TGT", "TGC" -> 'C';  // Cysteine
      case "TGG" -> 'W';  // Tryptophan
      case "CGT", "CGC", "CGA", "CGG", "AGA", "AGG" -> 'R';  // Arginine
      case "GGT", "GGC", "GGA", "GGG" -> 'G';  // Glycine
      default -> 'X';  // Unknown
    };
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
      // Clamp to valid chromosome coordinates (1-based, inclusive)
      int fetchStart = Math.max(1, viewStart - REFERENCE_BUFFER);
      int fetchEnd = viewEnd + REFERENCE_BUFFER;
      
      // Check if we're already loading this exact range
      boolean alreadyLoading = isLoadingBases.get() 
          && fetchStart == pendingFetchStart 
          && fetchEnd == pendingFetchEnd 
          && currentChrom.equals(pendingFetchChrom);
      
      if (!alreadyLoading && isLoadingBases.compareAndSet(false, true)) {
        // Start async fetch
        pendingFetchStart = fetchStart;
        pendingFetchEnd = fetchEnd;
        pendingFetchChrom = currentChrom;
        
        CompletableFuture.runAsync(() -> {
          String bases = SharedModel.referenceGenome.getBases(currentChrom, fetchStart, fetchEnd);
          Platform.runLater(() -> {
            // Only update cache if this is still the latest request
            if (fetchStart == pendingFetchStart && fetchEnd == pendingFetchEnd 
                && currentChrom.equals(pendingFetchChrom)) {
              cachedBases = bases;
              cachedStart = fetchStart;
              cachedEnd = fetchEnd;
              cachedChromosome = currentChrom;
            }
            isLoadingBases.set(false);
            // Trigger redraw
            resizing = true; 
            update.set(!update.get()); 
            resizing = false;
          });
        });
      }
      
      // Use whatever we have in cache while loading (may be empty or stale but won't block)
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

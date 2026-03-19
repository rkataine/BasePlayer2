package org.baseplayer.genome.gene.draw;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.AnnotationLoader;
import org.baseplayer.annotation.CosmicGenes;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.genome.draw.PositionIndicator;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.DrawColors;
import org.baseplayer.utils.StackingAlgorithm;

import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;

public class ChromosomeCanvas extends GenomicCanvas {
  private final GraphicsContext gc;
  private final ReferenceGenomeService referenceGenomeService;

  // ── Drawing helpers ──────────────────────────────────────────────────────────
  private final DrawExon drawExon;
  private final DrawGene drawGene;

  // ── Gene stacking algorithm ──────────────────────────────────────────────────
  private final StackingAlgorithm<Gene> geneStacker;

  // ── Gene display settings ────────────────────────────────────────────────────
  private boolean showManeOnly = true;

  // ── Hover / click state ──────────────────────────────────────────────────────
  private final GeneInfoPopup genePopup = new GeneInfoPopup();
  private final AminoAcidPopup aminoAcidPopup = new AminoAcidPopup();
  private Gene hoveredGene = null;
  private DrawExon.AminoAcidHitBox hoveredAminoAcid = null;

  public ChromosomeCanvas(Canvas reactiveCanvas, StackPane parent, DrawStack drawStack) {
    super(reactiveCanvas, parent, drawStack);
    gc = getGraphicsContext2D();
    gc.setFont(AppFonts.getUIFont());
    this.referenceGenomeService = ServiceRegistry.getInstance().getReferenceGenomeService();
    this.drawExon = new DrawExon(gc, drawStack, referenceGenomeService);
    this.drawGene = new DrawGene(gc, drawExon);

    // Unbind height from parent — we manage it dynamically based on gene rows
    heightProperty().unbind();
    reactiveCanvas.heightProperty().unbind();

    // Initialize gene stacker with label-aware visual extension
    geneStacker = StackingAlgorithm.createWithVisual(
      Gene::start,
      Gene::end,
      (gene, viewStart, viewLength, canvasWidth) -> {
        double geneX1 = ((gene.start() - viewStart) / viewLength) * canvasWidth;
        double geneX2 = ((gene.end() - viewStart) / viewLength) * canvasWidth;
        boolean isCancerGene = CosmicGenes.isCosmicGene(gene.name());
        boolean showLabel = isCancerGene || viewLength < 10_000_000 || "protein_coding".equals(gene.biotype());
        double labelWidth = showLabel ? gene.name().length() * DrawGene.GENE_CHAR_WIDTH : 0;
        return Math.max(geneX2, Math.max(geneX1, 0) + labelWidth) + DrawGene.GENE_PADDING;
      },
      DrawGene.MAX_GENE_ROWS,
      0.0  // No minimum width - show cancer genes even if tiny
    );
    
    if (!AnnotationData.isGenesLoaded() && !AnnotationData.isGenesLoading()) {
      AnnotationLoader.loadGenesBackground();
    }

    // Double-click to zoom in, single click to show gene/amino acid info
    reactiveCanvas.setOnMouseClicked(event -> {
      // Skip if this click is the tail-end of a drag gesture anywhere (local or global)
      if (isDragging()) return;

      if (event.getClickCount() == 2) {
        genePopup.hide();
        aminoAcidPopup.hide();
        double mouseX = event.getX();
        double genomicPos = drawStack.start + (mouseX / getWidth()) * drawStack.viewLength;
        
        double newViewLength = drawStack.viewLength * 0.1;
        double newStart = genomicPos - newViewLength / 2;
        double newEnd = genomicPos + newViewLength / 2;
        
        drawStack.alignmentCanvas.zoomAnimation(newStart, newEnd);
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
        DrawExon.AminoAcidHitBox aaHit = findAminoAcidAt(mouseX, mouseY);
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
      // Suppress all hover while any drag (local or global) is active
      if (isDragging()) {
        clearHover();
        return;
      }

      double mouseX = event.getX();
      double mouseY = event.getY();

      // Check for amino acid hover first (when zoomed close)
      DrawExon.AminoAcidHitBox aaAtMouse = findAminoAcidAt(mouseX, mouseY);
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
    reactiveCanvas.setOnMouseExited(event -> clearHover());
  }
  
  /** Clears hover state and resets the cursor, triggering a redraw if needed. */
  private void clearHover() {
    if (hoveredGene != null || hoveredAminoAcid != null) {
      hoveredGene = null;
      hoveredAminoAcid = null;
      getReactiveCanvas().setCursor(javafx.scene.Cursor.DEFAULT);
      drawReactive();
    }
  }

  @Override
  protected void onDragActive() {
    clearHover();
  }

  private Gene findGeneAt(double x, double y) {
    for (DrawGene.GeneHitBox hitBox : drawGene.hitBoxes) {
      if (hitBox.contains(x, y)) {
        return hitBox.gene();
      }
    }
    return null;
  }
  
  private DrawExon.AminoAcidHitBox findAminoAcidAt(double x, double y) {
    for (DrawExon.AminoAcidHitBox hitBox : drawExon.hitBoxes) {
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
    reactiveGc.clearRect(0, 0, getWidth(), getHeight());
    
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
    for (DrawGene.GeneHitBox hitBox : drawGene.hitBoxes) {
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
        double bodyY = Math.round(rowY + DrawExon.GENE_LABEL_HEIGHT + DrawExon.GENE_HEIGHT / 2) + 0.5;
        
        // Draw white highlight for gene body from first to last visible exon (only if wider than 3px)
        if (clippedX2 - clippedX1 > 3) {
          reactiveGc.setStroke(Color.WHITE);
          reactiveGc.setLineWidth(2);
          reactiveGc.strokeLine(Math.round(clippedX1) + 1, bodyY, Math.round(clippedX2) - 1, bodyY);
          reactiveGc.setLineWidth(1);
        }
        
        // Draw white exons with rounded coordinates
        reactiveGc.setFill(Color.WHITE);
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
            double exonY = Math.round(rowY + DrawExon.GENE_LABEL_HEIGHT);
            reactiveGc.fillRect(exonX, exonY, exonWidth, DrawExon.GENE_HEIGHT);
            
            // Draw exon number on top of the exon when zoomed in
            if (showExonNumbers && exonWidth > 10) {  // Only show if exon is wide enough
              reactiveGc.setFont(AppFonts.getUIFont(9));
              reactiveGc.setFill(Color.rgb(220, 220, 220, 0.9));  // Smoky white
              String exonLabel = String.valueOf(exonNumber);
              double textWidth = exonLabel.length() * 5;  // Approximate text width
              double textX = exonX + (exonWidth - textWidth) / 2;
              reactiveGc.fillText(exonLabel, textX, exonY - 2);
              reactiveGc.setFill(Color.WHITE);
            }
          }
        }
        
        // Draw white label
        double labelX = Math.max(2, x1);
        reactiveGc.setFont(AppFonts.getUIFont(DrawGene.GENE_FONT_SIZE));
        reactiveGc.setFill(Color.WHITE);
        reactiveGc.fillText(hoveredGene.name(), labelX, rowY + DrawExon.GENE_LABEL_HEIGHT - 2);
        
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
    double ovalHeight = DrawExon.GENE_HEIGHT - 2;
    
    // Draw white oval highlight around the codon
    reactiveGc.setStroke(Color.WHITE);
    reactiveGc.setLineWidth(2);
    reactiveGc.strokeOval(cx1 - 1, cy1, codonWidth + 2, ovalHeight + 2);
    reactiveGc.setLineWidth(1);
  }

  @Override
  public void draw() {
    gc.setFill(DrawColors.BACKGROUND);
    gc.fillRect(0, 0, getWidth(), getHeight());
    
    // Clear hover state on redraw — but preserve reactive canvas during line zoom drag
    hoveredGene = null;
    hoveredAminoAcid = null;
    if (!isDragging()) {
      reactiveGc.clearRect(0, 0, getWidth(), getHeight());
    }
   
    drawGenes();
    drawIndicators();
		drawExon.drawReferenceBases(getWidth(), getHeight());
    super.draw();
  }

  @Override
  protected void handleScroll(ScrollEvent event) {
    if (event.isControlDown()) {
      // Ctrl+scroll = zoom (consume to prevent ScrollPane from scrolling)
      event.consume();
      zoom(event.getDeltaY(), event.getX());
    } else if (event.getDeltaX() != 0) {
      // Horizontal scroll = pan genomic position (consume)
      event.consume();
      double scrollMultiplier = 0.3;
      double genomeDelta = event.getDeltaX() * scrollMultiplier * drawStack.scale;
      setStart(drawStack.start - genomeDelta);
    }
    // Vertical scroll without Ctrl: do NOT consume — let ScrollPane handle it
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
        // Cancer genes always visible regardless of pixel width
        if (isCancerGene) {
          filteredGenes.add(gene);
        } else {
          // For other genes, gene body must be at least 1 pixel wide
          double genePixelWidth = ((gene.end() - gene.start()) / viewLength) * canvasWidth;
          if (genePixelWidth >= 1.0) {
            filteredGenes.add(gene);
          }
        }
      }
    }
    
    // Stacking uses normal start-position order; visual extender already
    // accounts for max(label width, gene width) per gene
    StackingAlgorithm.StackResult<Gene> stacked = geneStacker.stack(filteredGenes, viewStart, viewEnd, canvasWidth);
    
    // Count actual rows used and resize canvas to fit all gene rows
    int usedRows = 0;
    for (int row = 0; row < stacked.getRowCount(); row++) {
      if (!stacked.getRow(row).isEmpty()) usedRows = row + 1;
    }
    double contentHeight = DrawGene.GENE_AREA_TOP + usedRows * (DrawGene.GENE_ROW_HEIGHT + DrawExon.GENE_LABEL_HEIGHT) + 10;
    double minHeight = drawStack.chromScrollPane != null 
        ? drawStack.chromScrollPane.getHeight() : 100;
    double canvasHeight = Math.max(contentHeight, minHeight);
    // Only update height when it actually changed — avoids layout oscillation
    // from setHeight → parent resize → update → draw → setHeight loop.
    if (Math.abs(getHeight() - canvasHeight) > 1.0) {
      setHeight(canvasHeight);
      getReactiveCanvas().setHeight(canvasHeight);
    }
    
    // Clear and rebuild hit boxes
    drawGene.clearHitBoxes();
    drawExon.clearHitBoxes();
    
    gc.save();
    gc.setFont(AppFonts.getUIFont(DrawGene.GENE_FONT_SIZE));
    gc.setTextAlign(TextAlignment.LEFT);
    
    // Use stacker's row assignments directly to ensure no overlaps
    for (int row = 0; row < stacked.getRowCount(); row++) {
      for (Gene gene : stacked.getRow(row)) {
        double rowY = DrawGene.GENE_AREA_TOP + row * (DrawGene.GENE_ROW_HEIGHT + DrawExon.GENE_LABEL_HEIGHT);
        drawGene.drawGene(gene, rowY, viewStart, viewLength, canvasWidth, showManeOnly);
      }
    }
    
    gc.restore();
  }
  

  void geneLoadingIndicator() {
		if (AnnotationData.isGenesLoaded()) return;
		gc.save();
		gc.setFill(Color.LIGHTGRAY);
		gc.setFont(AppFonts.getUIFont());
		gc.setTextAlign(TextAlignment.LEFT);
		gc.fillText("Loading genes...", 10, DrawGene.GENE_AREA_TOP + 12);
		gc.restore();
	}

  void drawIndicators() {
    // Draw indicators at the bottom of the visible viewport, not the full canvas
    double visibleHeight = getHeight();
    if (drawStack.chromScrollPane != null) {
      double viewportHeight = drawStack.chromScrollPane.getViewportBounds().getHeight();
      double scrollOffset = drawStack.chromScrollPane.getVvalue() * (getHeight() - viewportHeight);
      visibleHeight = scrollOffset + viewportHeight;
    }
    PositionIndicator.draw(gc, drawStack, getWidth(), visibleHeight);
  }
}

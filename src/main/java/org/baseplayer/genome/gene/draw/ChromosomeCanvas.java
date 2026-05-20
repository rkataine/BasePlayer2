package org.baseplayer.genome.gene.draw;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.AnnotationLoader;
import org.baseplayer.annotation.CosmicGenes;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.genome.draw.PositionIndicator;
import org.baseplayer.genome.gene.Gene;
import org.baseplayer.genome.gene.Transcript;
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
  private final Set<String> expandedGeneIds = new HashSet<>();

  // ── Hover / click state ──────────────────────────────────────────────────────
  private final GeneInfoPopup genePopup = new GeneInfoPopup();
  private final AminoAcidPopup aminoAcidPopup = new AminoAcidPopup();
  private Gene hoveredGene = null;
  private DrawExon.AminoAcidHitBox hoveredAminoAcid = null;
  private String selectedGeneId = null;
  private String selectedTranscriptId = null;

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

    genePopup.setOnHidden(this::clearSelectedGeneHighlight);

    // Double-click to zoom in, single click to show gene/amino acid info
    reactiveCanvas.setOnMouseClicked(event -> {
      // Skip if this click is the tail-end of a drag gesture anywhere (local or global)
      if (isDragging()) return;

      if (event.getClickCount() == 2) {
        genePopup.hide();
        clearSelectedGeneHighlight();
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
        DrawGene.GeneHitBox geneHitBox = findGeneHitAt(mouseX, mouseY);
        Gene geneHit = geneHitBox != null ? geneHitBox.gene() : null;
        String transcriptId = geneHitBox != null ? geneHitBox.transcriptId() : null;
        
        if (aaHit != null) {
          genePopup.hide();
          clearSelectedGeneHighlight();
          aminoAcidPopup.setData(aaHit.aminoAcid(), aaHit.codon(), aaHit.aminoAcidNumber(),
                                  aaHit.genomicStart(), aaHit.cdsPosition(), aaHit.geneName(), aaHit.isReverse());
          aminoAcidPopup.show(owner, screenX + 10, screenY + 10);
        } else if (geneHit != null) {
          // Show gene popup if no amino acid hit
          aminoAcidPopup.hide();
          setSelectedGeneHighlight(geneHit.id(), transcriptId);
          genePopup.show(geneHit, drawStack, owner, screenX + 10, screenY + 10, transcriptId);
          drawReactive();
        } else {
          genePopup.hide();
          clearSelectedGeneHighlight();
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

  private DrawGene.GeneHitBox findGeneHitAt(double x, double y) {
    for (DrawGene.GeneHitBox hitBox : drawGene.hitBoxes) {
      if (hitBox.contains(x, y)) {
        return hitBox;
      }
    }
    return null;
  }

  private Gene findGeneAt(double x, double y) {
    DrawGene.GeneHitBox hitBox = findGeneHitAt(x, y);
    return hitBox != null ? hitBox.gene() : null;
  }

  private void setSelectedGeneHighlight(String geneId, String transcriptId) {
    selectedGeneId = geneId;
    selectedTranscriptId = transcriptId;
  }

  private void clearSelectedGeneHighlight() {
    selectedGeneId = null;
    selectedTranscriptId = null;
    if (reactiveGc != null) {
      reactiveGc.clearRect(0, 0, getWidth(), getHeight());
    }
  }

  private DrawGene.GeneHitBox findSelectedGeneHitBox() {
    if (selectedGeneId == null || !genePopup.isShowing()) return null;
    for (DrawGene.GeneHitBox hitBox : drawGene.hitBoxes) {
      if (!selectedGeneId.equals(hitBox.gene().id())) continue;
      if (selectedTranscriptId == null || selectedTranscriptId.equals(hitBox.transcriptId())) {
        return hitBox;
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

  public void setGeneExpanded(String geneId, boolean expanded) {
    if (geneId == null || geneId.isBlank()) return;
    if (expanded) {
      expandedGeneIds.add(geneId);
    } else {
      expandedGeneIds.remove(geneId);
    }
    draw();
  }

  public boolean isGeneExpanded(String geneId) {
    return geneId != null && expandedGeneIds.contains(geneId);
  }

  private boolean useManeOnlyForGene() {
    return showManeOnly;
  }

  private List<Transcript> getGeneTranscriptsForView(Gene gene) {
    if (gene == null) return List.of();

    List<Transcript> transcripts = new ArrayList<>();
    if (gene.transcripts() != null) {
      transcripts.addAll(gene.transcripts());
    }
    transcripts.addAll(AnnotationData.getNonManeTranscripts(gene.id()));

    java.util.LinkedHashMap<String, Transcript> uniqueById = new java.util.LinkedHashMap<>();
    for (Transcript tx : transcripts) {
      if (tx == null) continue;
      String key = tx.id() != null ? tx.id() : (tx.name() != null ? tx.name() : String.valueOf(uniqueById.size()));
      uniqueById.putIfAbsent(key, tx);
    }

    List<Transcript> sorted = new ArrayList<>(uniqueById.values());
    sorted.sort((a, b) -> {
      if (a.isManeSelect() != b.isManeSelect()) return a.isManeSelect() ? -1 : 1;
      if (a.isManeClinic() != b.isManeClinic()) return a.isManeClinic() ? -1 : 1;
      String an = a.name() != null ? a.name() : "";
      String bn = b.name() != null ? b.name() : "";
      return an.compareTo(bn);
    });
    return sorted;
  }

  private Gene transcriptProxyGene(Gene sourceGene, Transcript transcript) {
    List<Transcript> singleTranscript = List.of(transcript);
    List<long[]> transcriptExons = transcript.exons() != null ? transcript.exons() : sourceGene.exons();
    return new Gene(
        sourceGene.chrom(),
        sourceGene.start(),
        sourceGene.end(),
        sourceGene.name(),
        sourceGene.id(),
        sourceGene.strand(),
        sourceGene.biotype(),
        sourceGene.description(),
        singleTranscript,
        transcriptExons);
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

    DrawGene.GeneHitBox selectedHit = findSelectedGeneHitBox();
    if (selectedHit != null) {
      drawGeneHitHighlight(selectedHit, Color.rgb(255, 220, 150, 0.95));
    }

    double viewLength = drawStack.viewLength;
    if (hoveredAminoAcid != null && viewLength < 500) {
      drawAminoAcidHighlight();
      return;
    }

    if (hoveredGene == null) return;
    for (DrawGene.GeneHitBox hitBox : drawGene.hitBoxes) {
      if (hitBox.gene() == hoveredGene) {
        drawGeneHitHighlight(hitBox, Color.WHITE);
        break;
      }
    }
  }

  private void drawGeneHitHighlight(DrawGene.GeneHitBox hitBox, Color color) {
    Gene gene = hitBox.gene();
    if (gene == null) return;

    List<long[]> exonsToShow = gene.getDisplayExons(useManeOnlyForGene());
    double viewStart = drawStack.start;
    double viewLength = drawStack.viewLength;
    double canvasWidth = getWidth();

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

    double rowY = hitBox.y1();
    double bodyY = Math.round(rowY + DrawExon.GENE_LABEL_HEIGHT + DrawExon.GENE_HEIGHT / 2) + 0.5;
    if (clippedX2 - clippedX1 > 3) {
      reactiveGc.setStroke(color);
      reactiveGc.setLineWidth(2);
      reactiveGc.strokeLine(Math.round(clippedX1) + 1, bodyY, Math.round(clippedX2) - 1, bodyY);
      reactiveGc.setLineWidth(1);
    }

    reactiveGc.setFill(Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0.9));
    boolean showExonNumbers = viewLength < 100000;
    boolean isReverse = "-".equals(gene.strand());
    int exonIndex = 0;
    for (long[] exon : exonsToShow) {
      exonIndex++;
      int exonNumber = isReverse ? (exonsToShow.size() - exonIndex + 1) : exonIndex;
      double ex1 = ((exon[0] - viewStart) / viewLength) * canvasWidth;
      double ex2 = ((exon[1] - viewStart) / viewLength) * canvasWidth;
      ex1 = Math.max(0, ex1);
      ex2 = Math.min(canvasWidth, ex2);
      if (ex2 >= ex1) {
        double exonX = Math.round(ex1);
        double exonWidth = Math.max(1, Math.round(ex2) - exonX);
        double exonY = Math.round(rowY + DrawExon.GENE_LABEL_HEIGHT);
        reactiveGc.fillRect(exonX, exonY, exonWidth, DrawExon.GENE_HEIGHT);

        if (showExonNumbers && exonWidth > 10) {
          reactiveGc.setFont(AppFonts.getUIFont(9));
          reactiveGc.setFill(Color.rgb(220, 220, 220, 0.9));
          String exonLabel = String.valueOf(exonNumber);
          double textWidth = exonLabel.length() * 5;
          double textX = exonX + (exonWidth - textWidth) / 2;
          reactiveGc.fillText(exonLabel, textX, exonY - 2);
          reactiveGc.setFill(Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0.9));
        }
      }
    }

    double labelX = Math.max(2, x1);
    reactiveGc.setFont(AppFonts.getUIFont(DrawGene.GENE_FONT_SIZE));
    reactiveGc.setFill(color);
    reactiveGc.fillText(gene.name(), labelX, rowY + DrawExon.GENE_LABEL_HEIGHT - 2);
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
    if (selectedGeneId != null && genePopup.isShowing()) {
      drawReactive();
    }
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
    
    if (!AnnotationData.isGenesLoaded() || currentChrom == null) {
      ensureMinimumHeight();
      return;
    }
    
    List<Gene> genes = AnnotationData.getGenesByChrom().get(currentChrom);
    if (genes == null) {
      ensureMinimumHeight();
      return;
    }
    
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
    
    List<Gene> genesForStacking = new ArrayList<>();
    Map<Gene, RenderSpec> renderSpecs = new IdentityHashMap<>();
    for (Gene gene : filteredGenes) {
      boolean expanded = showManeOnly && isGeneExpanded(gene.id());
      if (expanded) {
        List<Transcript> transcripts = getGeneTranscriptsForView(gene);
        if (!transcripts.isEmpty()) {
          for (int i = 0; i < transcripts.size(); i++) {
            Gene transcriptGene = transcriptProxyGene(gene, transcripts.get(i));
            genesForStacking.add(transcriptGene);
            renderSpecs.put(transcriptGene, new RenderSpec(i == 0, true));
          }
          continue;
        }
      }

      genesForStacking.add(gene);
      renderSpecs.put(gene, new RenderSpec(true, showManeOnly));
    }

    // Stacking uses normal start-position order; visual extender already
    // accounts for max(label width, gene width) per gene
    StackingAlgorithm.StackResult<Gene> stacked = geneStacker.stack(genesForStacking, viewStart, viewEnd, canvasWidth);

    // Count actual rows used and resize canvas to fit all visible stacked rows.
    int usedRows = 0;
    for (int row = 0; row < stacked.getRowCount(); row++) {
      if (!stacked.getRow(row).isEmpty()) usedRows = row + 1;
    }
    double contentHeight = DrawGene.GENE_AREA_TOP
        + usedRows * (DrawGene.GENE_ROW_HEIGHT + DrawExon.GENE_LABEL_HEIGHT)
        + 10;
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
      double rowY = DrawGene.GENE_AREA_TOP + row * (DrawGene.GENE_ROW_HEIGHT + DrawExon.GENE_LABEL_HEIGHT);
      for (Gene gene : stacked.getRow(row)) {
        RenderSpec spec = renderSpecs.get(gene);
        boolean drawLabel = spec == null || spec.drawLabel();
        boolean maneOnly = spec == null ? useManeOnlyForGene() : spec.maneOnly();
        drawGene.drawGene(gene, rowY, viewStart, viewLength, canvasWidth, maneOnly, drawLabel);
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

  private void ensureMinimumHeight() {
    double minHeight = drawStack.chromScrollPane != null
        ? drawStack.chromScrollPane.getHeight() : 100;
    if (minHeight > 0 && Math.abs(getHeight() - minHeight) > 1.0) {
      setHeight(minHeight);
      getReactiveCanvas().setHeight(minHeight);
    }
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

  private record RenderSpec(boolean drawLabel, boolean maneOnly) {}
}

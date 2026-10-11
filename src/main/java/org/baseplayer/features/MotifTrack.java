package org.baseplayer.features;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.baseplayer.components.InfoPopup;
import org.baseplayer.components.PopupContent;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.features.motif.MotifMatrix;
import org.baseplayer.features.motif.PwmScorer;
import org.baseplayer.features.motif.PwmScorer.Hit;
import org.baseplayer.features.motif.PwmScorer.PreparedMotif;
import org.baseplayer.features.motif.SequenceLogoPainter;
import org.baseplayer.genome.ReferenceGenomeService;
import org.baseplayer.services.LoadingManager;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.utils.AppFonts;
import org.baseplayer.utils.ChromosomeNames;
import org.baseplayer.utils.FeatureNameColors;
import org.baseplayer.utils.StackingAlgorithm;

import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;

/**
 * Feature track backed by JASPAR/PFM matrices. When zoomed in, scans the
 * reference with a simplified MOODS-style PWM scorer and draws sequence logos.
 */
public class MotifTrack extends AbstractTrack {

  public static final long MAX_SCAN_VIEW_BP = 5_000L;
  private static final double MIN_LOGO_PX_PER_BP = 6.0;
  /** Logo stack height (px); tall enough for readable relative-style letters. */
  private static final double LOGO_HEIGHT = 72;
  private static final double HIT_BAR_HEIGHT = 8;
  /** Space above logo/bar for the motif name (same idea as BedTrack / genes). */
  private static final double NAME_SLOT_H = 16;
  private static final double ROW_TOP_PAD = 4;
  private static final double MIN_LABEL_WIDTH_PX = 18;
  /**
   * Horizontal packing gap after each hit's visual extent (logo and/or name),
   * matching gene {@code GENE_PADDING}.
   */
  private static final double ITEM_PAD_PX = 15.0;
  /** Vertical padding between stacked lanes when name labels are shown. */
  private static final double LANE_GAP_WITH_LABELS = 4;
  /** Tight gap when packing bars/logos without a name row. */
  private static final double LANE_GAP_COMPACT = 2;
  private static final int MAX_STACK_ROWS = 40;
  /** Extra bases around the view so small pans/zooms reuse the last scan. */
  private static final long QUERY_PADDING_BP = 500L;

  /** Reused on the FX thread to measure name labels for packing. */
  private static final Text LABEL_PROBE = new Text();

  private final Path sourcePath;
  private final List<MotifMatrix> allMatrices;
  private final Map<String, MotifMatrix> byId;
  private volatile List<PreparedMotif> activeMotifs;
  private volatile Set<String> selectedIds;
  private volatile double pvalue = PwmScorer.DEFAULT_PVALUE;

  private volatile List<Hit> hitCache = List.of();
  private volatile String cacheChrom = "";
  private volatile long cacheStart = -1;
  private volatile long cacheEnd = -1;
  private volatile boolean loading;
  private volatile String statusMessage = "";
  private final AtomicInteger fetchGeneration = new AtomicInteger();
  private volatile ThreadRunner.RunnerTask activeScanTask;
  private Runnable onDataLoaded;
  private final InfoPopup featurePopup = new InfoPopup(320, 240, false);

  private record ScanResult(
      List<Hit> hits, String chrom, long padStart, long padEnd, String error) {}

  /** Last stacked layout for hit-testing (track-local Y, after scroll). */
  private List<StackedHit> lastStackedHits = List.of();
  private double stackScrollOffset;
  private double stackContentHeight;
  private double stackViewHeight;
  private boolean stackOverflow;

  private record StackedHit(Hit hit, double x1, double y1, double width, double height) {}

  public record MotifHit(
      Hit hit, double x1, double y1, double width, double height) {
  }

  private static final StackingAlgorithm<Hit> HIT_STACKER = StackingAlgorithm.createWithVisual(
      Hit::start1Based,
      // Exclusive end for packing (hits store inclusive end1Based).
      hit -> hit.end1Based() + 1L,
      MotifTrack::visualEndIncludingName,
      MAX_STACK_ROWS,
      0.0);

  /**
   * Screen-space right edge used for packing: logo/bar body, and name width only
   * when the hit is wide enough for a visible label.
   */
  private static double visualEndIncludingName(
      Hit hit, double viewStart, double viewLength, double canvasWidth) {
    double x1 = ((hit.start1Based() - viewStart) / viewLength) * canvasWidth;
    double x2 = ((hit.end1Based() + 1.0 - viewStart) / viewLength) * canvasWidth;
    double visibleX1 = Math.max(0, x1);
    double visibleX2 = Math.min(canvasWidth, x2);
    double visibleW = Math.max(0, visibleX2 - visibleX1);
    String name = hit.motifName();
    if (name != null && !name.isEmpty() && visibleW >= MIN_LABEL_WIDTH_PX) {
      double labelEnd = visibleX1 + 2 + measureNameWidth(name);
      return Math.max(x2, labelEnd) + ITEM_PAD_PX;
    }
    return x2 + ITEM_PAD_PX;
  }

  private static double measureNameWidth(String name) {
    if (name == null || name.isEmpty()) {
      return 0;
    }
    LABEL_PROBE.setText(name);
    LABEL_PROBE.setFont(AppFonts.getUIFont(BedTrack.FEATURE_NAME_FONT_SIZE));
    return Math.ceil(LABEL_PROBE.getLayoutBounds().getWidth());
  }

  public MotifTrack(Path sourcePath, List<MotifMatrix> matrices, Set<String> selectedIds) {
    super(sourcePath.getFileName().toString(), "JASPAR");
    this.sourcePath = sourcePath.toAbsolutePath().normalize();
    this.allMatrices = List.copyOf(matrices);
    this.byId = matrices.stream()
        .collect(Collectors.toMap(MotifMatrix::id, m -> m, (a, b) -> a, java.util.LinkedHashMap::new));
    this.preferredHeight = NAME_SLOT_H + LOGO_HEIGHT + ROW_TOP_PAD + 4;
    this.color = Color.rgb(120, 80, 160);
    setSelectedMotifs(selectedIds);
    // Bake logo glyphs off the track-canvas paint path (avoids NGCanvas corruption).
    SequenceLogoPainter.warmUp();
  }

  public List<MotifMatrix> getAllMatrices() {
    return allMatrices;
  }

  public Set<String> getSelectedMotifIds() {
    return selectedIds == null ? Set.of() : Set.copyOf(selectedIds);
  }

  public double getPvalue() {
    return pvalue;
  }

  public void setPvalue(double pvalue) {
    if (pvalue <= 0 || pvalue >= 1 || Double.isNaN(pvalue)) {
      return;
    }
    this.pvalue = pvalue;
    rebuildActiveMotifs();
    clearCache();
  }

  /**
   * Restrict scanning to motif ids. {@code null} or empty keeps previous
   * selection unchanged if already set; first construction requires a non-empty set.
   */
  public void setSelectedMotifs(Set<String> ids) {
    if (ids == null || ids.isEmpty()) {
      if (activeMotifs == null || activeMotifs.isEmpty()) {
        // Default: first motif only so open is never empty.
        MotifMatrix first = allMatrices.get(0);
        this.selectedIds = Set.of(first.id());
      }
    } else {
      LinkedHashSet<String> keep = new LinkedHashSet<>();
      for (String id : ids) {
        if (byId.containsKey(id)) {
          keep.add(id);
        }
      }
      if (keep.isEmpty()) {
        keep.add(allMatrices.get(0).id());
      }
      this.selectedIds = keep;
    }
    rebuildActiveMotifs();
    clearCache();
  }

  private void rebuildActiveMotifs() {
    List<MotifMatrix> chosen = new ArrayList<>();
    for (String id : selectedIds) {
      MotifMatrix m = byId.get(id);
      if (m != null) {
        chosen.add(m);
      }
    }
    this.activeMotifs = PwmScorer.prepareAll(chosen, pvalue);
  }

  public void setOnDataLoaded(Runnable onDataLoaded) {
    this.onDataLoaded = onDataLoaded;
  }

  @Override
  public Path getSourcePath() {
    return sourcePath;
  }

  @Override
  public boolean isLoading() {
    return loading;
  }

  @Override
  public void dispose() {
    featurePopup.hide();
    fetchGeneration.incrementAndGet();
    ThreadRunner.RunnerTask task = activeScanTask;
    activeScanTask = null;
    if (task != null) {
      task.cancel();
    }
    loading = false;
    hitCache = List.of();
  }

  @Override
  public void onRegionChanged(String chromosome, long start, long end, DrawStack drawStack) {
    if (!visible) {
      return;
    }
    if (drawStack != null && drawStack.nav != null && drawStack.nav.animationRunning) {
      return;
    }
    requestScan(chromosome, start, end);
  }

  private void clearCache() {
    fetchGeneration.incrementAndGet();
    ThreadRunner.RunnerTask task = activeScanTask;
    activeScanTask = null;
    if (task != null) {
      task.cancel();
    }
    loading = false;
    hitCache = List.of();
    cacheChrom = "";
    cacheStart = -1;
    cacheEnd = -1;
    statusMessage = "";
    resetStackScroll();
  }

  private void resetStackScroll() {
    stackScrollOffset = 0;
    stackContentHeight = 0;
    stackViewHeight = 0;
    stackOverflow = false;
  }

  /** {@code true} when stacked content is taller than the track row. */
  public boolean canScrollStack() {
    return stackOverflow;
  }

  /**
   * Scroll stacked motif lanes inside this track. {@code deltaY} follows JavaFX
   * scroll convention (positive = content moves down / view moves up).
   *
   * @return {@code true} if the offset changed
   */
  public boolean scrollStack(double deltaY) {
    if (!stackOverflow || deltaY == 0) {
      return false;
    }
    double max = Math.max(0, stackContentHeight - stackViewHeight);
    double next = Math.max(0, Math.min(max, stackScrollOffset - deltaY));
    if (Math.abs(next - stackScrollOffset) < 0.5) {
      return false;
    }
    stackScrollOffset = next;
    return true;
  }

  private boolean cacheCovers(String chrom, long start, long end) {
    return chrom.equals(cacheChrom) && start >= cacheStart && end <= cacheEnd;
  }

  private static boolean isNavigationAnimating() {
    try {
      DrawStack stack = ServiceRegistry.getInstance().getDrawStackManager().getFirst();
      return stack != null && stack.nav != null && stack.nav.animationRunning;
    } catch (Exception ignored) {
      return false;
    }
  }

  private void requestScan(String chromosome, long start, long end) {
    if (chromosome == null || end <= start) {
      return;
    }
    String bare = ChromosomeNames.strip(chromosome);
    long viewLen = end - start;
    if (viewLen > MAX_SCAN_VIEW_BP) {
      fetchGeneration.incrementAndGet();
      ThreadRunner.RunnerTask task = activeScanTask;
      activeScanTask = null;
      if (task != null) {
        task.cancel();
      }
      loading = false;
      boolean changed = !hitCache.isEmpty() || cacheStart >= 0;
      hitCache = List.of();
      cacheChrom = "";
      cacheStart = -1;
      cacheEnd = -1;
      statusMessage = "Zoom to ≤ " + (MAX_SCAN_VIEW_BP / 1000) + " kb to scan motifs";
      if (changed) {
        notifyLoaded();
      }
      return;
    }
    ReferenceGenomeService ref = ServiceRegistry.getInstance().getReferenceGenomeService();
    if (ref == null || !ref.hasGenome()) {
      statusMessage = "Load a reference genome to scan motifs";
      hitCache = List.of();
      loading = false;
      notifyLoaded();
      return;
    }
    if (cacheCovers(bare, start, end)) {
      return;
    }

    long padStart = Math.max(1, start - QUERY_PADDING_BP);
    long padEnd = end + QUERY_PADDING_BP;
    int generation = fetchGeneration.incrementAndGet();
    ThreadRunner.RunnerTask previous = activeScanTask;
    loading = true;
    statusMessage = "";
    final List<PreparedMotif> motifs = activeMotifs;
    final String trackLabel = getName();
    final ThreadRunner.RunnerTask[] taskRef = new ThreadRunner.RunnerTask[1];

    // Register the new task before cancelling the previous one so LoadingPopup
    // never sees an empty active-task list (which would hide the modal).
    taskRef[0] = ThreadRunner.get().submit(
        "Aligning motifs (" + trackLabel + ")…",
        () -> {
          if (generation != fetchGeneration.get()) {
            return null;
          }
          try {
            int fetchStart = (int) Math.min(Integer.MAX_VALUE, padStart);
            int fetchEnd = (int) Math.min(Integer.MAX_VALUE, padEnd);
            String bases = ref.getBases(bare, fetchStart, fetchEnd);
            if (generation != fetchGeneration.get()) {
              return null;
            }
            int motifCount = motifs == null ? 0 : motifs.size();
            if (motifCount > 0) {
              LoadingManager.get().setProgress(0, motifCount);
            }
            List<Hit> hits = PwmScorer.scan(bases, fetchStart, motifs, (cur, tot) -> {
              if (generation != fetchGeneration.get()) {
                return;
              }
              ThreadRunner.RunnerTask task = taskRef[0];
              if (task == null || task.isCancelled()) {
                return;
              }
              task.setProgressSuffix(cur + "/" + tot);
              ThreadRunner.get().notifyDescriptionChanged();
              LoadingManager.get().setProgress(cur, tot);
            });
            return new ScanResult(hits, bare, padStart, padEnd, null);
          } catch (Exception ex) {
            return new ScanResult(List.of(), bare, padStart, padEnd, ex.getMessage());
          }
        },
        result -> {
          if (generation != fetchGeneration.get()) {
            return;
          }
          activeScanTask = null;
          loading = false;
          if (result == null) {
            notifyLoaded();
            return;
          }
          if (result.error() != null) {
            hitCache = List.of();
            cacheChrom = "";
            cacheStart = -1;
            cacheEnd = -1;
            statusMessage = "Scan failed: " + result.error();
          } else {
            hitCache = result.hits();
            cacheChrom = result.chrom();
            cacheStart = result.padStart();
            cacheEnd = result.padEnd();
            statusMessage = result.hits().isEmpty() ? "No motif hits in view" : "";
          }
          notifyLoaded();
        });
    activeScanTask = taskRef[0];
    if (previous != null) {
      previous.cancel();
    }
  }

  private void notifyLoaded() {
    if (Platform.isFxApplicationThread()) {
      if (onDataLoaded != null) {
        onDataLoaded.run();
      } else {
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      }
      return;
    }
    Platform.runLater(() -> {
      if (onDataLoaded != null) {
        onDataLoaded.run();
      } else {
        GenomicCanvas.update.set(!GenomicCanvas.update.get());
      }
    });
  }

  @Override
  public void draw(
      GraphicsContext gc, double x, double y, double width, double height,
      String chromosome, double start, double end) {
    if (!visible || width <= 0 || height <= 0 || end <= start) {
      return;
    }
    String bare = ChromosomeNames.strip(chromosome);
    long viewStart = (long) start;
    long viewEnd = (long) Math.ceil(end);
    // Prefer onRegionChanged for scans; paint only starts one when idle and not
    // animating (restart-per-frame would flash/hide the loading modal).
    if (!cacheCovers(bare, viewStart, viewEnd)
        && (viewEnd - viewStart) <= MAX_SCAN_VIEW_BP
        && !loading
        && !isNavigationAnimating()) {
      requestScan(chromosome, viewStart, viewEnd);
    }

    // Keep painting the last scan while a new alignment runs (modal covers progress).
    List<Hit> hits = hitCache;
    if (hits.isEmpty()) {
      if (loading) {
        return;
      }
      if (!statusMessage.isEmpty()) {
        gc.setFill(Color.gray(0.55));
        gc.setFont(AppFonts.getUIFont(10));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(statusMessage, x + 6, y + Math.min(height - 4, 14));
      }
      return;
    }

    double viewLength = end - start;
    double pxPerBp = width / viewLength;
    boolean logos = pxPerBp >= MIN_LOGO_PX_PER_BP;
    // Always stack overlaps; reserve label row only when a name will paint on-screen.
    boolean showLabels = anyMotifLabelVisible(hits, x, width, start, end, viewLength);
    drawStackedHits(gc, x, y, width, height, start, end, viewLength, pxPerBp, logos, hits, showLabels);
    gc.setTextAlign(TextAlignment.LEFT);
  }

  /** Same visibility rule as stacked label painting (on-screen width). */
  private static boolean anyMotifLabelVisible(
      List<Hit> hits,
      double trackX,
      double trackWidth,
      double start,
      double end,
      double viewLength) {
    for (Hit hit : hits) {
      if (hit.end1Based() < start || hit.start1Based() > end) {
        continue;
      }
      if (hit.motifName() == null || hit.motifName().isEmpty()) {
        continue;
      }
      double x1 = trackX + ((hit.start1Based() - start) / viewLength) * trackWidth;
      double x2 = trackX + ((hit.end1Based() + 1 - start) / viewLength) * trackWidth;
      double visibleW = Math.min(trackX + trackWidth, x2) - Math.max(trackX, x1);
      if (visibleW >= MIN_LABEL_WIDTH_PX) {
        return true;
      }
    }
    return false;
  }

  private void drawStackedHits(
      GraphicsContext gc, double x, double y, double width, double height,
      double start, double end, double viewLength, double pxPerBp, boolean logos,
      List<Hit> hits, boolean showLabels) {
    double bodyH = logos ? LOGO_HEIGHT : HIT_BAR_HEIGHT;
    double laneGap = showLabels ? LANE_GAP_WITH_LABELS : LANE_GAP_COMPACT;

    StackingAlgorithm.StackResult<Hit> stacked =
        HIT_STACKER.stack(hits, start, end, width);
    int usedRows = 0;
    for (int row = 0; row < stacked.getRowCount(); row++) {
      if (!stacked.getRow(row).isEmpty()) {
        usedRows = row + 1;
      }
    }
    if (usedRows == 0) {
      lastStackedHits = List.of();
      resetStackScroll();
      return;
    }

    // Per-row name slot: unlabeled rows pack tightly (no empty label band).
    double[] rowNameSlot = new double[usedRows];
    double contentH = ROW_TOP_PAD + 2;
    for (int row = 0; row < usedRows; row++) {
      boolean rowHasLabel = showLabels
          && rowHasVisibleMotifLabel(stacked.getRow(row), x, width, start, viewLength);
      rowNameSlot[row] = rowHasLabel ? NAME_SLOT_H : 0;
      contentH += rowNameSlot[row] + bodyH + laneGap;
    }
    setPreferredHeight(contentH);
    stackContentHeight = contentH;
    stackViewHeight = height;
    stackOverflow = contentH > height + 0.5;
    double maxScroll = Math.max(0, contentH - height);
    stackScrollOffset = Math.max(0, Math.min(maxScroll, stackScrollOffset));

    Map<String, MotifMatrix> matrices = byId;
    List<StackedHit> stackedHits = new ArrayList<>();
    // No gc.clip/save — those opcodes on a live track canvas have corrupted
    // NGCanvas during project restore. Cull lanes by Y instead.
    double laneTop = y + ROW_TOP_PAD - stackScrollOffset;
    double maxNameSlot = 0;
    for (int row = 0; row < usedRows; row++) {
      double nameSlot = rowNameSlot[row];
      double featureY = laneTop + nameSlot;
      maxNameSlot = Math.max(maxNameSlot, nameSlot);
      if (featureY + bodyH >= y && laneTop <= y + height) {
        for (Hit hit : stacked.getRow(row)) {
          double x1 = x + ((hit.start1Based() - start) / viewLength) * width;
          double x2 = x + ((hit.end1Based() + 1 - start) / viewLength) * width;
          double visibleW = Math.min(x + width, x2) - Math.max(x, x1);
          double w = Math.max(1, x2 - x1);
          Color c = FeatureNameColors.colorForName(hit.motifName());
          paintHitBody(gc, matrices, hit, c, x1, featureY, w, bodyH, pxPerBp, logos);
          if (nameSlot > 0.5
              && visibleW >= MIN_LABEL_WIDTH_PX
              && hit.motifName() != null
              && !hit.motifName().isEmpty()) {
            BedTrack.drawFeatureNameLabel(gc, hit.motifName(), x1 + 2, featureY - 2);
          }
          stackedHits.add(new StackedHit(hit, x1 - x, featureY - y, w, bodyH));
        }
      }
      laneTop = featureY + bodyH + laneGap;
    }
    if (stackOverflow) {
      paintStackScrollHint(gc, x, y, width, height);
    }
    lastStackedHits = stackedHits;
  }

  private static boolean rowHasVisibleMotifLabel(
      List<Hit> row,
      double trackX,
      double trackWidth,
      double start,
      double viewLength) {
    for (Hit hit : row) {
      if (hit.motifName() == null || hit.motifName().isEmpty()) {
        continue;
      }
      double x1 = trackX + ((hit.start1Based() - start) / viewLength) * trackWidth;
      double x2 = trackX + ((hit.end1Based() + 1 - start) / viewLength) * trackWidth;
      double visibleW = Math.min(trackX + trackWidth, x2) - Math.max(trackX, x1);
      if (visibleW >= MIN_LABEL_WIDTH_PX) {
        return true;
      }
    }
    return false;
  }

  /** Thin right-edge thumb so overflow / scroll position is visible. */
  private void paintStackScrollHint(
      GraphicsContext gc, double x, double y, double width, double height) {
    double track = Math.max(12, height - 4);
    double thumbH = Math.max(8, track * (stackViewHeight / Math.max(1, stackContentHeight)));
    double maxTravel = Math.max(0, track - thumbH);
    double maxScroll = Math.max(1, stackContentHeight - stackViewHeight);
    double thumbY = y + 2 + maxTravel * (stackScrollOffset / maxScroll);
    double thumbX = x + width - 5;
    gc.setFill(Color.rgb(200, 200, 200, 0.35));
    gc.fillRoundRect(thumbX, y + 2, 3, track, 2, 2);
    gc.setFill(Color.rgb(230, 230, 230, 0.75));
    gc.fillRoundRect(thumbX, thumbY, 3, thumbH, 2, 2);
  }

  private static void paintHitBody(
      GraphicsContext gc,
      Map<String, MotifMatrix> matrices,
      Hit hit,
      Color c,
      double x1,
      double featureY,
      double w,
      double bodyH,
      double pxPerBp,
      boolean logos) {
    if (logos) {
      MotifMatrix matrix = matrices.get(hit.motifId());
      if (matrix != null
          && SequenceLogoPainter.draw(
              gc, matrix, x1, featureY, pxPerBp, bodyH, hit.strand() == '+')) {
        return;
      }
    }
    gc.setFill(logos ? c : c.deriveColor(0, 1, 1, 0.85));
    gc.fillRect(x1, featureY, w, bodyH);
  }

  public MotifHit hitTest(
      double clickX, double clickY, double trackWidth, double trackHeight,
      String chromosome, double viewStart, double viewEnd) {
    if (trackWidth <= 0 || viewEnd <= viewStart || lastStackedHits.isEmpty()) {
      return null;
    }
    for (int i = lastStackedHits.size() - 1; i >= 0; i--) {
      StackedHit sh = lastStackedHits.get(i);
      if (clickX >= sh.x1() && clickX <= sh.x1() + sh.width()
          && clickY >= sh.y1() - 3
          && clickY <= sh.y1() + sh.height() + 2) {
        return new MotifHit(sh.hit(), sh.x1(), sh.y1(), sh.width(), sh.height());
      }
    }
    return null;
  }

  @Override
  public boolean supportsClick() {
    return true;
  }

  @Override
  public boolean handleClick(
      double clickX, double clickY, double trackWidth, double trackHeight,
      String chromosome, double viewStart, double viewEnd,
      Window owner, double screenX, double screenY) {
    MotifHit hit = hitTest(clickX, clickY, trackWidth, trackHeight, chromosome, viewStart, viewEnd);
    if (hit == null) {
      return false;
    }
    Hit h = hit.hit();
    Color color = FeatureNameColors.colorForName(h.motifName());
    PopupContent content = new PopupContent()
        .title(h.motifName(), color)
        .separator()
        .row("Motif ID", h.motifId())
        .row("Track", getName())
        .row("Locus", String.format("%s:%,d–%,d", chromosome, h.start1Based(), h.end1Based()))
        .row("Strand", String.valueOf(h.strand()))
        .row("Score", String.format("%.3f", h.score()))
        .row("p-value ≤", String.format("%.1e", pvalue));
    featurePopup.show(content, owner, screenX + 12, screenY + 8);
    return true;
  }

  @Override
  public void hidePopup() {
    featurePopup.hide();
  }
}

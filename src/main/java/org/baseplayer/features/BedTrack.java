package org.baseplayer.features;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.io.readers.BedFileReader;
import org.baseplayer.io.readers.BedFileReader.BedFeature;
import org.baseplayer.utils.AppFonts;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

public class BedTrack extends AbstractTrack {

  private static final double FEATURE_BAR_HEIGHT = 10;

  private final Path sourcePath;
  private final Map<String, List<BedFeature>> featuresByChrom;

  public BedTrack(Path filePath) throws IOException {
    super(filePath.getFileName().toString(), "BED");
    this.sourcePath = filePath.toAbsolutePath().normalize();
    this.preferredHeight = 25;
    this.color = Color.rgb(70, 130, 180);
    this.featuresByChrom = BedFileReader.read(filePath, this.color);
  }

  @Override
  public Path getSourcePath() {
    return sourcePath;
  }

  @Override
  public void onRegionChanged(String chromosome, long start, long end, DrawStack drawStack) {
  }
  
  @Override
  public void draw(GraphicsContext gc, double x, double y, double width, double height,
                   String chromosome, double start, double end) {
    gc.setFill(Color.rgb(28, 28, 32));
    gc.fillRect(x, y, width, height);

    gc.setFill(Color.GRAY);
    gc.setFont(AppFonts.getUIFont(9));
    gc.setTextAlign(TextAlignment.LEFT);
    gc.fillText(name, x + 4, y + 10);
    
    List<BedFeature> features = featuresByChrom.get(chromosome);
    if (features == null || features.isEmpty()) return;
    
    double plotTop = y + 14;
    double plotHeight = Math.max(FEATURE_BAR_HEIGHT, height - 18);
    double featureHeight = Math.min(FEATURE_BAR_HEIGHT, plotHeight);
    double featureY = plotTop + (plotHeight - featureHeight) / 2;
    double viewLength = end - start;

    int from = findFirstOverlappingIndex(features, start, end);
    for (int i = from; i < features.size(); i++) {
      BedFeature feature = features.get(i);
      if (feature.start() + 1 > end) {
        break;
      }
      if (feature.end() < start) {
        continue;
      }
      
      double featureX1 = Math.max(x, x + ((feature.start() + 1 - start) / viewLength) * width);
      double featureX2 = Math.min(x + width, x + ((feature.end() - start) / viewLength) * width);
      double featureWidth = Math.max(1, featureX2 - featureX1);
      
      gc.setFill(feature.color());
      gc.fillRect(featureX1, featureY, featureWidth, featureHeight);
      
      if (featureWidth > 30 && !feature.name().isEmpty()) {
        gc.setFill(Color.WHITE);
        gc.setFont(AppFonts.getUIFont(8));
        gc.fillText(feature.name(), featureX1 + 2, featureY + featureHeight / 2 + 3);
      }
    }
    
    gc.setTextAlign(TextAlignment.LEFT);
  }

  /**
   * Index of the first feature that may overlap [{@code viewStart}, {@code viewEnd}]
   * in a list sorted by {@code start} (then {@code end}). Returns {@code features.size()}
   * if none. Caller should scan forward and stop when {@code start + 1 > viewEnd}.
   */
  public static int findFirstOverlappingIndex(
      List<BedFeature> features, double viewStart, double viewEnd) {
    if (features == null || features.isEmpty()) {
      return 0;
    }
    // First index with start+1 > viewStart (features before may still overlap if end >= viewStart).
    int lo = 0;
    int hi = features.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (features.get(mid).start() + 1 <= viewStart) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    int i = lo;
    while (i > 0 && features.get(i - 1).end() >= viewStart) {
      i--;
    }
    return i;
  }
  
  public List<BedFeature> getFeatures(String chromosome) {
    return featuresByChrom.getOrDefault(chromosome, List.of());
  }

  public Set<String> getChromosomes() {
    return featuresByChrom.keySet();
  }
}

package org.baseplayer.features;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.io.readers.BedFileReader;
import org.baseplayer.io.readers.BedFileReader.BedFeature;
import org.baseplayer.utils.AppFonts;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

public class BedTrack extends AbstractTrack {

  private final Map<String, List<BedFeature>> featuresByChrom;

  public BedTrack(Path filePath) throws IOException {
    super(filePath.getFileName().toString(), "BED");
    this.preferredHeight = 25;
    this.color = Color.rgb(70, 130, 180);
    this.featuresByChrom = BedFileReader.read(filePath, this.color);
  }

  @Override
  public void onRegionChanged(String chromosome, long start, long end) {
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
    
    double featureY = y + 14;
    double featureHeight = height - 18;
    double viewLength = end - start;
    
    for (BedFeature feature : features) {
      if (feature.end() < start || feature.start() + 1 > end) continue;
      
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
  
  public List<BedFeature> getFeatures(String chromosome) {
    return featuresByChrom.getOrDefault(chromosome, List.of());
  }

  public Set<String> getChromosomes() {
    return featuresByChrom.keySet();
  }
}

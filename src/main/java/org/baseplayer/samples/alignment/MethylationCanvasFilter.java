package org.baseplayer.samples.alignment;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.baseplayer.draw.GenomicCanvas;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;

/**
 * Canvas-only methylation visibility (aggregate legend toggles), parallel to
 * {@code VcfManager.canvasHiddenTypes}. Does not change BAM loading.
 * <p>
 * Site-context changes bump {@link #contextRevisionProperty()} so coverage caches
 * recompute filtered methyl ratios. Mod-type changes only trigger a redraw.
 */
public final class MethylationCanvasFilter {

  private static final MethylationCanvasFilter INSTANCE = new MethylationCanvasFilter();

  private final Set<MethylSiteContext> hiddenContexts = EnumSet.noneOf(MethylSiteContext.class);
  private final Set<Character> hiddenModTypes = new LinkedHashSet<>();
  private final ReadOnlyIntegerWrapper contextRevision = new ReadOnlyIntegerWrapper(0);

  private MethylationCanvasFilter() {}

  public static MethylationCanvasFilter get() {
    return INSTANCE;
  }

  public ReadOnlyIntegerProperty contextRevisionProperty() {
    return contextRevision.getReadOnlyProperty();
  }

  public int getContextRevision() {
    return contextRevision.get();
  }

  public synchronized boolean isContextVisible(MethylSiteContext context) {
    return context != null && !hiddenContexts.contains(context);
  }

  /** True when the site should contribute to methyl ratios / cytosine marks. */
  public synchronized boolean isSiteDrawn(MethylSiteContext context) {
    if (context == null) {
      // Unknown context (no ref): keep visible so data is not silently dropped.
      return true;
    }
    return !hiddenContexts.contains(context);
  }

  public synchronized boolean isModTypeVisible(char modCode) {
    return !hiddenModTypes.contains(Character.toLowerCase(modCode));
  }

  public synchronized boolean isModTypeVisible(String modCode) {
    if (modCode == null || modCode.isEmpty()) {
      return true;
    }
    return isModTypeVisible(modCode.charAt(0));
  }

  public synchronized void toggleContext(MethylSiteContext context) {
    if (context == null) {
      return;
    }
    if (!hiddenContexts.add(context)) {
      hiddenContexts.remove(context);
    }
    bumpContextRevision();
    redraw();
  }

  public synchronized void setAllContextsVisible(boolean visible) {
    boolean changed;
    if (visible) {
      changed = !hiddenContexts.isEmpty();
      hiddenContexts.clear();
    } else {
      changed = hiddenContexts.addAll(EnumSet.allOf(MethylSiteContext.class));
    }
    if (changed) {
      bumpContextRevision();
      redraw();
    }
  }

  public synchronized void toggleModType(char modCode) {
    char key = Character.toLowerCase(modCode);
    if (!hiddenModTypes.add(key)) {
      hiddenModTypes.remove(key);
    }
    redraw();
  }

  public synchronized void setAllModTypesVisible(Collection<Character> present, boolean visible) {
    if (present == null || present.isEmpty()) {
      return;
    }
    boolean changed = false;
    for (Character c : present) {
      if (c == null) {
        continue;
      }
      char key = Character.toLowerCase(c);
      if (visible) {
        changed |= hiddenModTypes.remove(key);
      } else {
        changed |= hiddenModTypes.add(key);
      }
    }
    if (changed) {
      redraw();
    }
  }

  public synchronized boolean allContextsVisible() {
    return hiddenContexts.isEmpty();
  }

  public synchronized boolean allModTypesVisible(Collection<Character> present) {
    if (present == null || present.isEmpty()) {
      return true;
    }
    for (Character c : present) {
      if (c != null && hiddenModTypes.contains(Character.toLowerCase(c))) {
        return false;
      }
    }
    return true;
  }

  /** Mod codes observed on open methylation / base-mod BAM samples. */
  public Set<Character> collectPresentModTypes() {
    Set<Character> present = new LinkedHashSet<>();
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    for (SampleTrack track : registry.getSampleTracks()) {
      if (track == null) {
        continue;
      }
      for (Sample sample : track.getSamples()) {
        if (sample == null || sample.getBamFile() == null) {
          continue;
        }
        AlignmentFile bam = sample.getBamFile();
        if (!bam.hasBaseModifications()) {
          continue;
        }
        present.addAll(bam.getObservedModTypes());
      }
    }
    return present;
  }

  public boolean anyMethylSamplesOpen() {
    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    for (SampleTrack track : registry.getSampleTracks()) {
      if (track == null) {
        continue;
      }
      for (Sample sample : track.getSamples()) {
        if (sample != null && sample.isMethylationData()) {
          return true;
        }
        if (sample != null && sample.getBamFile() != null && sample.getBamFile().hasBaseModifications()) {
          return true;
        }
      }
    }
    return false;
  }

  private void bumpContextRevision() {
    contextRevision.set(contextRevision.get() + 1);
  }

  private void redraw() {
    Platform.runLater(() ->
        GenomicCanvas.update.set(!GenomicCanvas.update.get()));
  }
}

package org.baseplayer.utils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.annotation.CosmicGenes;
import org.baseplayer.genome.gene.Gene;

import javafx.scene.paint.Color;

/**
 * Shared gene-biotype (and COSMIC) visibility for the gene-canvas legend and
 * Variant Manager table filtering. Categories match {@link GeneColors}.
 */
public final class GeneBiotypeVisibility {

  public enum Category {
    COSMIC("COSMIC", Color.web("#d16624")),
    PROTEIN_CODING("Coding", Color.web("#5a9a8a")),
    LNCRNA("lncRNA", Color.GRAY),
    SMALL_RNA("Small RNA", Color.LIGHTCORAL),
    PSEUDOGENE("Pseudogene", Color.LIGHTGRAY),
    OTHER("Other", Color.CORNFLOWERBLUE);

    private final String label;
    private final Color color;

    Category(String label, Color color) {
      this.label = label;
      this.color = color;
    }

    public String label() {
      return label;
    }

    public Color color() {
      return color;
    }
  }

  private static final GeneBiotypeVisibility INSTANCE = new GeneBiotypeVisibility();

  private final Set<Category> hidden = EnumSet.noneOf(Category.class);
  private final List<Runnable> listeners = new ArrayList<>();

  private GeneBiotypeVisibility() {}

  public static GeneBiotypeVisibility get() {
    return INSTANCE;
  }

  public static Category categoryOf(String geneName, String biotype) {
    if (CosmicGenes.isCosmicGene(geneName)) {
      return Category.COSMIC;
    }
    if (biotype == null) {
      return Category.OTHER;
    }
    return switch (biotype) {
      case "protein_coding" -> Category.PROTEIN_CODING;
      case "lncRNA", "lincRNA" -> Category.LNCRNA;
      case "miRNA", "snRNA", "snoRNA" -> Category.SMALL_RNA;
      case "pseudogene", "processed_pseudogene", "transcribed_unitary_pseudogene"
          -> Category.PSEUDOGENE;
      default -> Category.OTHER;
    };
  }

  public static Category categoryOf(Gene gene) {
    if (gene == null) {
      return Category.OTHER;
    }
    return categoryOf(gene.name(), gene.biotype());
  }

  public static Category categoryOfGeneName(String geneName) {
    if (geneName == null || geneName.isBlank()) {
      return null;
    }
    String biotype = AnnotationData.getGeneBiotype(geneName);
    return categoryOf(geneName, biotype);
  }

  public synchronized boolean isVisible(Category category) {
    return category == null || !hidden.contains(category);
  }

  public synchronized boolean isVisible(String geneName, String biotype) {
    return isVisible(categoryOf(geneName, biotype));
  }

  public synchronized boolean isVisible(Gene gene) {
    return gene != null && isVisible(categoryOf(gene));
  }

  /**
   * Gene-name visibility for Variant Manager rows. Blank / unknown names
   * (intergenic) stay visible — they are not gene-colored.
   */
  public synchronized boolean isVisibleGeneName(String geneName) {
    if (geneName == null || geneName.isBlank()) {
      return true;
    }
    return isVisible(categoryOfGeneName(geneName));
  }

  public synchronized void toggle(Category category) {
    if (category == null) {
      return;
    }
    if (!hidden.add(category)) {
      hidden.remove(category);
    }
    notifyListeners();
  }

  public synchronized void setVisible(Category category, boolean visible) {
    if (category == null) {
      return;
    }
    boolean changed = visible ? hidden.remove(category) : hidden.add(category);
    if (changed) {
      notifyListeners();
    }
  }

  /** True when every legend category is visible. */
  public synchronized boolean areAllVisible() {
    return hidden.isEmpty();
  }

  /** True when no legend category is visible. */
  public synchronized boolean areNoneVisible() {
    return hidden.containsAll(EnumSet.allOf(Category.class));
  }

  /** Select or deselect every biotype category (legend “All” checkbox). */
  public synchronized void setAllVisible(boolean visible) {
    if (visible) {
      if (hidden.isEmpty()) {
        return;
      }
      hidden.clear();
    } else {
      EnumSet<Category> all = EnumSet.allOf(Category.class);
      if (hidden.containsAll(all)) {
        return;
      }
      hidden.addAll(all);
    }
    notifyListeners();
  }

  public synchronized void addListener(Runnable listener) {
    if (listener != null && !listeners.contains(listener)) {
      listeners.add(listener);
    }
  }

  public synchronized void removeListener(Runnable listener) {
    listeners.remove(listener);
  }

  private void notifyListeners() {
    List<Runnable> snapshot = List.copyOf(listeners);
    for (Runnable listener : snapshot) {
      try {
        listener.run();
      } catch (RuntimeException ex) {
        System.err.println("GeneBiotypeVisibility listener failed: " + ex.getMessage());
      }
    }
  }
}

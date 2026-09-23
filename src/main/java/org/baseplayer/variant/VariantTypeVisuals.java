package org.baseplayer.variant;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import javafx.scene.paint.Color;

/**
 * Shared labels and colors for variant types across Variant Manager, track drawing,
 * and the sample aggregate density band.
 */
public final class VariantTypeVisuals {

  private VariantTypeVisuals() {}

  public static String shortLabel(VcfVariantType type) {
    if (type == null) {
      return "?";
    }
    return switch (type) {
      case SNV -> "SNV";
      case INSERTION -> "INS";
      case DELETION -> "DEL";
      case MNV -> "MNV";
      case SV_DELETION -> "DEL";
      case SV_INSERTION -> "INS";
      case SV_DUPLICATION -> "DUP";
      case SV_INVERSION -> "INV";
      case SV_TRANSLOCATION -> "TRA";
      case SV_BREAKEND -> "BND";
      case COMPLEX -> "Complex";
    };
  }

  public static Color color(VcfVariantType type) {
    if (type == null) {
      return Color.web("#B8E986");
    }
    return switch (type) {
      case SNV -> Color.web("#4A90E2");
      case INSERTION -> Color.web("#7ED321");
      case DELETION -> Color.rgb(200, 100, 100);       // Muted red (app palette)
      case MNV -> Color.web("#BD10E0");
      case COMPLEX -> Color.web("#B8E986");
      case SV_DELETION -> Color.rgb(200, 100, 100);    // Same muted red as indel DEL
      case SV_INVERSION -> Color.web("#4488ff");
      case SV_DUPLICATION -> Color.web("#c0c0d0");
      case SV_INSERTION -> Color.web("#33cc66");
      case SV_TRANSLOCATION -> Color.web("#ffdd00");
      case SV_BREAKEND -> Color.web("#c0c0c0");
    };
  }

  /**
   * Types shown as filter checkboxes / density legends for the given present set.
   * Merges indel INS/DEL into SV counterparts when SVs are present (same as Variant Manager).
   */
  public static Set<VcfVariantType> typesForUi(Collection<VcfVariantType> presentTypes) {
    Set<VcfVariantType> present = (presentTypes != null && !presentTypes.isEmpty())
        ? EnumSet.copyOf(presentTypes)
        : EnumSet.noneOf(VcfVariantType.class);

    boolean hasSvTypes = present.stream().anyMatch(VariantTypeVisuals::isStructural);

    Set<VcfVariantType> typesToShow = new LinkedHashSet<>();
    for (VcfVariantType type : present) {
      switch (type) {
        case SNV, MNV, COMPLEX -> typesToShow.add(type);
        case INSERTION -> {
          if (!hasSvTypes || !present.contains(VcfVariantType.SV_INSERTION)) {
            typesToShow.add(type);
          }
        }
        case DELETION -> {
          if (!hasSvTypes || !present.contains(VcfVariantType.SV_DELETION)) {
            typesToShow.add(type);
          }
        }
        case SV_INSERTION, SV_DELETION, SV_DUPLICATION, SV_INVERSION,
             SV_TRANSLOCATION, SV_BREAKEND -> {
          if (hasSvTypes) {
            typesToShow.add(type);
          }
        }
      }
    }
    return typesToShow;
  }

  /**
   * Types toggled together when interacting with a UI entry for {@code displayType}
   * (e.g. SV DEL also covers indel DEL when both exist).
   */
  public static Set<VcfVariantType> linkedTypes(
      VcfVariantType displayType, Collection<VcfVariantType> presentTypes) {
    Set<VcfVariantType> present = (presentTypes != null && !presentTypes.isEmpty())
        ? EnumSet.copyOf(presentTypes)
        : EnumSet.noneOf(VcfVariantType.class);
    Set<VcfVariantType> linked = EnumSet.of(displayType);
    if (displayType == VcfVariantType.SV_INSERTION && present.contains(VcfVariantType.INSERTION)) {
      linked.add(VcfVariantType.INSERTION);
    } else if (displayType == VcfVariantType.SV_DELETION && present.contains(VcfVariantType.DELETION)) {
      linked.add(VcfVariantType.DELETION);
    } else if (displayType == VcfVariantType.INSERTION && present.contains(VcfVariantType.SV_INSERTION)) {
      linked.add(VcfVariantType.SV_INSERTION);
    } else if (displayType == VcfVariantType.DELETION && present.contains(VcfVariantType.SV_DELETION)) {
      linked.add(VcfVariantType.SV_DELETION);
    }
    return linked;
  }

  public static boolean isStructural(VcfVariantType type) {
    return type == VcfVariantType.SV_DELETION
        || type == VcfVariantType.SV_INSERTION
        || type == VcfVariantType.SV_DUPLICATION
        || type == VcfVariantType.SV_INVERSION
        || type == VcfVariantType.SV_TRANSLOCATION
        || type == VcfVariantType.SV_BREAKEND;
  }
}

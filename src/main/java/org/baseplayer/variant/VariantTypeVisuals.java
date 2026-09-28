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

  /** Top-level Variant Manager mode: point mutations vs structural variants. */
  public enum VariantClass {
    POINT,
    STRUCTURAL;

    public static VariantClass of(VcfVariantType type) {
      return isStructural(type) ? STRUCTURAL : POINT;
    }

    public boolean contains(VcfVariantType type) {
      return type != null && of(type) == this;
    }

    public EnumSet<VcfVariantType> allTypes() {
      EnumSet<VcfVariantType> out = EnumSet.noneOf(VcfVariantType.class);
      for (VcfVariantType type : VcfVariantType.values()) {
        if (contains(type)) {
          out.add(type);
        }
      }
      return out;
    }
  }

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
      case DELETION -> Color.rgb(200, 100, 100);
      case MNV -> Color.web("#BD10E0");
      case COMPLEX -> Color.web("#B8E986");
      case SV_DELETION -> Color.rgb(200, 100, 100);
      case SV_INVERSION -> Color.web("#4488ff");
      case SV_DUPLICATION -> Color.web("#c0c0d0");
      case SV_INSERTION -> Color.web("#33cc66");
      case SV_TRANSLOCATION -> Color.web("#ffdd00");
      case SV_BREAKEND -> Color.web("#c0c0c0");
    };
  }

  /**
   * Types shown as filter checkboxes / density legends for the given present set.
   * Point and SV types are listed independently (no indel↔SV merging).
   */
  public static Set<VcfVariantType> typesForUi(Collection<VcfVariantType> presentTypes) {
    return typesForUi(presentTypes, null);
  }

  /**
   * Types for UI limited to {@code variantClass} when non-null.
   */
  public static Set<VcfVariantType> typesForUi(
      Collection<VcfVariantType> presentTypes, VariantClass variantClass) {
    Set<VcfVariantType> present = (presentTypes != null && !presentTypes.isEmpty())
        ? EnumSet.copyOf(presentTypes)
        : EnumSet.noneOf(VcfVariantType.class);

    Set<VcfVariantType> typesToShow = new LinkedHashSet<>();
    for (VcfVariantType type : present) {
      if (type == null) {
        continue;
      }
      if (variantClass != null && !variantClass.contains(type)) {
        continue;
      }
      typesToShow.add(type);
    }
    return typesToShow;
  }

  /**
   * Types toggled together for a UI entry. Point and SV types are independent —
   * each display type only toggles itself.
   */
  public static Set<VcfVariantType> linkedTypes(
      VcfVariantType displayType, Collection<VcfVariantType> presentTypes) {
    if (displayType == null) {
      return EnumSet.noneOf(VcfVariantType.class);
    }
    return EnumSet.of(displayType);
  }

  public static boolean isStructural(VcfVariantType type) {
    return type == VcfVariantType.SV_DELETION
        || type == VcfVariantType.SV_INSERTION
        || type == VcfVariantType.SV_DUPLICATION
        || type == VcfVariantType.SV_INVERSION
        || type == VcfVariantType.SV_TRANSLOCATION
        || type == VcfVariantType.SV_BREAKEND;
  }

  public static boolean isPoint(VcfVariantType type) {
    return type != null && !isStructural(type);
  }

  public static boolean hasClass(Collection<VcfVariantType> types, VariantClass variantClass) {
    if (types == null || variantClass == null) {
      return false;
    }
    for (VcfVariantType type : types) {
      if (variantClass.contains(type)) {
        return true;
      }
    }
    return false;
  }

  /** Span length in bp for structural nodes; {@code -1} when unknown / not an SV span. */
  public static long svSpanLengthBp(VariantNode node) {
    if (node == null || !isStructural(node.type)) {
      return -1;
    }
    if (node.svEnd > node.position) {
      return node.svEnd - node.position;
    }
    return -1;
  }
}

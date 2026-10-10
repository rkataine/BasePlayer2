package org.baseplayer.variant;

/**
 * One {@code ##INFO}, {@code ##FILTER}, or {@code ##FORMAT} definition from a VCF header.
 * Captured at open so Variant Manager can show descriptions and drive advanced filters.
 */
public record VcfHeaderFieldDef(
    Kind kind,
    String id,
    String number,
    String type,
    String description
) {
    public enum Kind {
        INFO,
        FILTER,
        FORMAT
    }

    public VcfHeaderFieldDef {
        if (kind == null) {
            throw new IllegalArgumentException("kind");
        }
        id = id != null ? id.trim() : "";
        number = blankToNull(number);
        type = blankToNull(type);
        description = description != null ? description.trim() : "";
    }

    public boolean hasId() {
        return id != null && !id.isBlank();
    }

    /** Short line for combo / list cells. */
    public String displayLabel() {
        if (kind == Kind.FILTER) {
            return id;
        }
        StringBuilder sb = new StringBuilder(id);
        if (type != null || number != null) {
            sb.append(" (");
            if (type != null) {
                sb.append(type);
            }
            if (number != null) {
                if (type != null) {
                    sb.append(", ");
                }
                sb.append("Number=").append(number);
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /** Multi-line tooltip text. */
    public String tooltipText() {
        StringBuilder sb = new StringBuilder();
        sb.append(kind.name()).append(" / ").append(id);
        if (type != null) {
            sb.append("\nType: ").append(type);
        }
        if (number != null) {
            sb.append("\nNumber: ").append(number);
        }
        if (description != null && !description.isBlank()) {
            sb.append("\n").append(description);
        } else {
            sb.append("\n(no Description in VCF header)");
        }
        return sb.toString();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

package org.baseplayer.variant.annotation;

public enum VariantEffect {
    CODING_MISSENSE,
    CODING_SYNONYMOUS,
    CODING_STOP_GAIN,
    CODING_STOP_LOSS,
    CODING_FRAMESHIFT,
    CODING_INFRAME,
    CODING_OTHER,
    UTR5,
    UTR3,
    INTRONIC,
    INTERGENIC;

    public boolean isCoding() {
        return this == CODING_MISSENSE || this == CODING_SYNONYMOUS
            || this == CODING_STOP_GAIN || this == CODING_STOP_LOSS
            || this == CODING_FRAMESHIFT || this == CODING_INFRAME
            || this == CODING_OTHER;
    }

    public boolean isIntronic() {
        return this == INTRONIC || this == UTR5 || this == UTR3;
    }

    public String displayName() {
        return switch (this) {
            case CODING_MISSENSE   -> "Missense";
            case CODING_SYNONYMOUS -> "Synonymous";
            case CODING_STOP_GAIN  -> "Stop gained";
            case CODING_STOP_LOSS  -> "Stop lost";
            case CODING_FRAMESHIFT -> "Frameshift";
            case CODING_INFRAME    -> "In-frame";
            case CODING_OTHER      -> "Coding";
            case UTR5              -> "5' UTR";
            case UTR3              -> "3' UTR";
            case INTRONIC          -> "Intronic";
            case INTERGENIC        -> "Intergenic";
        };
    }
}

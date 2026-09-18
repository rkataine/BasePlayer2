package org.baseplayer.variant;

/**
 * Parses VCF breakend ALT notation ({@code N[chr2:123[}, {@code ]chr2:123]N}, etc.)
 * into a mate chromosome and 1-based position.
 */
public final class BreakendAlt {

    public record Mate(String chrom, long pos) {}

    private BreakendAlt() {}

    public static Mate parse(String alt) {
        if (alt == null || alt.isBlank()) {
            return null;
        }

        int openBracket = alt.indexOf('[');
        int closeBracket = alt.indexOf(']');
        int startIdx = -1;
        int endIdx = -1;

        if (openBracket >= 0 && (closeBracket < 0 || openBracket <= closeBracket)) {
            startIdx = openBracket + 1;
            endIdx = alt.indexOf('[', startIdx);
        } else if (closeBracket >= 0) {
            startIdx = closeBracket + 1;
            endIdx = alt.indexOf(']', startIdx);
        }

        if (startIdx < 0 || endIdx <= startIdx) {
            return null;
        }

        String chrPos = alt.substring(startIdx, endIdx);
        int colon = chrPos.indexOf(':');
        if (colon <= 0 || colon >= chrPos.length() - 1) {
            return null;
        }

        String chrom = chrPos.substring(0, colon).trim();
        if (chrom.isEmpty()) {
            return null;
        }
        try {
            long pos = Long.parseLong(chrPos.substring(colon + 1).trim());
            if (pos < 0) {
                return null;
            }
            return new Mate(chrom, pos);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

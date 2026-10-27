package org.baseplayer.variant;

import java.util.List;
import java.util.Locale;

/**
 * Allele odds ratio of the loaded cohort versus a control population AF
 * (e.g. gnomAD {@link VariantNode#controlAf}).
 *
 * <p>Shown when ≥2 samples share the allele. Case allele counts come from GT
 * (het = 1, hom-alt = 2); non-carriers among the cohort contribute 0/2.
 * Control counts are reconstructed from AF with the same diploid AN as the
 * case cohort so Haldane–Anscombe continuity is consistent and OR ≈ 1 when
 * frequencies match.
 */
public final class VariantOddsRatio {

    private VariantOddsRatio() {}

    /**
     * @param cohortSamples diploid individuals in the comparison set
     *        (typically displayed / visible sample tracks)
     * @return odds ratio, or {@code null} when OR should not be shown
     */
    public static Double compute(VariantNode node, int cohortSamples) {
        if (node == null || node.controlAf == null) {
            return null;
        }
        int carriers = countAltCarriers(node);
        if (carriers < 2) {
            return null;
        }
        int caseAc = countAltAlleles(node);
        // Prefer the full cohort; fall back to carrier count when registry size is unknown.
        int caseAn = 2 * Math.max(Math.max(cohortSamples, 0), carriers);
        return alleleOddsRatio(caseAc, caseAn, node.controlAf.doubleValue());
    }

    /** Alt-carrying sample calls (het or hom-alt) on this node. */
    public static int countAltCarriers(VariantNode node) {
        if (node == null) {
            return 0;
        }
        List<VariantNode.SampleCall> samples = node.getSamples();
        if (samples == null || samples.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (VariantNode.SampleCall call : samples) {
            if (node.isAltCarrier(call)) {
                n++;
            }
        }
        return n;
    }

    /** Sum of ALT alleles from GT (het → 1, hom-alt → 2). */
    public static int countAltAlleles(VariantNode node) {
        if (node == null) {
            return 0;
        }
        List<VariantNode.SampleCall> samples = node.getSamples();
        if (samples == null || samples.isEmpty()) {
            return 0;
        }
        int ac = 0;
        for (VariantNode.SampleCall call : samples) {
            if (node.isHomozygousAlt(call)) {
                ac += 2;
            } else if (node.isHeterozygous(call)) {
                ac += 1;
            }
        }
        return ac;
    }

    /**
     * Haldane–Anscombe allele OR:
     * {@code ((AC_c+0.5)/(AN_c−AC_c+0.5)) / ((AF·AN+0.5)/((1−AF)·AN+0.5))}.
     */
    static double alleleOddsRatio(int caseAc, int caseAn, double controlAf) {
        if (caseAn < 2 || caseAc < 0 || caseAc > caseAn) {
            return Double.NaN;
        }
        if (Double.isNaN(controlAf) || controlAf < 0.0 || controlAf > 1.0) {
            return Double.NaN;
        }
        double caseOdds = (caseAc + 0.5) / (caseAn - caseAc + 0.5);
        double ctrlAlt = controlAf * caseAn + 0.5;
        double ctrlRef = (1.0 - controlAf) * caseAn + 0.5;
        return caseOdds / (ctrlAlt / ctrlRef);
    }

    /** Display string for the details tab / table. */
    public static String format(double or) {
        if (Double.isNaN(or) || or < 0) {
            return "—";
        }
        if (Double.isInfinite(or) || or >= 1000.0) {
            return "≥1000";
        }
        if (or >= 100.0) {
            return String.format(Locale.ROOT, "%.0f", or);
        }
        if (or >= 10.0) {
            return String.format(Locale.ROOT, "%.1f", or);
        }
        return String.format(Locale.ROOT, "%.2f", or);
    }
}

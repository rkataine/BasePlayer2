package org.baseplayer.variant;

import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;

import java.util.EnumSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VariantFilter {

    /** Sentinel group id for samples that are not in any named group. */
    public static final int UNGROUPED_COHORT_ID = -1;

    /** How a named sample group participates in multi-group comparison. */
    public enum GroupRole {
        IGNORE,
        PRESENT,
        ABSENT
    }

    /**
     * When multiple groups are marked {@link GroupRole#PRESENT}, require the variant
     * in every such group (AND) or in at least one (OR). Absent groups are always AND.
     */
    public enum PresentMatchMode {
        ALL,
        ANY
    }

    private double minQuality = 0.0;
    private int minDepth = 0;
    private double minAlleleFraction = 0.0;
    private boolean cancerGenesOnly = false;
    /** Inclusive lower bound on how many samples must share a variant (default 1 = no floor). */
    private int minSharedSamples = 1;
    /** Inclusive upper bound; {@link Integer#MAX_VALUE} means no ceiling. */
    private int maxSharedSamples = Integer.MAX_VALUE;
    private PresentMatchMode presentMatchMode = PresentMatchMode.ALL;
    /**
     * When true, shared-sample and group comparison use the union of mutated samples
     * across all variants in a gene (see {@link #passesNodeLevel(VariantNode, Set)}).
     */
    private boolean geneLevel = false;
    /**
     * Soft-match window in bp for Sample Comparison. Variants of the same type family
     * within this window (or with overlapping SV spans expanded by this amount) share
     * sample counts. Ignored when {@link #geneLevel} is on. {@code 0} = exact allele only.
     */
    private int comparisonWindowBp = 0;
    /** Role per sample-group id ({@link #UNGROUPED_COHORT_ID} = ungrouped). */
    private Map<Integer, GroupRole> groupRoles = new HashMap<>();
    /** Resolved track indices per group id at apply time. */
    private Map<Integer, Set<Integer>> groupTrackIndices = new HashMap<>();
    private Set<VcfVariantType> allowedTypes = EnumSet.allOf(VcfVariantType.class);
    private Set<VariantEffect> allowedEffects = EnumSet.allOf(VariantEffect.class);
    private boolean showCoding = true;
    private boolean showIntronic = true;
    private boolean showIntergenic = true;
    
    // Advanced filters for INFO and FILTER fields
    private Map<String, String> infoFieldFilters = new HashMap<>();  // Field name -> expected value
    private Set<String> allowedFilterValues = new HashSet<>();        // E.g., "PASS", "LowQual", etc.
    private boolean filterFieldsActive = false;                       // Whether to apply FILTER field filtering

    // ── Getters/setters ───────────────────────────────────────────────────────

    public double getMinQuality() { return minQuality; }
    public void setMinQuality(double minQuality) { this.minQuality = minQuality; }
    
    public int getMinDepth() { return minDepth; }
    public void setMinDepth(int minDepth) { this.minDepth = minDepth; }
    
    public double getMinAlleleFraction() { return minAlleleFraction; }
    public void setMinAlleleFraction(double minAlleleFraction) { this.minAlleleFraction = minAlleleFraction; }

    public boolean isCancerGenesOnly() { return cancerGenesOnly; }
    public void setCancerGenesOnly(boolean cancerGenesOnly) { this.cancerGenesOnly = cancerGenesOnly; }

    public int getMinSharedSamples() { return minSharedSamples; }
    public void setMinSharedSamples(int minSharedSamples) {
        this.minSharedSamples = Math.max(1, minSharedSamples);
    }

    public int getMaxSharedSamples() { return maxSharedSamples; }
    public void setMaxSharedSamples(int maxSharedSamples) {
        this.maxSharedSamples = maxSharedSamples < 1 ? Integer.MAX_VALUE : maxSharedSamples;
    }

    public PresentMatchMode getPresentMatchMode() { return presentMatchMode; }
    public void setPresentMatchMode(PresentMatchMode presentMatchMode) {
        this.presentMatchMode = presentMatchMode != null ? presentMatchMode : PresentMatchMode.ALL;
    }

    public boolean isGeneLevel() { return geneLevel; }
    public void setGeneLevel(boolean geneLevel) { this.geneLevel = geneLevel; }

    public int getComparisonWindowBp() { return comparisonWindowBp; }
    public void setComparisonWindowBp(int comparisonWindowBp) {
        this.comparisonWindowBp = Math.max(0, comparisonWindowBp);
    }

    public boolean hasComparisonWindow() {
        return !geneLevel && comparisonWindowBp > 0;
    }

    public Map<Integer, GroupRole> getGroupRoles() { return groupRoles; }
    public void setGroupRoles(Map<Integer, GroupRole> groupRoles) {
        this.groupRoles = groupRoles != null ? new HashMap<>(groupRoles) : new HashMap<>();
    }

    public Map<Integer, Set<Integer>> getGroupTrackIndices() { return groupTrackIndices; }
    public void setGroupTrackIndices(Map<Integer, Set<Integer>> groupTrackIndices) {
        this.groupTrackIndices = new HashMap<>();
        if (groupTrackIndices != null) {
            for (Map.Entry<Integer, Set<Integer>> entry : groupTrackIndices.entrySet()) {
                this.groupTrackIndices.put(
                    entry.getKey(),
                    entry.getValue() != null ? new HashSet<>(entry.getValue()) : new HashSet<>());
            }
        }
    }

    public boolean hasActiveGroupComparison() {
        for (GroupRole role : groupRoles.values()) {
            if (role == GroupRole.PRESENT || role == GroupRole.ABSENT) {
                return true;
            }
        }
        return false;
    }

    public Set<VcfVariantType> getAllowedTypes() { return allowedTypes; }
    public void setAllowedTypes(Set<VcfVariantType> allowedTypes) { this.allowedTypes = allowedTypes; }

    public Set<VariantEffect> getAllowedEffects() { return allowedEffects; }
    public void setAllowedEffects(Set<VariantEffect> allowedEffects) {
        if (allowedEffects == null || allowedEffects.isEmpty()) {
            this.allowedEffects = EnumSet.noneOf(VariantEffect.class);
        } else {
            this.allowedEffects = EnumSet.copyOf(allowedEffects);
        }
        syncVisibilityFlagsFromAllowedEffects();
    }

    public boolean isShowCoding() { return showCoding; }

    public boolean isShowIntronic() { return showIntronic; }

    public boolean isShowIntergenic() { return showIntergenic; }
    
    public Map<String, String> getInfoFieldFilters() { return infoFieldFilters; }
    public void setInfoFieldFilters(Map<String, String> filters) { this.infoFieldFilters = filters; }
    
    public Set<String> getAllowedFilterValues() { return allowedFilterValues; }
    public void setAllowedFilterValues(Set<String> values) { 
        this.allowedFilterValues = values; 
        this.filterFieldsActive = !values.isEmpty();
    }
    
    public boolean isFilterFieldsActive() { return filterFieldsActive; }

    private void syncVisibilityFlagsFromAllowedEffects() {
        boolean hasCodingLike = false;
        boolean hasIntronic = false;
        boolean hasIntergenic = false;

        for (VariantEffect effect : allowedEffects) {
            if (effect == null) {
                continue;
            }
            if (effect.isCoding()
                || effect.isSpliceSite()
                || effect.isRegulatory()
                || effect == VariantEffect.NONCODING_GENE
                || effect == VariantEffect.UTR5
                || effect == VariantEffect.UTR3) {
                hasCodingLike = true;
            }
            if (effect.isIntronic()) {
                hasIntronic = true;
            }
            if (effect == VariantEffect.INTERGENIC) {
                hasIntergenic = true;
            }
        }

        this.showCoding = hasCodingLike;
        this.showIntronic = hasIntronic;
        this.showIntergenic = hasIntergenic;
    }

    /** Create a deep copy so long-running loads can use a stable filter snapshot. */
    public VariantFilter copy() {
        VariantFilter copy = new VariantFilter();
        copy.minQuality = this.minQuality;
        copy.minDepth = this.minDepth;
        copy.minAlleleFraction = this.minAlleleFraction;
        copy.cancerGenesOnly = this.cancerGenesOnly;
        copy.minSharedSamples = this.minSharedSamples;
        copy.maxSharedSamples = this.maxSharedSamples;
        copy.presentMatchMode = this.presentMatchMode;
        copy.geneLevel = this.geneLevel;
        copy.comparisonWindowBp = this.comparisonWindowBp;
        copy.groupRoles = new HashMap<>(this.groupRoles);
        copy.groupTrackIndices = new HashMap<>();
        for (Map.Entry<Integer, Set<Integer>> entry : this.groupTrackIndices.entrySet()) {
            copy.groupTrackIndices.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        copy.allowedTypes = EnumSet.copyOf(this.allowedTypes);
        copy.allowedEffects = EnumSet.copyOf(this.allowedEffects);
        copy.showCoding = this.showCoding;
        copy.showIntronic = this.showIntronic;
        copy.showIntergenic = this.showIntergenic;
        copy.infoFieldFilters = new HashMap<>(this.infoFieldFilters);
        copy.allowedFilterValues = new HashSet<>(this.allowedFilterValues);
        copy.filterFieldsActive = this.filterFieldsActive;
        return copy;
    }

    /**
     * Filters that can be applied during VCF streaming before annotation is available.
     * Annotation-dependent dimensions (coding/intronic/intergenic, cancer-only, INFO/FILTER)
     * are intentionally deferred until after annotation/pruning.
     */
    public boolean passesLoadTime(VcfVariantType type, double siteQuality, VariantNode.SampleCall call) {
        if (!allowedTypes.contains(type)) return false;

        if (minQuality > 0) {
            if (siteQuality >= 0) {
                if (siteQuality < minQuality) return false;
            } else if (call != null && call.quality >= 0 && call.quality < minQuality) {
                return false;
            }
        }

        if (call != null) {
            if (minDepth > 0 && call.depth >= 0 && call.depth < minDepth) return false;
            if (minAlleleFraction > 0 && call.alleleFraction >= 0 && call.alleleFraction < minAlleleFraction) return false;
        }

        return true;
    }

    /** Whether this filter needs annotation-aware post-load pruning. */
    public boolean requiresPostAnnotationFiltering() {
        return cancerGenesOnly
            || !showCoding
            || !showIntronic
            || !showIntergenic
            || !infoFieldFilters.isEmpty()
            || filterFieldsActive;
    }

    /** Stable key for comparing whether cached chromosome data matches filter settings. */
    public String toStableKey() {
        List<String> typeNames = new ArrayList<>();
        for (VcfVariantType t : allowedTypes) typeNames.add(t.name());
        Collections.sort(typeNames);

        List<String> infoKeys = new ArrayList<>(infoFieldFilters.keySet());
        Collections.sort(infoKeys);
        List<String> infoPairs = new ArrayList<>();
        for (String k : infoKeys) {
            infoPairs.add(k + "=" + infoFieldFilters.get(k));
        }

        List<String> filterVals = new ArrayList<>(allowedFilterValues);
        Collections.sort(filterVals);

        List<String> effectNames = new ArrayList<>();
        for (VariantEffect e : allowedEffects) {
            if (e != null) effectNames.add(e.name());
        }
        Collections.sort(effectNames);

        return "minQ=" + minQuality
            + "|minDP=" + minDepth
            + "|minAF=" + minAlleleFraction
            + "|cancerOnly=" + cancerGenesOnly
            + "|minShare=" + minSharedSamples
            + "|maxShare=" + maxSharedSamples
            + "|geneLevel=" + geneLevel
            + "|cmpWindow=" + comparisonWindowBp
            + "|presentMode=" + presentMatchMode
            + "|groupRoles=" + sortedGroupRolesKey()
            + "|groupTracks=" + sortedGroupTracksKey()
            + "|showCoding=" + showCoding
            + "|showIntronic=" + showIntronic
            + "|showIntergenic=" + showIntergenic
            + "|effects=" + String.join(",", effectNames)
            + "|types=" + String.join(",", typeNames)
            + "|info=" + String.join(",", infoPairs)
            + "|filterActive=" + filterFieldsActive
            + "|filterValues=" + String.join(",", filterVals);
    }

    private String sortedGroupRolesKey() {
        List<Integer> ids = new ArrayList<>(groupRoles.keySet());
        Collections.sort(ids);
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            GroupRole role = groupRoles.get(id);
            if (role == null || role == GroupRole.IGNORE) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(id).append('=').append(role.name());
        }
        return sb.toString();
    }

    private String sortedGroupTracksKey() {
        List<Integer> ids = new ArrayList<>(groupTrackIndices.keySet());
        Collections.sort(ids);
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            if (sb.length() > 0) sb.append(';');
            sb.append(id).append(':').append(sortedInts(groupTrackIndices.get(id)));
        }
        return sb.toString();
    }

    private static String sortedInts(Set<Integer> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(sorted.get(i));
        }
        return sb.toString();
    }

    // ── Filtering logic ───────────────────────────────────────────────────────

    /**
     * Variant-level filter checks shared by all samples for a node.
     * Use this to avoid repeating annotation/type checks in per-sample loops.
     * When {@link #geneLevel} or comparison window clustering is on, prefer
     * {@link #passesNodeLevel(VariantNode, Set)} with the aggregated sample tracks.
     */
    public boolean passesNodeLevel(VariantNode node) {
        return passesNodeLevel(node, null);
    }

    /**
     * @param aggregatedSampleTracks when gene-level or comparison-window clustering is active,
     *        the union of mutated sample track indices for this node's gene/cluster
     *        (null falls back to per-variant semantics)
     */
    public boolean passesNodeLevel(VariantNode node, Set<Integer> aggregatedSampleTracks) {
        if (!passesBaseNodeLevel(node)) {
            return false;
        }
        if (geneLevel || hasComparisonWindow()) {
            if (aggregatedSampleTracks == null) {
                return passesSharedSampleCount(node) && passesGroupComparison(node);
            }
            return passesSharedSampleCount(aggregatedSampleTracks)
                && passesGroupComparison(aggregatedSampleTracks);
        }
        return passesSharedSampleCount(node) && passesGroupComparison(node);
    }

    /**
     * Type / quality / annotation / INFO checks only — excludes shared-sample and group comparison.
     * Used when building the gene→samples index so those constraints apply to the gene union.
     */
    public boolean passesBaseNodeLevel(VariantNode node) {
        if (node == null) return false;
        if (!allowedTypes.contains(node.type)) return false;

        if (minQuality > 0) {
            if (node.siteQuality >= 0 && node.siteQuality < minQuality) return false;
        }

        VariantAnnotation ann = node.annotation;
        if (ann != null) {
            if (cancerGenesOnly && !ann.isCancerGene()) return false;
            if (!allowedEffects.contains(ann.effect())) return false;
        } else {
            if (cancerGenesOnly) return false;
            if (!allowedEffects.contains(VariantEffect.INTERGENIC)) return false;
        }

        return true;
    }

    /**
     * Compare presence across named groups using quality/depth/AF thresholds.
     * Present groups use {@link #presentMatchMode}; absent groups must all lack the variant.
     */
    public boolean passesGroupComparison(VariantNode node) {
        if (node == null) return false;
        if (!hasActiveGroupComparison()) {
            return true;
        }

        boolean anyPresentMatched = false;
        boolean anyPresentConfigured = false;

        for (Map.Entry<Integer, GroupRole> entry : groupRoles.entrySet()) {
            GroupRole role = entry.getValue();
            if (role == null || role == GroupRole.IGNORE) {
                continue;
            }
            Set<Integer> tracks = groupTrackIndices.get(entry.getKey());
            boolean present = hasPassingCallInCohort(node, tracks);

            if (role == GroupRole.ABSENT) {
                if (present) {
                    return false;
                }
                continue;
            }

            // PRESENT
            anyPresentConfigured = true;
            if (presentMatchMode == PresentMatchMode.ALL) {
                if (!present) {
                    return false;
                }
            } else if (present) {
                anyPresentMatched = true;
            }
        }

        if (anyPresentConfigured && presentMatchMode == PresentMatchMode.ANY) {
            return anyPresentMatched;
        }
        return true;
    }

    /** Group comparison against a precomputed set of mutated sample track indices (gene level). */
    public boolean passesGroupComparison(Set<Integer> sampleTrackIndices) {
        if (!hasActiveGroupComparison()) {
            return true;
        }
        Set<Integer> samples = sampleTrackIndices != null ? sampleTrackIndices : Set.of();

        boolean anyPresentMatched = false;
        boolean anyPresentConfigured = false;

        for (Map.Entry<Integer, GroupRole> entry : groupRoles.entrySet()) {
            GroupRole role = entry.getValue();
            if (role == null || role == GroupRole.IGNORE) {
                continue;
            }
            Set<Integer> cohort = groupTrackIndices.get(entry.getKey());
            boolean present = intersects(samples, cohort);

            if (role == GroupRole.ABSENT) {
                if (present) {
                    return false;
                }
                continue;
            }

            anyPresentConfigured = true;
            if (presentMatchMode == PresentMatchMode.ALL) {
                if (!present) {
                    return false;
                }
            } else if (present) {
                anyPresentMatched = true;
            }
        }

        if (anyPresentConfigured && presentMatchMode == PresentMatchMode.ANY) {
            return anyPresentMatched;
        }
        return true;
    }

    private static boolean intersects(Set<Integer> a, Set<Integer> b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) {
            return false;
        }
        Set<Integer> smaller = a.size() <= b.size() ? a : b;
        Set<Integer> larger = smaller == a ? b : a;
        for (Integer v : smaller) {
            if (larger.contains(v)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPassingCallInCohort(VariantNode node, Set<Integer> trackIndices) {
        if (trackIndices == null || trackIndices.isEmpty()) {
            return false;
        }
        for (VariantNode.SampleCall call : node.getSamples()) {
            if (call == null) continue;
            int trackIndex = call.getTrackIndex();
            if (!trackIndices.contains(trackIndex)) continue;
            if (passesSampleThresholds(node, call)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the number of samples that pass quality/depth/AF thresholds for this
     * variant falls within {@link #minSharedSamples}..{@link #maxSharedSamples}.
     */
    public boolean passesSharedSampleCount(VariantNode node) {
        if (node == null) return false;
        if (minSharedSamples <= 1 && maxSharedSamples == Integer.MAX_VALUE) {
            return true;
        }

        int shared = countPassingSamples(node);
        return shared >= minSharedSamples && shared <= maxSharedSamples;
    }

    /** Shared-sample bounds against a precomputed sample-track set (gene level). */
    public boolean passesSharedSampleCount(Set<Integer> sampleTrackIndices) {
        if (minSharedSamples <= 1 && maxSharedSamples == Integer.MAX_VALUE) {
            return true;
        }
        int shared = sampleTrackIndices != null ? sampleTrackIndices.size() : 0;
        return shared >= minSharedSamples && shared <= maxSharedSamples;
    }

    /** Count sample calls that pass per-sample quality/depth/AF thresholds. */
    public int countPassingSamples(VariantNode node) {
        if (node == null) return 0;
        int count = 0;
        for (VariantNode.SampleCall call : node.getSamples()) {
            if (passesSampleThresholds(node, call)) {
                count++;
            }
        }
        return count;
    }

    public boolean passesSampleThresholds(VariantNode node, VariantNode.SampleCall call) {
        if (node == null || call == null) return false;

        if (minQuality > 0 && node.siteQuality < 0 && call.quality >= 0 && call.quality < minQuality) return false;
        if (minDepth > 0 && call.depth >= 0 && call.depth < minDepth) return false;
        if (minAlleleFraction > 0 && call.alleleFraction >= 0 && call.alleleFraction < minAlleleFraction) return false;

        return true;
    }

    public boolean passes(VariantNode node, VariantNode.SampleCall call) {
        return passesNodeLevel(node) && passesSampleThresholds(node, call);
    }

    /** Returns true if all filters are at default (pass-all) state. */
    public boolean isPassAll() {
        return minQuality == 0.0
            && minDepth == 0
            && minAlleleFraction == 0.0
            && !cancerGenesOnly
            && minSharedSamples <= 1
            && maxSharedSamples == Integer.MAX_VALUE
            && !geneLevel
            && comparisonWindowBp <= 0
            && !hasActiveGroupComparison()
            && showCoding && showIntronic && showIntergenic
            && allowedTypes.equals(EnumSet.allOf(VcfVariantType.class))
            && infoFieldFilters.isEmpty()
            && !filterFieldsActive;
    }
}

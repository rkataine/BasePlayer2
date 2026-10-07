package org.baseplayer.variant;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;

import java.util.EnumSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
        ABSENT,
        /** At least one cohort sample must pass thresholds and be heterozygous. */
        HETEROZYGOUS,
        /**
         * At least one cohort sample must be homozygous.
         * Alone: homozygous ALT only.
         * With {@link #HETEROZYGOUS} on another group (LOH mode): AA or BB.
         */
        HOMOZYGOUS
    }

    /**
     * When multiple groups are marked {@link GroupRole#PRESENT}, {@link GroupRole#HETEROZYGOUS},
     * or {@link GroupRole#HOMOZYGOUS}, require the constraint in every such group (AND) or
     * in at least one (OR). Absent groups are always AND.
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
    /**
     * Maps each group id to its lineage scope id (root group id, or
     * {@link #UNGROUPED_COHORT_ID}). Roles are evaluated per lineage so one
     * group's constraints do not hide another lineage's variants.
     */
    private Map<Integer, Integer> groupLineageScope = new HashMap<>();

    /** Lazy caches invalidated when group comparison inputs change. */
    private boolean lineageCacheValid = false;
    private Map<Integer, List<Map.Entry<Integer, GroupRole>>> cachedRolesByLineage = Map.of();
    private Set<Integer> cachedLohLineageIds = Set.of();
    private Set<Integer> cachedLohHomTrackIndices = Set.of();
    private Map<Integer, Integer> cachedTrackToLineage = Map.of();
    private Boolean cachedHasActiveGroupComparison;
    private Boolean cachedHasActiveGenotypeComparison;
    private Boolean cachedIsLohModeLocal;

    private Set<VcfVariantType> allowedTypes = EnumSet.allOf(VcfVariantType.class);
    private Set<VariantEffect> allowedEffects = EnumSet.allOf(VariantEffect.class);
    private boolean showCoding = true;
    private boolean showIntronic = true;
    private boolean showIntergenic = true;
    
    // Advanced filters for INFO and FILTER fields
    private Map<String, String> infoFieldFilters = new HashMap<>();  // Field name -> expected value
    private Set<String> allowedFilterValues = new HashSet<>();        // E.g., "PASS", "LowQual", etc.
    private boolean filterFieldsActive = false;                       // Whether to apply FILTER field filtering

    /**
     * Inclusive minimum SV span length in bp ({@code svEnd - position}).
     * {@code 0} = no minimum. Applied only to structural types.
     */
    private long minSvLengthBp = 0;
    /**
     * Inclusive maximum SV span length in bp. {@code Long.MAX_VALUE} = no maximum.
     * Applied only to structural types with a known span; types without END are not
     * rejected by max alone.
     */
    private long maxSvLengthBp = Long.MAX_VALUE;

    /**
     * Optional class-scoped filter slices. When both are set, type / Q / DP / AF /
     * effects / cancer / INFO / SV-length checks use the matching slice; shared-sample
     * and group comparison stay on this parent filter.
     */
    private VariantFilter pointSlice;
    private VariantFilter svSlice;
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

    public boolean isGeneLevel() {
        if (hasClassSlices()) {
            return (pointSlice != null && pointSlice.geneLevel)
                || (svSlice != null && svSlice.geneLevel);
        }
        return geneLevel;
    }
    public void setGeneLevel(boolean geneLevel) { this.geneLevel = geneLevel; }

    public int getComparisonWindowBp() { return comparisonWindowBp; }
    public void setComparisonWindowBp(int comparisonWindowBp) {
        this.comparisonWindowBp = Math.max(0, comparisonWindowBp);
    }

    public boolean hasComparisonWindow() {
        if (hasClassSlices()) {
            return (pointSlice != null && !pointSlice.geneLevel && pointSlice.comparisonWindowBp > 0)
                || (svSlice != null && !svSlice.geneLevel && svSlice.comparisonWindowBp > 0);
        }
        return !geneLevel && comparisonWindowBp > 0;
    }

    public Map<Integer, GroupRole> getGroupRoles() { return groupRoles; }
    public void setGroupRoles(Map<Integer, GroupRole> groupRoles) {
        this.groupRoles = groupRoles != null ? new HashMap<>(groupRoles) : new HashMap<>();
        invalidateLineageCache();
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
        invalidateLineageCache();
    }

    public Map<Integer, Integer> getGroupLineageScope() {
        return groupLineageScope;
    }

    public void setGroupLineageScope(Map<Integer, Integer> groupLineageScope) {
        this.groupLineageScope = groupLineageScope != null
            ? new HashMap<>(groupLineageScope)
            : new HashMap<>();
        invalidateLineageCache();
    }

    private void invalidateLineageCache() {
        lineageCacheValid = false;
        cachedRolesByLineage = Map.of();
        cachedLohLineageIds = Set.of();
        cachedLohHomTrackIndices = Set.of();
        cachedTrackToLineage = Map.of();
        cachedHasActiveGroupComparison = null;
        cachedHasActiveGenotypeComparison = null;
        cachedIsLohModeLocal = null;
    }

    private void ensureLineageCache() {
        if (lineageCacheValid) {
            return;
        }
        Map<Integer, List<Map.Entry<Integer, GroupRole>>> byLineage = new HashMap<>();
        Map<Integer, Integer> trackToLineage = new HashMap<>();
        Set<Integer> lohLineages = new HashSet<>();
        Set<Integer> lohHomTracks = new HashSet<>();
        boolean anyActive = false;
        boolean anyGenotype = false;

        for (Map.Entry<Integer, GroupRole> entry : groupRoles.entrySet()) {
            GroupRole role = entry.getValue();
            if (role == null || role == GroupRole.IGNORE) {
                continue;
            }
            anyActive = true;
            if (role == GroupRole.HETEROZYGOUS || role == GroupRole.HOMOZYGOUS) {
                anyGenotype = true;
            }
            int lineageId = lineageScopeOf(entry.getKey());
            byLineage.computeIfAbsent(lineageId, key -> new ArrayList<>()).add(entry);
            Set<Integer> tracks = groupTrackIndices.get(entry.getKey());
            if (tracks != null) {
                for (Integer trackIndex : tracks) {
                    if (trackIndex != null) {
                        trackToLineage.putIfAbsent(trackIndex, lineageId);
                    }
                }
            }
        }

        for (Map.Entry<Integer, List<Map.Entry<Integer, GroupRole>>> lineageEntry
            : byLineage.entrySet()) {
            if (isLohModeForRoles(lineageEntry.getValue())) {
                int lineageId = lineageEntry.getKey();
                lohLineages.add(lineageId);
                for (Map.Entry<Integer, GroupRole> entry : lineageEntry.getValue()) {
                    if (entry.getValue() != GroupRole.HOMOZYGOUS) {
                        continue;
                    }
                    Set<Integer> tracks = groupTrackIndices.get(entry.getKey());
                    if (tracks != null) {
                        lohHomTracks.addAll(tracks);
                    }
                }
            }
        }

        cachedRolesByLineage = byLineage;
        cachedTrackToLineage = trackToLineage;
        cachedLohLineageIds = lohLineages;
        cachedLohHomTrackIndices = lohHomTracks;
        cachedHasActiveGroupComparison = anyActive;
        cachedHasActiveGenotypeComparison = anyGenotype;
        cachedIsLohModeLocal = !lohLineages.isEmpty();
        lineageCacheValid = true;
    }

    public boolean hasActiveGroupComparison() {
        ensureLineageCache();
        return Boolean.TRUE.equals(cachedHasActiveGroupComparison);
    }

    /** True when any group requires a genotype (het / hom), not just presence. */
    public boolean hasActiveGenotypeGroupComparison() {
        ensureLineageCache();
        return Boolean.TRUE.equals(cachedHasActiveGenotypeComparison);
    }

    /**
     * LOH mode: one group is {@link GroupRole#HETEROZYGOUS} (markers) and another
     * is {@link GroupRole#HOMOZYGOUS} (AA/BB). Does not change VCF loading — het
     * filter is applied at comparison time; missing genotypes get AA via
     * {@link #addMissingLohAaCalls}.
     */
    public boolean isLohMode() {
        if (hasClassSlices()) {
            return (pointSlice != null && pointSlice.isLohModeLocal())
                || (svSlice != null && svSlice.isLohModeLocal());
        }
        return isLohModeLocal();
    }

    private boolean isLohModeLocal() {
        ensureLineageCache();
        return Boolean.TRUE.equals(cachedIsLohModeLocal);
    }

    /** LOH mode for the lineage that owns {@code groupId}, if known. */
    private boolean isLohModeForLineage(int lineageId) {
        ensureLineageCache();
        return cachedLohLineageIds.contains(lineageId);
    }

    /**
     * In LOH mode, at heterozygous-cohort (marker) sites add a {@code 0/0} (AA)
     * {@link VariantNode.SampleCall} for each {@link GroupRole#HOMOZYGOUS} track
     * that has no genotype (ALT lost). Skips tracks that already have a call.
     *
     * @return number of AA calls added on this node
     */
    public int addMissingLohAaCalls(VariantNode node) {
        if (node != null && hasClassSlices()) {
            return classSlice(node.type).addMissingLohAaCalls(node);
        }
        if (!isLohModeLocal() || node == null) {
            return 0;
        }

        int added = 0;
        Map<Integer, List<Map.Entry<Integer, GroupRole>>> byLineage = rolesGroupedByLineage();
        for (Map.Entry<Integer, List<Map.Entry<Integer, GroupRole>>> lineageEntry
            : byLineage.entrySet()) {
            if (!isLohModeForRoles(lineageEntry.getValue())) {
                continue;
            }
            Set<Integer> hetTracks = tracksForRoleInEntries(
                lineageEntry.getValue(), GroupRole.HETEROZYGOUS);
            Set<Integer> homTracks = tracksForRoleInEntries(
                lineageEntry.getValue(), GroupRole.HOMOZYGOUS);
            if (hetTracks.isEmpty() || homTracks.isEmpty()) {
                continue;
            }
            if (!hasPassingZygosityInCohort(node, hetTracks, Zygosity.HET)) {
                continue;
            }
            for (Integer trackIndex : homTracks) {
                if (trackIndex == null || trackIndex < 0) {
                    continue;
                }
                if (node.getSampleCall(trackIndex) != null) {
                    continue;
                }
                String gt = (node.ref != null && !node.ref.isBlank())
                    ? node.ref + "/" + node.ref
                    : "0/0";
                node.addSample(new VariantNode.SampleCall(trackIndex, gt, -1, -1, 0.0));
                added++;
            }
        }
        return added;
    }

    private Set<Integer> tracksForRoleInEntries(
        List<Map.Entry<Integer, GroupRole>> roles, GroupRole wanted) {
        Set<Integer> tracks = new HashSet<>();
        for (Map.Entry<Integer, GroupRole> entry : roles) {
            if (entry.getValue() != wanted) {
                continue;
            }
            Set<Integer> cohort = groupTrackIndices.get(entry.getKey());
            if (cohort != null) {
                tracks.addAll(cohort);
            }
        }
        return tracks;
    }

    public Set<VcfVariantType> getAllowedTypes() { return allowedTypes; }
    public void setAllowedTypes(Set<VcfVariantType> allowedTypes) {
        if (allowedTypes == null || allowedTypes.isEmpty()) {
            this.allowedTypes = EnumSet.noneOf(VcfVariantType.class);
        } else {
            this.allowedTypes = EnumSet.copyOf(allowedTypes);
        }
    }

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

    public long getMinSvLengthBp() { return minSvLengthBp; }
    public void setMinSvLengthBp(long minSvLengthBp) {
        this.minSvLengthBp = Math.max(0, minSvLengthBp);
    }

    public long getMaxSvLengthBp() { return maxSvLengthBp; }
    public void setMaxSvLengthBp(long maxSvLengthBp) {
        this.maxSvLengthBp = maxSvLengthBp < 1 ? Long.MAX_VALUE : maxSvLengthBp;
    }

    public VariantFilter getPointSlice() { return pointSlice; }
    public VariantFilter getSvSlice() { return svSlice; }

    public boolean hasClassSlices() {
        return pointSlice != null && svSlice != null;
    }

    /**
     * Install class-scoped slices and sync parent {@link #allowedTypes} to their union.
     * Nested slices must not themselves carry class slices.
     */
    public void setClassSlices(VariantFilter point, VariantFilter sv) {
        this.pointSlice = point == null ? null : copySlice(point);
        this.svSlice = sv == null ? null : copySlice(sv);
        if (this.pointSlice != null || this.svSlice != null) {
            EnumSet<VcfVariantType> union = EnumSet.noneOf(VcfVariantType.class);
            if (this.pointSlice != null && this.pointSlice.allowedTypes != null) {
                union.addAll(this.pointSlice.allowedTypes);
            }
            if (this.svSlice != null && this.svSlice.allowedTypes != null) {
                union.addAll(this.svSlice.allowedTypes);
            }
            this.allowedTypes = union;
        }
    }

    /** Clear class slices; parent fields remain as-is. */
    public void clearClassSlices() {
        this.pointSlice = null;
        this.svSlice = null;
    }

    /**
     * Empty allowedTypes on a class slice means that class was never observed in the UI.
     * Expand those slices to the full class inventory so a newly added VCF of that class
     * is not filtered out at load time. Does not invent UI checkboxes / legends.
     */
    public void ensureUnobservedClassSlicesPassAll() {
        if (pointSlice == null && svSlice == null) {
            return;
        }
        boolean changed = false;
        if (pointSlice != null
            && (pointSlice.allowedTypes == null || pointSlice.allowedTypes.isEmpty())) {
            pointSlice.setAllowedTypes(VariantTypeVisuals.VariantClass.POINT.allTypes());
            changed = true;
        }
        if (svSlice != null
            && (svSlice.allowedTypes == null || svSlice.allowedTypes.isEmpty())) {
            svSlice.setAllowedTypes(VariantTypeVisuals.VariantClass.STRUCTURAL.allTypes());
            changed = true;
        }
        if (changed) {
            setClassSlices(pointSlice, svSlice);
        }
    }

    private static VariantFilter copySlice(VariantFilter source) {
        VariantFilter copy = source.copy();
        copy.pointSlice = null;
        copy.svSlice = null;
        return copy;
    }

    private VariantFilter classSlice(VcfVariantType type) {
        if (pointSlice == null && svSlice == null) {
            return this;
        }
        if (VariantTypeVisuals.isStructural(type)) {
            return svSlice != null ? svSlice : this;
        }
        return pointSlice != null ? pointSlice : this;
    }

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
        copy.groupLineageScope = new HashMap<>(this.groupLineageScope);
        copy.invalidateLineageCache();
        // EnumSet.copyOf throws on empty collections — keep noneOf for "exclude all".
        copy.allowedTypes = this.allowedTypes == null || this.allowedTypes.isEmpty()
            ? EnumSet.noneOf(VcfVariantType.class)
            : EnumSet.copyOf(this.allowedTypes);
        copy.allowedEffects = this.allowedEffects == null || this.allowedEffects.isEmpty()
            ? EnumSet.noneOf(VariantEffect.class)
            : EnumSet.copyOf(this.allowedEffects);
        copy.showCoding = this.showCoding;
        copy.showIntronic = this.showIntronic;
        copy.showIntergenic = this.showIntergenic;
        copy.infoFieldFilters = new HashMap<>(this.infoFieldFilters);
        copy.allowedFilterValues = new HashSet<>(this.allowedFilterValues);
        copy.filterFieldsActive = this.filterFieldsActive;
        copy.minSvLengthBp = this.minSvLengthBp;
        copy.maxSvLengthBp = this.maxSvLengthBp;
        copy.pointSlice = this.pointSlice == null ? null : copySlice(this.pointSlice);
        copy.svSlice = this.svSlice == null ? null : copySlice(this.svSlice);
        return copy;
    }

    /**
     * Filters that can be applied during VCF streaming before annotation is available.
     * Annotation-dependent dimensions (coding/intronic/intergenic, cancer-only, INFO/FILTER)
     * are intentionally deferred until after annotation/pruning.
     */
    public boolean passesLoadTime(VcfVariantType type, double siteQuality, VariantNode.SampleCall call) {
        return passesLoadTime(type, siteQuality, call, -1);
    }

    /**
     * @param svLengthBp known SV span length at load time, or {@code -1} if unknown
     */
    public boolean passesLoadTime(
            VcfVariantType type, double siteQuality, VariantNode.SampleCall call, long svLengthBp) {
        VariantFilter f = classSlice(type);
        if (!f.allowedTypes.contains(type)) return false;

        if (f.minQuality > 0) {
            if (siteQuality >= 0) {
                if (siteQuality < f.minQuality) return false;
            } else if (call != null && call.quality >= 0 && call.quality < f.minQuality) {
                return false;
            }
        }

        if (call != null) {
            if (f.minDepth > 0 && call.depth >= 0 && call.depth < f.minDepth) return false;
            // HomRef / AF=0: alt fraction is not meaningful — do not reject on min AF.
            boolean homRefLike = call.alleleFraction == 0
                || (call.gt != null && VariantNode.isHomRefGt(call.gt, null));
            if (!homRefLike && f.minAlleleFraction > 0 && call.alleleFraction >= 0
                && call.alleleFraction < f.minAlleleFraction) {
                return false;
            }
        }

        if (VariantTypeVisuals.isStructural(type) && !f.passesSvLength(svLengthBp)) {
            return false;
        }

        return true;
    }

    private boolean passesSvLength(long svLengthBp) {
        if (minSvLengthBp <= 0 && maxSvLengthBp == Long.MAX_VALUE) {
            return true;
        }
        if (svLengthBp < 0) {
            // Unknown span: only reject when a positive minimum is required.
            return minSvLengthBp <= 0;
        }
        if (minSvLengthBp > 0 && svLengthBp < minSvLengthBp) {
            return false;
        }
        if (maxSvLengthBp < Long.MAX_VALUE && svLengthBp > maxSvLengthBp) {
            return false;
        }
        return true;
    }

    /** Whether this filter needs annotation-aware post-load pruning. */
    public boolean requiresPostAnnotationFiltering() {
        if (hasClassSlices()) {
            return (pointSlice != null && pointSlice.requiresPostAnnotationFiltering())
                || (svSlice != null && svSlice.requiresPostAnnotationFiltering());
        }
        return cancerGenesOnly
            || !showCoding
            || !showIntronic
            || !showIntergenic
            || !infoFieldFilters.isEmpty()
            || filterFieldsActive
            || minSvLengthBp > 0
            || maxSvLengthBp < Long.MAX_VALUE;
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
            + "|minSvLen=" + minSvLengthBp
            + "|maxSvLen=" + (maxSvLengthBp == Long.MAX_VALUE ? "inf" : maxSvLengthBp)
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
            + "|filterValues=" + String.join(",", filterVals)
            + "|pointSlice=" + (pointSlice == null ? "-" : pointSlice.toStableKey())
            + "|svSlice=" + (svSlice == null ? "-" : svSlice.toStableKey());
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
        if (node != null && hasClassSlices()) {
            return classSlice(node.type).passesNodeLevel(node, aggregatedSampleTracks);
        }
        if (!passesBaseNodeLevel(node)) {
            return false;
        }
        if (geneLevel || hasComparisonWindow()) {
            if (aggregatedSampleTracks == null) {
                return passesSharedSampleCount(node) && passesGroupComparison(node);
            }
            // Genotype roles need per-call GT; set intersection cannot express them.
            if (hasActiveGenotypeGroupComparison()) {
                return passesSharedSampleCount(aggregatedSampleTracks)
                    && passesGroupComparison(node);
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
        VariantFilter f = classSlice(node.type);
        if (!f.allowedTypes.contains(node.type)) return false;

        if (f.minQuality > 0) {
            if (node.siteQuality >= 0 && node.siteQuality < f.minQuality) return false;
        }

        if (VariantTypeVisuals.isStructural(node.type)) {
            if (!f.passesSvLength(VariantTypeVisuals.svSpanLengthBp(node))) {
                return false;
            }
        }

        VariantAnnotation ann = node.annotation;
        if (ann != null) {
            if (f.cancerGenesOnly && !ann.isCancerGene()) return false;
            if (!f.allowedEffects.contains(ann.effect())) return false;
        } else {
            if (f.cancerGenesOnly) return false;
            if (!f.allowedEffects.contains(VariantEffect.INTERGENIC)) return false;
        }

        return true;
    }

    /**
     * Compare presence / zygosity across named groups using quality/depth/AF thresholds.
     * Roles are evaluated <b>per lineage</b> (root group + its subgroups). Each lineage's
     * internal comparison is independent: the site is kept if <b>any</b> involved lineage
     * passes (e.g. LOH in group B still shows when group A fails at the same site).
     * When every involved lineage fails, the site is kept only if an unconstrained sample
     * (ignore / outside those roles) still has a passing alt call.
     * A solitary heterozygous/homozygous/present role does not filter sites.
     * Within a lineage, {@link #presentMatchMode} applies; absent groups are always AND.
     * {@link GroupRole#HOMOZYGOUS}: ALT-only unless LOH mode (HETEROZYGOUS + HOMOZYGOUS
     * in the same lineage), then AA or BB.
     */
    public boolean passesGroupComparison(VariantNode node) {
        if (node == null) return false;
        if (!hasActiveGroupComparison()) {
            return true;
        }

        boolean anyLineageApplied = false;
        boolean anyLineagePassed = false;
        Set<Integer> allConstrainedTracks = new HashSet<>();

        Map<Integer, List<Map.Entry<Integer, GroupRole>>> rolesByLineage = rolesGroupedByLineage();
        for (Map.Entry<Integer, List<Map.Entry<Integer, GroupRole>>> lineageEntry
            : rolesByLineage.entrySet()) {
            List<Map.Entry<Integer, GroupRole>> lineageRoles = lineageEntry.getValue();
            if (lineageRoles.isEmpty() || !lineageHasSiteLevelComparison(lineageRoles)) {
                continue;
            }
            addConstrainedTracks(allConstrainedTracks, lineageRoles);
            if (!lineageHasAnyCall(node, lineageRoles)) {
                // Lineage not involved at this site — do not apply its constraints.
                continue;
            }
            anyLineageApplied = true;
            if (passesLineageGroupComparison(node, lineageRoles)) {
                anyLineagePassed = true;
            }
        }

        if (!anyLineageApplied) {
            return true;
        }
        if (anyLineagePassed) {
            return true;
        }
        // Every involved lineage failed — keep only for unconstrained samples.
        return hasPassingAltCallOutside(node, allConstrainedTracks);
    }

    private Map<Integer, List<Map.Entry<Integer, GroupRole>>> rolesGroupedByLineage() {
        ensureLineageCache();
        return cachedRolesByLineage;
    }

    private int lineageScopeOf(int groupId) {
        Integer scope = groupLineageScope.get(groupId);
        return scope != null ? scope : groupId;
    }

    private boolean lineageHasAnyCall(
        VariantNode node, List<Map.Entry<Integer, GroupRole>> lineageRoles) {
        for (Map.Entry<Integer, GroupRole> entry : lineageRoles) {
            Set<Integer> tracks = groupTrackIndices.get(entry.getKey());
            if (tracks == null || tracks.isEmpty()) {
                continue;
            }
            for (Integer trackIndex : tracks) {
                if (trackIndex != null && node.getSampleCall(trackIndex) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean passesLineageGroupComparison(
        VariantNode node, List<Map.Entry<Integer, GroupRole>> lineageRoles) {
        // A solitary present/genotype role is not a within-group comparison — do not
        // drop sites for other samples. Sample-row display still enforces the role.
        if (!lineageHasSiteLevelComparison(lineageRoles)) {
            return true;
        }

        boolean anyPresentMatched = false;
        boolean anyPresentConfigured = false;
        boolean lohMode = isLohModeForRoles(lineageRoles);

        for (Map.Entry<Integer, GroupRole> entry : lineageRoles) {
            GroupRole role = entry.getValue();
            Set<Integer> tracks = groupTrackIndices.get(entry.getKey());

            if (role == GroupRole.ABSENT) {
                if (hasPassingAltCallInCohort(node, tracks)) {
                    return false;
                }
                continue;
            }

            anyPresentConfigured = true;
            boolean matched = switch (role) {
                case HETEROZYGOUS -> hasPassingZygosityInCohort(node, tracks, Zygosity.HET);
                case HOMOZYGOUS -> lohMode
                    ? hasPassingZygosityInCohort(node, tracks, Zygosity.HOM_ANY)
                    : hasPassingZygosityInCohort(node, tracks, Zygosity.HOM_ALT);
                default -> hasPassingAltCallInCohort(node, tracks)
                    || (lohMode && hasPassingZygosityInCohort(node, tracks, Zygosity.HOM_REF));
            };

            if (presentMatchMode == PresentMatchMode.ALL) {
                if (!matched) {
                    return false;
                }
            } else if (matched) {
                anyPresentMatched = true;
            }
        }

        if (anyPresentConfigured && presentMatchMode == PresentMatchMode.ANY) {
            return anyPresentMatched;
        }
        return true;
    }

    /**
     * Site-level filtering runs only when the lineage has a real comparison:
     * an {@link GroupRole#ABSENT} constraint, or at least two present/genotype roles
     * (e.g. parental het + offspring present/hom). A single "must be heterozygous"
     * alone must not hide other groups' or ignored samples' variants.
     */
    private static boolean lineageHasSiteLevelComparison(
        List<Map.Entry<Integer, GroupRole>> lineageRoles) {
        int presentTypeCount = 0;
        boolean hasAbsent = false;
        for (Map.Entry<Integer, GroupRole> entry : lineageRoles) {
            GroupRole role = entry.getValue();
            if (role == null || role == GroupRole.IGNORE) {
                continue;
            }
            if (role == GroupRole.ABSENT) {
                hasAbsent = true;
            } else {
                presentTypeCount++;
            }
        }
        return hasAbsent || presentTypeCount >= 2;
    }

    private static boolean isLohModeForRoles(List<Map.Entry<Integer, GroupRole>> roles) {
        boolean hasHeterozygous = false;
        boolean hasHomozygous = false;
        for (Map.Entry<Integer, GroupRole> entry : roles) {
            GroupRole role = entry.getValue();
            if (role == GroupRole.HETEROZYGOUS) {
                hasHeterozygous = true;
            } else if (role == GroupRole.HOMOZYGOUS) {
                hasHomozygous = true;
            }
        }
        return hasHeterozygous && hasHomozygous;
    }

    /**
     * Group comparison against a precomputed set of mutated sample track indices (gene level).
     * Presence/absence only — genotype roles cannot be evaluated from track sets alone;
     * callers should use {@link #passesGroupComparison(VariantNode)} when genotype roles are active.
     */
    public boolean passesGroupComparison(Set<Integer> sampleTrackIndices) {
        if (!hasActiveGroupComparison()) {
            return true;
        }
        // Fail closed: set-based path cannot verify het/hom/LOH.
        if (hasActiveGenotypeGroupComparison()) {
            return false;
        }
        Set<Integer> samples = sampleTrackIndices != null ? sampleTrackIndices : Set.of();

        boolean anyLineageApplied = false;
        boolean anyLineagePassed = false;
        Set<Integer> allConstrainedTracks = new HashSet<>();

        Map<Integer, List<Map.Entry<Integer, GroupRole>>> rolesByLineage = rolesGroupedByLineage();
        for (Map.Entry<Integer, List<Map.Entry<Integer, GroupRole>>> lineageEntry
            : rolesByLineage.entrySet()) {
            List<Map.Entry<Integer, GroupRole>> lineageRoles = lineageEntry.getValue();
            if (lineageRoles.isEmpty() || !lineageHasSiteLevelComparison(lineageRoles)) {
                continue;
            }
            addConstrainedTracks(allConstrainedTracks, lineageRoles);
            if (!lineageIntersectsSamples(samples, lineageRoles)) {
                continue;
            }
            anyLineageApplied = true;
            if (passesLineageGroupComparison(samples, lineageRoles)) {
                anyLineagePassed = true;
            }
        }

        if (!anyLineageApplied) {
            return true;
        }
        if (anyLineagePassed) {
            return true;
        }
        return hasSampleOutside(samples, allConstrainedTracks);
    }

    private void addConstrainedTracks(
        Set<Integer> into, List<Map.Entry<Integer, GroupRole>> lineageRoles) {
        for (Map.Entry<Integer, GroupRole> entry : lineageRoles) {
            Set<Integer> tracks = groupTrackIndices.get(entry.getKey());
            if (tracks != null) {
                into.addAll(tracks);
            }
        }
    }

    private boolean hasPassingAltCallOutside(VariantNode node, Set<Integer> excludedTracks) {
        if (node == null || excludedTracks == null) {
            return false;
        }
        for (VariantNode.SampleCall call : node.getSamples()) {
            if (call == null) {
                continue;
            }
            int trackIndex = call.getTrackIndex();
            if (excludedTracks.contains(trackIndex)) {
                continue;
            }
            if (!passesSampleThresholds(node, call)) {
                continue;
            }
            if (node.isAltCarrier(call)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSampleOutside(Set<Integer> samples, Set<Integer> excludedTracks) {
        if (samples == null || samples.isEmpty()) {
            return false;
        }
        for (Integer trackIndex : samples) {
            if (trackIndex != null && (excludedTracks == null || !excludedTracks.contains(trackIndex))) {
                return true;
            }
        }
        return false;
    }

    private boolean lineageIntersectsSamples(
        Set<Integer> samples, List<Map.Entry<Integer, GroupRole>> lineageRoles) {
        for (Map.Entry<Integer, GroupRole> entry : lineageRoles) {
            if (intersects(samples, groupTrackIndices.get(entry.getKey()))) {
                return true;
            }
        }
        return false;
    }

    private boolean passesLineageGroupComparison(
        Set<Integer> samples, List<Map.Entry<Integer, GroupRole>> lineageRoles) {
        if (!lineageHasSiteLevelComparison(lineageRoles)) {
            return true;
        }

        boolean anyPresentMatched = false;
        boolean anyPresentConfigured = false;

        for (Map.Entry<Integer, GroupRole> entry : lineageRoles) {
            GroupRole role = entry.getValue();
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

    private enum Zygosity { HET, HOM_ALT, HOM_REF, HOM_ANY }

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

    /** Alt-carrier (het / hom-alt) presence — HomRef does not count as mutated. */
    private boolean hasPassingAltCallInCohort(VariantNode node, Set<Integer> trackIndices) {
        if (trackIndices == null || trackIndices.isEmpty()) {
            return false;
        }
        for (VariantNode.SampleCall call : node.getSamples()) {
            if (call == null) continue;
            int trackIndex = call.getTrackIndex();
            if (!trackIndices.contains(trackIndex)) continue;
            if (!passesSampleThresholds(node, call)) continue;
            if (node.isAltCarrier(call)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPassingZygosityInCohort(
            VariantNode node, Set<Integer> trackIndices, Zygosity zygosity) {
        if (trackIndices == null || trackIndices.isEmpty()) {
            return false;
        }
        for (VariantNode.SampleCall call : node.getSamples()) {
            if (call == null) continue;
            int trackIndex = call.getTrackIndex();
            if (!trackIndices.contains(trackIndex)) continue;
            if (!passesSampleThresholds(node, call)) continue;
            boolean match = switch (zygosity) {
                case HET -> node.isHeterozygous(call);
                case HOM_ALT -> node.isHomozygousAlt(call);
                case HOM_REF -> node.isHomozygousRef(call);
                case HOM_ANY -> node.isHomozygousRef(call) || node.isHomozygousAlt(call);
            };
            if (match) {
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

    /**
     * Count alt-carrier sample calls that pass per-sample thresholds.
     * HomRef (AA) does not inflate shared-sample counts.
     */
    public int countPassingSamples(VariantNode node) {
        if (node == null) return 0;
        int count = 0;
        for (VariantNode.SampleCall call : node.getSamples()) {
            if (passesSampleThresholds(node, call) && node.isAltCarrier(call)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Per-call quality/depth/AF and UI visibility only — not group comparison roles.
     * Use {@link #passesSampleDisplay} for table rows and sample-canvas alleles.
     */
    public boolean passesSampleThresholds(VariantNode node, VariantNode.SampleCall call) {
        if (node == null || call == null) return false;
        if (!call.isUiVisible()) return false;

        VariantFilter f = classSlice(node.type);
        if (f.minQuality > 0 && node.siteQuality < 0 && call.quality >= 0 && call.quality < f.minQuality) {
            return false;
        }
        if (f.minDepth > 0 && call.depth >= 0 && call.depth < f.minDepth) return false;
        // HomRef AF is alt fraction (~0); do not reject AA on min AF.
        if (!node.isHomozygousRef(call)
            && f.minAlleleFraction > 0
            && call.alleleFraction >= 0
            && call.alleleFraction < f.minAlleleFraction) {
            return false;
        }

        return true;
    }

    /**
     * Thresholds plus group-role genotype/presence constraints for table display.
     * HomRef rows are shown only for homozygous-role tracks in a lineage that is in LOH mode.
     */
    public boolean passesSampleDisplay(VariantNode node, VariantNode.SampleCall call) {
        if (node != null && hasClassSlices()) {
            return classSlice(node.type).passesSampleDisplay(node, call);
        }
        if (!passesSampleThresholds(node, call)) {
            return false;
        }
        if (node.isHomozygousRef(call) && !isLohHomRefTrack(call.getTrackIndex())) {
            return false;
        }
        return passesSampleGroupConstraint(node, call, null);
    }

    /**
     * Build track→call map of display-eligible alleles for canvas/density.
     * Evaluates lineage failures once per node. Used when rebuilding the visible chain.
     */
    public IdentityHashMap<SampleTrack, VariantNode.SampleCall> buildDisplayByTrack(VariantNode node) {
        if (node != null && hasClassSlices()) {
            return classSlice(node.type).buildDisplayByTrack(node);
        }
        IdentityHashMap<SampleTrack, VariantNode.SampleCall> map = new IdentityHashMap<>();
        if (node == null) {
            return map;
        }
        Set<Integer> failedLineages =
            hasActiveGroupComparison() ? failedLineagesForNode(node) : Set.of();
        for (VariantNode.SampleCall call : node.getSamples()) {
            if (call == null || call.getTrack() == null) {
                continue;
            }
            if (!passesSampleThresholds(node, call)) {
                continue;
            }
            if (node.isHomozygousRef(call) && !isLohHomRefTrack(call.getTrackIndex())) {
                continue;
            }
            if (!passesSampleGroupConstraint(node, call, failedLineages)) {
                continue;
            }
            map.put(call.getTrack(), call);
        }
        return map;
    }

    /**
     * Whether this call is consistent with active group comparison roles for its track.
     * No-op (passes) when group comparison is inactive.
     *
     * @param failedLineages precomputed {@link #failedLineagesForNode} or null to compute
     */
    public boolean passesSampleGroupConstraint(VariantNode node, VariantNode.SampleCall call) {
        return passesSampleGroupConstraint(node, call, null);
    }

    private boolean passesSampleGroupConstraint(
        VariantNode node, VariantNode.SampleCall call, Set<Integer> failedLineages) {
        if (node != null && hasClassSlices()) {
            return classSlice(node.type).passesSampleGroupConstraint(node, call, failedLineages);
        }
        if (node == null || call == null) {
            return false;
        }
        if (!hasActiveGroupComparison()) {
            return true;
        }

        int trackIndex = call.getTrackIndex();
        if (trackIndex < 0) {
            return true;
        }

        Set<Integer> failed = failedLineages != null
            ? failedLineages
            : failedLineagesForNode(node);
        ensureLineageCache();
        Integer lineageId = cachedTrackToLineage.get(trackIndex);
        if (lineageId != null && failed.contains(lineageId)) {
            return false;
        }

        for (Map.Entry<Integer, GroupRole> entry : groupRoles.entrySet()) {
            GroupRole role = entry.getValue();
            if (role == null || role == GroupRole.IGNORE || role == GroupRole.ABSENT) {
                continue;
            }
            Set<Integer> cohort = groupTrackIndices.get(entry.getKey());
            if (cohort == null || !cohort.contains(trackIndex)) {
                continue;
            }
            boolean lohMode = isLohModeForLineage(lineageScopeOf(entry.getKey()));
            if (role == GroupRole.HETEROZYGOUS) {
                if (lohMode) {
                    return false;
                }
                if (!node.isHeterozygous(call)) {
                    return false;
                }
            }
            if (role == GroupRole.HOMOZYGOUS) {
                boolean ok = lohMode
                    ? (node.isHomozygousRef(call) || node.isHomozygousAlt(call))
                    : node.isHomozygousAlt(call);
                if (!ok) {
                    return false;
                }
            }
        }

        return true;
    }

    /** True when {@code trackIndex} is in a HOMOZYGOUS cohort of a lineage in LOH mode. */
    private boolean isLohHomRefTrack(int trackIndex) {
        if (trackIndex < 0) {
            return false;
        }
        ensureLineageCache();
        return cachedLohHomTrackIndices.contains(trackIndex);
    }

    /**
     * Lineage ids whose site-level comparison fails on {@code node}.
     * Computed once per node when building display caches or checking many calls.
     */
    public Set<Integer> failedLineagesForNode(VariantNode node) {
        if (node != null && hasClassSlices()) {
            return classSlice(node.type).failedLineagesForNode(node);
        }
        Set<Integer> failed = new HashSet<>();
        if (node == null || !hasActiveGroupComparison()) {
            return failed;
        }
        Map<Integer, List<Map.Entry<Integer, GroupRole>>> rolesByLineage = rolesGroupedByLineage();
        for (Map.Entry<Integer, List<Map.Entry<Integer, GroupRole>>> lineageEntry
            : rolesByLineage.entrySet()) {
            List<Map.Entry<Integer, GroupRole>> lineageRoles = lineageEntry.getValue();
            if (!lineageHasSiteLevelComparison(lineageRoles)) {
                continue;
            }
            if (!lineageHasAnyCall(node, lineageRoles)) {
                continue;
            }
            if (!passesLineageGroupComparison(node, lineageRoles)) {
                failed.add(lineageEntry.getKey());
            }
        }
        return failed;
    }

    /** Count calls that pass {@link #passesSampleDisplay} (table / comparison-aware). */
    public int countDisplaySamples(VariantNode node) {
        if (node == null) return 0;
        if (hasClassSlices()) {
            return classSlice(node.type).countDisplaySamples(node);
        }
        int count = 0;
        for (VariantNode.SampleCall call : node.getSamples()) {
            if (passesSampleDisplay(node, call)) {
                count++;
            }
        }
        return count;
    }

    public boolean passes(VariantNode node, VariantNode.SampleCall call) {
        return passesNodeLevel(node) && passesSampleDisplay(node, call);
    }

    /**
     * Post-annotation cache retention: type / quality / effect / cancer / DP / AF only.
     * Does <b>not</b> apply gene-level, comparison-window, or group shared-sample gates —
     * those are display-time filters and must not delete alleles from the loaded cache.
     */
    public boolean passesCacheRetention(VariantNode node) {
        if (node == null) {
            return false;
        }
        if (!passesBaseNodeLevel(node)) {
            return false;
        }
        for (VariantNode.SampleCall call : node.getSamples()) {
            if (passesSampleThresholds(node, call)) {
                return true;
            }
        }
        return false;
    }

    /** Returns true if all filters are at default (pass-all) state. */
    public boolean isPassAll() {
        if (hasClassSlices()) {
            return (pointSlice == null || pointSlice.isPassAll())
                && (svSlice == null || svSlice.isPassAll())
                && minSharedSamples <= 1
                && maxSharedSamples == Integer.MAX_VALUE
                && !geneLevel
                && comparisonWindowBp <= 0
                && !hasActiveGroupComparison();
        }
        return minQuality == 0.0
            && minDepth == 0
            && minAlleleFraction == 0.0
            && minSvLengthBp <= 0
            && maxSvLengthBp == Long.MAX_VALUE
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

package org.baseplayer.variant;

import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTag;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;

import java.util.EnumMap;
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
    /** Inclusive upper bound on allele fraction; {@code 1.0} means no ceiling. */
    private double maxAlleleFraction = 1.0;
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
    /**
     * Explicit LOH region merge gap in bp. When {@code > 0}, used by
     * {@link #lohRegionGapBp()} instead of {@link #comparisonWindowBp}, so setting a
     * LOH gap does not turn on soft-match cluster indexing.
     */
    private int lohRegionGapOverrideBp = 0;
    /**
     * Roles keyed by fixed {@link SampleTag}. Expanded into per-group cohorts
     * ({@link #groupRoles} / {@link #groupTrackIndices}) at apply / rebuild time
     * so each family group is its own lineage.
     */
    private Map<SampleTag, GroupRole> tagRoles = new EnumMap<>(SampleTag.class);
    /** Expanded cohort roles (synthetic keys from {@link #tagCohortKey}). */
    private Map<Integer, GroupRole> groupRoles = new HashMap<>();
    /** Resolved track indices per expanded cohort key. */
    private Map<Integer, Set<Integer>> groupTrackIndices = new HashMap<>();
    /**
     * Maps each expanded cohort key to its lineage scope (flat group id, or
     * {@link #UNGROUPED_COHORT_ID}). Roles are evaluated per lineage so one
     * family's constraints do not hide another's variants.
     */
    private Map<Integer, Integer> groupLineageScope = new HashMap<>();

    /** Lazy caches invalidated when group comparison inputs change. */
    private boolean lineageCacheValid = false;
    private Map<Integer, List<Map.Entry<Integer, GroupRole>>> cachedRolesByLineage = Map.of();
    private Set<Integer> cachedLohHomTrackIndices = Set.of();
    private Set<Integer> cachedLohHetTrackIndices = Set.of();
    private Map<Integer, Integer> cachedTrackToLineage = Map.of();
    private Boolean cachedHasActiveGroupComparison;
    private Boolean cachedHasActiveGenotypeComparison;
    private Boolean cachedIsLohModeLocal;

    /** Pass-all excludes synthetic LOH AA/BB (built at compare time, not VCF-loaded). */
    private Set<VcfVariantType> allowedTypes = passAllLoadedTypes();
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

    public double getMaxAlleleFraction() { return maxAlleleFraction; }
    public void setMaxAlleleFraction(double maxAlleleFraction) {
        this.maxAlleleFraction = maxAlleleFraction < 0 ? 1.0 : Math.min(1.0, maxAlleleFraction);
    }

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

    /** Explicit LOH merge gap; {@code 0} falls back to comparison window / default. */
    public int getLohRegionGapOverrideBp() {
        return lohRegionGapOverrideBp;
    }

    public void setLohRegionGapOverrideBp(int lohRegionGapOverrideBp) {
        this.lohRegionGapOverrideBp = Math.max(0, lohRegionGapOverrideBp);
    }

    public boolean hasComparisonWindow() {
        if (hasClassSlices()) {
            return (pointSlice != null && !pointSlice.geneLevel && pointSlice.comparisonWindowBp > 0)
                || (svSlice != null && !svSlice.geneLevel && svSlice.comparisonWindowBp > 0);
        }
        return !geneLevel && comparisonWindowBp > 0;
    }

    public Map<SampleTag, GroupRole> getTagRoles() {
        return tagRoles;
    }

    /** Preferred API: fixed-tag roles, expanded per sample group into lineages. */
    public void setTagRoles(Map<SampleTag, GroupRole> tagRoles) {
        this.tagRoles = new EnumMap<>(SampleTag.class);
        if (tagRoles != null) {
            for (Map.Entry<SampleTag, GroupRole> entry : tagRoles.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null
                        && entry.getValue() != GroupRole.IGNORE) {
                    this.tagRoles.put(entry.getKey(), entry.getValue());
                }
            }
        }
        rebuildTagCohortsFromRegistry();
    }

    /**
     * Legacy / expanded cohort roles. Prefer {@link #setTagRoles(Map)}.
     * Still used when restoring old docs or after cohort expansion.
     */
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

    /**
     * Expand {@link #tagRoles} into per-group cohorts so each family is a lineage
     * and each tag is a role cohort within that lineage.
     */
    public void rebuildTagCohortsFromRegistry() {
        groupRoles.clear();
        groupTrackIndices.clear();
        groupLineageScope.clear();
        if (tagRoles.isEmpty()) {
            invalidateLineageCache();
            return;
        }
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        List<SampleTrack> tracks = registry.getSampleTracks();
        for (SampleGroup group : registry.getSampleGroups()) {
            if (group == null) {
                continue;
            }
            int lineageId = group.getId();
            for (Map.Entry<SampleTag, GroupRole> entry : tagRoles.entrySet()) {
                SampleTag tag = entry.getKey();
                GroupRole role = entry.getValue();
                Set<Integer> cohort = new HashSet<>();
                for (int i = 0; i < tracks.size(); i++) {
                    SampleTrack track = tracks.get(i);
                    if (track != null && track.isInGroup(lineageId) && track.hasTag(tag)) {
                        cohort.add(i);
                    }
                }
                int key = tagCohortKey(lineageId, tag);
                groupRoles.put(key, role);
                groupTrackIndices.put(key, cohort);
                groupLineageScope.put(key, lineageId);
            }
        }
        // Ungrouped tagged tracks: one lineage.
        boolean anyUngroupedTagged = false;
        for (SampleTrack track : tracks) {
            if (track != null && !track.hasGroup() && track.hasAnyTag()) {
                anyUngroupedTagged = true;
                break;
            }
        }
        if (anyUngroupedTagged) {
            for (Map.Entry<SampleTag, GroupRole> entry : tagRoles.entrySet()) {
                SampleTag tag = entry.getKey();
                GroupRole role = entry.getValue();
                Set<Integer> cohort = new HashSet<>();
                for (int i = 0; i < tracks.size(); i++) {
                    SampleTrack track = tracks.get(i);
                    if (track != null && !track.hasGroup() && track.hasTag(tag)) {
                        cohort.add(i);
                    }
                }
                int key = tagCohortKey(UNGROUPED_COHORT_ID, tag);
                groupRoles.put(key, role);
                groupTrackIndices.put(key, cohort);
                groupLineageScope.put(key, UNGROUPED_COHORT_ID);
            }
        }
        invalidateLineageCache();
    }

    /** Synthetic cohort id for (groupId, tag) — stable for session restore expansion. */
    public static int tagCohortKey(int groupId, SampleTag tag) {
        int ordinal = tag != null ? tag.ordinal() : 0;
        if (groupId == UNGROUPED_COHORT_ID) {
            return -100 - ordinal;
        }
        return groupId * 16 + ordinal;
    }

    private void invalidateLineageCache() {
        lineageCacheValid = false;
        cachedRolesByLineage = Map.of();
        cachedLohHomTrackIndices = Set.of();
        cachedLohHetTrackIndices = Set.of();
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
        Set<Integer> lohHetTracks = new HashSet<>();
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
                    GroupRole role = entry.getValue();
                    if (role != GroupRole.HOMOZYGOUS && role != GroupRole.HETEROZYGOUS) {
                        continue;
                    }
                    Set<Integer> tracks = groupTrackIndices.get(entry.getKey());
                    if (tracks == null) {
                        continue;
                    }
                    if (role == GroupRole.HOMOZYGOUS) {
                        lohHomTracks.addAll(tracks);
                    } else {
                        lohHetTracks.addAll(tracks);
                    }
                }
            }
        }

        cachedRolesByLineage = byLineage;
        cachedTrackToLineage = trackToLineage;
        cachedLohHomTrackIndices = lohHomTracks;
        cachedLohHetTrackIndices = lohHetTracks;
        cachedHasActiveGroupComparison = anyActive;
        cachedHasActiveGenotypeComparison = anyGenotype;
        cachedIsLohModeLocal = !lohLineages.isEmpty();
        lineageCacheValid = true;
    }

    public boolean hasActiveGroupComparison() {
        if (hasClassSlices()) {
            return (pointSlice != null && pointSlice.hasActiveGroupComparison())
                || (svSlice != null && svSlice.hasActiveGroupComparison());
        }
        ensureLineageCache();
        return Boolean.TRUE.equals(cachedHasActiveGroupComparison);
    }

    /** True when any group requires a genotype (het / hom), not just presence. */
    public boolean hasActiveGenotypeGroupComparison() {
        // Merged filters keep roles on point/SV slices — check those like isLohMode().
        if (hasClassSlices()) {
            return (pointSlice != null && pointSlice.hasActiveGenotypeGroupComparison())
                || (svSlice != null && svSlice.hasActiveGenotypeGroupComparison());
        }
        ensureLineageCache();
        return Boolean.TRUE.equals(cachedHasActiveGenotypeComparison);
    }

    /**
     * Roles configured for on-demand Calculate LOH (Parental/Marker HET + Child HOM).
     * Does <em>not</em> change normal filter semantics — only enables Calculate and
     * gap settings. Region calling lives in {@link LohRegionBuilder}.
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

    /**
     * Legacy no-op. Missing child genotypes are implied AA only inside
     * {@link LohRegionBuilder} when Calculate LOH runs — never on marker nodes
     * during normal filtering.
     *
     * @return always {@code 0}
     */
    public int addMissingLohAaCalls(VariantNode node) {
        return 0;
    }

    /**
     * Implied AA ({@code 0/0}) for a LOH region sample when Calculate finds a
     * missing child genotype at a parental-het site. Not used in normal filtering.
     */
    public static VariantNode.SampleCall impliedLohAaCall(int trackIndex) {
        return new VariantNode.SampleCall(trackIndex, "0/0", -1, -1, 0.0);
    }

    /**
     * Implied AA using the site REF when available ({@code A/A}), else {@code 0/0}.
     */
    public static VariantNode.SampleCall impliedLohAaCall(VariantNode node, int trackIndex) {
        if (node != null && node.ref != null && !node.ref.isBlank()) {
            String gt = node.ref + "/" + node.ref;
            return new VariantNode.SampleCall(trackIndex, gt, -1, -1, 0.0);
        }
        return impliedLohAaCall(trackIndex);
    }

    /**
     * Display-eligible calls for tables / export: sample calls that pass
     * display thresholds + group roles. No implied AA — that exists only on
     * calculated LOH region nodes. Lineage failures are evaluated once per node.
     */
    public List<VariantNode.SampleCall> listDisplayCalls(VariantNode node) {
        List<VariantNode.SampleCall> out = new ArrayList<>();
        forEachDisplayCall(node, out::add);
        return out;
    }

    /**
     * Visit each display-eligible call once (one lineage-failure pass).
     *
     * @return number of display-eligible calls
     */
    public int forEachDisplayCall(
            VariantNode node, java.util.function.Consumer<VariantNode.SampleCall> consumer) {
        if (node == null || consumer == null) {
            return 0;
        }
        if (hasClassSlices() && !VariantTypeVisuals.isLohRegion(node.type)) {
            return classSlice(node.type).forEachDisplayCall(node, consumer);
        }
        if (VariantTypeVisuals.isLohRegion(node.type)) {
            int[] n = { 0 };
            node.forEachSample(call -> {
                if (call != null && passesSampleThresholds(node, call)) {
                    consumer.accept(call);
                    n[0]++;
                }
            });
            return n[0];
        }
        Set<Integer> failed = hasActiveGroupComparison()
            ? failedLineagesForNode(node)
            : Set.of();
        int[] n = { 0 };
        node.forEachSample(call -> {
            if (call == null) {
                return;
            }
            if (!passesSampleThresholds(node, call)) {
                return;
            }
            if (node.isHomozygousRef(call)) {
                return;
            }
            if (!passesSampleGroupConstraint(node, call, failed)) {
                return;
            }
            consumer.accept(call);
            n[0]++;
        });
        return n[0];
    }

    /**
     * Short cohort summary for loading UI, e.g. {@code het: Parental(12) · hom: Child(14)}.
     */
    public String comparisonCohortSummary() {
        if (hasClassSlices()) {
            String point = pointSlice != null ? pointSlice.comparisonCohortSummaryLocal() : "";
            String sv = svSlice != null ? svSlice.comparisonCohortSummaryLocal() : "";
            if (!point.isEmpty()) {
                return point;
            }
            return sv;
        }
        return comparisonCohortSummaryLocal();
    }

    private String comparisonCohortSummaryLocal() {
        if (tagRoles == null || tagRoles.isEmpty()) {
            return "";
        }
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        List<String> het = new ArrayList<>();
        List<String> hom = new ArrayList<>();
        List<String> present = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        for (Map.Entry<SampleTag, GroupRole> entry : tagRoles.entrySet()) {
            SampleTag tag = entry.getKey();
            GroupRole role = entry.getValue();
            if (tag == null || role == null || role == GroupRole.IGNORE) {
                continue;
            }
            int count = 0;
            for (SampleTrack track : registry.getSampleTracks()) {
                if (track != null && track.hasTag(tag)) {
                    count++;
                }
            }
            String label = tag.displayName() + "(" + count + ")";
            switch (role) {
                case HETEROZYGOUS -> het.add(label);
                case HOMOZYGOUS -> hom.add(label);
                case PRESENT -> present.add(label);
                case ABSENT -> absent.add(label);
                default -> { }
            }
        }
        List<String> parts = new ArrayList<>();
        if (!het.isEmpty()) {
            parts.add("het: " + String.join(", ", het));
        }
        if (!hom.isEmpty()) {
            parts.add("hom: " + String.join(", ", hom));
        }
        if (!present.isEmpty()) {
            parts.add("present: " + String.join(", ", present));
        }
        if (!absent.isEmpty()) {
            parts.add("absent: " + String.join(", ", absent));
        }
        return String.join(" · ", parts);
    }

    public Set<VcfVariantType> getAllowedTypes() { return allowedTypes; }
    public void setAllowedTypes(Set<VcfVariantType> allowedTypes) {
        if (allowedTypes == null || allowedTypes.isEmpty()) {
            this.allowedTypes = EnumSet.noneOf(VcfVariantType.class);
        } else {
            this.allowedTypes = EnumSet.copyOf(allowedTypes);
        }
    }

    /** Whether {@code type} is in the active class slice (or parent) allowed set. */
    public boolean allowsType(VcfVariantType type) {
        if (type == null) {
            return false;
        }
        VariantFilter f = classSlice(type);
        return f.allowedTypes != null && f.allowedTypes.contains(type);
    }

    /**
     * Add a newly seen type to the matching class slice / parent allowed set.
     * Used at load time so first-time types are not dropped when other types in the
     * same class already drive the filter.
     */
    public void admitType(VcfVariantType type) {
        if (type == null || VariantTypeVisuals.isLohRegion(type)) {
            return;
        }
        if (hasClassSlices()) {
            boolean structural = VariantTypeVisuals.isStructural(type);
            VariantFilter slice = structural ? svSlice : pointSlice;
            if (slice == null) {
                return;
            }
            EnumSet<VcfVariantType> next = slice.allowedTypes == null || slice.allowedTypes.isEmpty()
                ? EnumSet.noneOf(VcfVariantType.class)
                : EnumSet.copyOf(slice.allowedTypes);
            if (!next.add(type)) {
                return;
            }
            slice.setAllowedTypes(next);
            setClassSlices(pointSlice, svSlice);
            return;
        }
        EnumSet<VcfVariantType> next = allowedTypes == null || allowedTypes.isEmpty()
            ? EnumSet.noneOf(VcfVariantType.class)
            : EnumSet.copyOf(allowedTypes);
        if (next.add(type)) {
            setAllowedTypes(next);
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
        copy.maxAlleleFraction = this.maxAlleleFraction;
        copy.cancerGenesOnly = this.cancerGenesOnly;
        copy.minSharedSamples = this.minSharedSamples;
        copy.maxSharedSamples = this.maxSharedSamples;
        copy.presentMatchMode = this.presentMatchMode;
        copy.geneLevel = this.geneLevel;
        copy.comparisonWindowBp = this.comparisonWindowBp;
        copy.lohRegionGapOverrideBp = this.lohRegionGapOverrideBp;
        copy.tagRoles = this.tagRoles.isEmpty()
            ? new EnumMap<>(SampleTag.class)
            : new EnumMap<>(this.tagRoles);
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
            // HomRef / AF=0: alt fraction is not meaningful — do not reject on AF bounds.
            boolean homRefLike = call.alleleFraction == 0
                || (call.gt != null && VariantNode.isHomRefGt(call.gt, null));
            if (!homRefLike && call.alleleFraction >= 0) {
                if (f.minAlleleFraction > 0 && call.alleleFraction < f.minAlleleFraction) {
                    return false;
                }
                if (f.maxAlleleFraction < 1.0 && call.alleleFraction > f.maxAlleleFraction) {
                    return false;
                }
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
            + "|maxAF=" + maxAlleleFraction
            + "|minSvLen=" + minSvLengthBp
            + "|maxSvLen=" + (maxSvLengthBp == Long.MAX_VALUE ? "inf" : maxSvLengthBp)
            + "|cancerOnly=" + cancerGenesOnly
            + "|minShare=" + minSharedSamples
            + "|maxShare=" + maxSharedSamples
            + "|geneLevel=" + geneLevel
            + "|cmpWindow=" + comparisonWindowBp
            + "|lohGap=" + lohRegionGapOverrideBp
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
     * Roles are evaluated <b>per lineage</b> (each sample group, or ungrouped tagged
     * tracks). Each lineage's internal comparison is independent: the site is kept if
     * <b>any</b> involved lineage passes (e.g. LOH in group B still shows when group A
     * fails at the same site).
     * When every involved lineage fails, the site is kept only if an unconstrained sample
     * (ignore / outside those roles) still has a passing alt call.
     * A solitary {@link GroupRole#PRESENT} role does not filter sites; heterozygous /
     * homozygous roles prune sites like other filters (same as AF/GQ).
     * Within a lineage, {@link #presentMatchMode} applies; absent groups are always AND.
     * {@link GroupRole#HOMOZYGOUS} always means hom-alt (no LOH implied-AA on this path).
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
        // Solitary PRESENT is not site-level; HET/HOM/ABSENT/multi-role are.
        if (!lineageHasSiteLevelComparison(lineageRoles)) {
            return true;
        }

        boolean anyPresentMatched = false;
        boolean anyPresentConfigured = false;

        for (Map.Entry<Integer, GroupRole> entry : lineageRoles) {
            GroupRole role = entry.getValue();
            Set<Integer> tracks = groupTrackIndices.get(entry.getKey());
            // Empty cohorts (e.g. Marker role with no Marker tags) are ignored —
            // they must not fail ALL matching.
            if (tracks == null || tracks.isEmpty()) {
                continue;
            }

            if (role == GroupRole.ABSENT) {
                if (hasPassingAltCallInCohort(node, tracks)) {
                    return false;
                }
                continue;
            }

            anyPresentConfigured = true;
            // Basic genotype roles only — no LOH implied-AA / HomRef specials here.
            boolean matched = switch (role) {
                case HETEROZYGOUS -> hasPassingZygosityInCohort(node, tracks, Zygosity.HET);
                case HOMOZYGOUS -> hasPassingZygosityInCohort(node, tracks, Zygosity.HOM_ALT);
                default -> hasPassingAltCallInCohort(node, tracks);
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
     * Site-level filtering when the lineage has a real comparison:
     * {@link GroupRole#ABSENT}, any heterozygous/homozygous role (prunes like AF/GQ),
     * or at least two present-type roles (e.g. parental + offspring present).
     * A solitary {@link GroupRole#PRESENT} alone does not hide other samples' sites.
     */
    private static boolean lineageHasSiteLevelComparison(
        List<Map.Entry<Integer, GroupRole>> lineageRoles) {
        int presentTypeCount = 0;
        boolean hasAbsent = false;
        boolean hasGenotype = false;
        for (Map.Entry<Integer, GroupRole> entry : lineageRoles) {
            GroupRole role = entry.getValue();
            if (role == null || role == GroupRole.IGNORE) {
                continue;
            }
            if (role == GroupRole.ABSENT) {
                hasAbsent = true;
            } else {
                presentTypeCount++;
                if (role == GroupRole.HETEROZYGOUS || role == GroupRole.HOMOZYGOUS) {
                    hasGenotype = true;
                }
            }
        }
        return hasAbsent || hasGenotype || presentTypeCount >= 2;
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
            if (cohort == null || cohort.isEmpty()) {
                continue;
            }
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
        // Probe by cohort track index (O(1) after node track index) — cheaper than
        // scanning all sample calls when S ≫ cohort size.
        for (Integer trackIndex : trackIndices) {
            if (trackIndex == null || trackIndex < 0) {
                continue;
            }
            VariantNode.SampleCall call = node.getSampleCall(trackIndex);
            if (call == null || !passesSampleThresholds(node, call)) {
                continue;
            }
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
        for (Integer trackIndex : trackIndices) {
            if (trackIndex == null || trackIndex < 0) {
                continue;
            }
            VariantNode.SampleCall call = node.getSampleCall(trackIndex);
            if (call == null || !passesSampleThresholds(node, call)) {
                continue;
            }
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
        // HomRef AF is alt fraction (~0); do not reject AA on AF bounds.
        if (!node.isHomozygousRef(call) && call.alleleFraction >= 0) {
            if (f.minAlleleFraction > 0 && call.alleleFraction < f.minAlleleFraction) {
                return false;
            }
            if (f.maxAlleleFraction < 1.0 && call.alleleFraction > f.maxAlleleFraction) {
                return false;
            }
        }

        return true;
    }

    /**
     * Thresholds plus group-role genotype/presence constraints for table display.
     * HomRef marker alleles are never shown in normal browsing; calculated LOH
     * region nodes carry their own sample calls.
     */
    public boolean passesSampleDisplay(VariantNode node, VariantNode.SampleCall call) {
        if (node != null && hasClassSlices() && !VariantTypeVisuals.isLohRegion(node.type)) {
            return classSlice(node.type).passesSampleDisplay(node, call);
        }
        if (!passesSampleThresholds(node, call)) {
            return false;
        }
        // Synthetic LOH regions already encode LOH; skip site-level group re-check.
        if (VariantTypeVisuals.isLohRegion(node.type)) {
            return true;
        }
        if (node.isHomozygousRef(call)) {
            return false;
        }
        return passesSampleGroupConstraint(node, call, null);
    }

    /**
     * Build track→call map of display-eligible alleles for canvas/density.
     * Evaluates lineage failures once per node. Used when rebuilding the visible chain.
     */
    public IdentityHashMap<SampleTrack, VariantNode.SampleCall> buildDisplayByTrack(VariantNode node) {
        Set<Integer> failed = hasActiveGroupComparison() && node != null
            ? failedLineagesForNode(node)
            : Set.of();
        return buildDisplayByTrack(node, failed);
    }

    /**
     * Like {@link #buildDisplayByTrack(VariantNode)} but reuses a precomputed
     * {@link #failedLineagesForNode} set (avoids a second lineage pass).
     */
    public IdentityHashMap<SampleTrack, VariantNode.SampleCall> buildDisplayByTrack(
        VariantNode node, Set<Integer> failedLineages) {
        if (node != null && hasClassSlices() && !VariantTypeVisuals.isLohRegion(node.type)) {
            return classSlice(node.type).buildDisplayByTrack(node, failedLineages);
        }
        IdentityHashMap<SampleTrack, VariantNode.SampleCall> map = new IdentityHashMap<>();
        if (node == null) {
            return map;
        }
        if (VariantTypeVisuals.isLohRegion(node.type)) {
            node.forEachSample(call -> {
                if (call == null || call.getTrack() == null) {
                    return;
                }
                if (!passesSampleThresholds(node, call)) {
                    return;
                }
                map.put(call.getTrack(), call);
            });
            return map;
        }
        Set<Integer> failed = failedLineages != null
            ? failedLineages
            : (hasActiveGroupComparison() ? failedLineagesForNode(node) : Set.of());
        node.forEachSample(call -> {
            if (call == null || call.getTrack() == null) {
                return;
            }
            if (!passesSampleThresholds(node, call)) {
                return;
            }
            if (node.isHomozygousRef(call)) {
                return;
            }
            if (!passesSampleGroupConstraint(node, call, failed)) {
                return;
            }
            SampleTrack track = call.getTrack();
            VariantNode.SampleCall existing = map.get(track);
            // Prefer solid over transparent when several VCFs share a track.
            if (existing == null || (existing.isUiOverlay() && !call.isUiOverlay())) {
                map.put(track, call);
            }
        });
        return map;
    }

    /** Whether this call matches active group roles for its track (no-op when inactive). */
    public boolean passesSampleGroupConstraint(VariantNode node, VariantNode.SampleCall call) {
        return passesSampleGroupConstraint(node, call, null);
    }

    /**
     * Whether this call matches active group roles for its track.
     *
     * @param failedLineages precomputed {@link #failedLineagesForNode}, or null to compute
     */
    public boolean passesSampleGroupConstraint(
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
            if (role == GroupRole.HETEROZYGOUS) {
                if (!node.isHeterozygous(call)) {
                    return false;
                }
            }
            if (role == GroupRole.HOMOZYGOUS) {
                if (!node.isHomozygousAlt(call)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Track indices in HOMOZYGOUS cohorts of LOH-mode lineages (Child / similar).
     * Empty when not in LOH mode.
     */
    public Set<Integer> getLohHomozygousTrackIndices() {
        if (hasClassSlices()) {
            Set<Integer> out = new HashSet<>();
            if (pointSlice != null) {
                out.addAll(pointSlice.getLohHomozygousTrackIndices());
            }
            if (svSlice != null) {
                out.addAll(svSlice.getLohHomozygousTrackIndices());
            }
            return out;
        }
        ensureLineageCache();
        return cachedLohHomTrackIndices.isEmpty()
            ? Set.of()
            : Set.copyOf(cachedLohHomTrackIndices);
    }

    /**
     * Track indices in HETEROZYGOUS cohorts of LOH-mode lineages (Parental / Marker).
     * Empty when not in LOH mode. Used as the informative-marker grid for region calling.
     */
    public Set<Integer> getLohHeterozygousTrackIndices() {
        if (hasClassSlices()) {
            Set<Integer> out = new HashSet<>();
            if (pointSlice != null) {
                out.addAll(pointSlice.getLohHeterozygousTrackIndices());
            }
            if (svSlice != null) {
                out.addAll(svSlice.getLohHeterozygousTrackIndices());
            }
            return out;
        }
        ensureLineageCache();
        return cachedLohHetTrackIndices.isEmpty()
            ? Set.of()
            : Set.copyOf(cachedLohHetTrackIndices);
    }

    /**
     * Gap used when merging LOH sites into regions: comparison window when set,
     * otherwise {@link LohRegionBuilder#DEFAULT_GAP_BP}.
     */
    public int lohRegionGapBp() {
        if (hasClassSlices()) {
            int gap = 0;
            if (pointSlice != null && pointSlice.isLohModeLocal()) {
                gap = pointSlice.lohRegionGapOverrideBp > 0
                    ? pointSlice.lohRegionGapOverrideBp
                    : pointSlice.comparisonWindowBp;
            } else if (svSlice != null && svSlice.isLohModeLocal()) {
                gap = svSlice.lohRegionGapOverrideBp > 0
                    ? svSlice.lohRegionGapOverrideBp
                    : svSlice.comparisonWindowBp;
            }
            return gap > 0 ? gap : LohRegionBuilder.DEFAULT_GAP_BP;
        }
        if (lohRegionGapOverrideBp > 0) {
            return lohRegionGapOverrideBp;
        }
        return comparisonWindowBp > 0 ? comparisonWindowBp : LohRegionBuilder.DEFAULT_GAP_BP;
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

    /**
     * Count calls that pass display rules (thresholds + group roles).
     * Evaluates lineage failures once per node — never once per sample
     * (that O(samples) alloc storm OOMs large cohorts).
     */
    public int countDisplaySamples(VariantNode node) {
        if (node == null) return 0;
        if (hasClassSlices() && !VariantTypeVisuals.isLohRegion(node.type)) {
            return classSlice(node.type).countDisplaySamples(node);
        }
        if (VariantTypeVisuals.isLohRegion(node.type)) {
            int count = 0;
            for (VariantNode.SampleCall call : node.getSamples()) {
                if (call != null && passesSampleThresholds(node, call)) {
                    count++;
                }
            }
            return count;
        }
        Set<Integer> failed = hasActiveGroupComparison()
            ? failedLineagesForNode(node)
            : Set.of();
        int count = 0;
        for (VariantNode.SampleCall call : node.getSamples()) {
            if (call == null) {
                continue;
            }
            if (!passesSampleThresholds(node, call)) {
                continue;
            }
            if (node.isHomozygousRef(call)) {
                continue;
            }
            if (!passesSampleGroupConstraint(node, call, failed)) {
                continue;
            }
            count++;
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
            && maxAlleleFraction >= 1.0
            && minSvLengthBp <= 0
            && maxSvLengthBp == Long.MAX_VALUE
            && !cancerGenesOnly
            && minSharedSamples <= 1
            && maxSharedSamples == Integer.MAX_VALUE
            && !geneLevel
            && comparisonWindowBp <= 0
            && !hasActiveGroupComparison()
            && showCoding && showIntronic && showIntergenic
            && allowedTypes.equals(passAllLoadedTypes())
            && infoFieldFilters.isEmpty()
            && !filterFieldsActive;
    }

    /** All VCF-loaded types (point + SV); excludes synthetic LOH AA/BB. */
    private static EnumSet<VcfVariantType> passAllLoadedTypes() {
        EnumSet<VcfVariantType> types = EnumSet.allOf(VcfVariantType.class);
        types.removeAll(VariantTypeVisuals.lohRegionTypes());
        return types;
    }
}

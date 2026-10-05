package org.baseplayer.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned JSON DTO for BasePlayer session save/load.
 * Live runtime state stays in DrawStack / registries; this is snapshot-only.
 *
 * <p>Gson serializes every public field. Prefer adding new session state as public
 * fields on these nested specs so save/load picks them up without serializer changes;
 * wire capture/restore in {@link ProjectService} for the runtime objects.
 */
public class ProjectDocument {

  public int schemaVersion = 3;
  public String name;

  public GenomeSpec genome = new GenomeSpec();
  public Map<String, Object> settings = new LinkedHashMap<>();
  public UiSpec ui = new UiSpec();
  public VariantFilterSpec variantFilter = new VariantFilterSpec();
  public ViewportSpec sampleViewport = new ViewportSpec();
  public ViewportSpec featureViewport = new ViewportSpec();
  public SampleFilterSpec sampleFilter = new SampleFilterSpec();
  public List<StackSpec> stacks = new ArrayList<>();
  /** Named sample groups (sidebar colors); membership is on {@link SampleTrackSpec#groupIds}. */
  public List<SampleGroupSpec> sampleGroups = new ArrayList<>();
  public List<SampleTrackSpec> sampleTracks = new ArrayList<>();
  /** Legacy top-level VCF list; new saves omit this and nest VCFs under samples. */
  @Deprecated
  public List<PathSpec> vcfs;
  public List<FeatureTrackSpec> featureTracks = new ArrayList<>();

  public static class GenomeSpec {
    public String id;
    public String annotation;
  }

  public static class UiSpec {
    public boolean darkMode = true;
    public boolean maneOnly = true;
    /** Vertical main split (gene / feature / sample) divider positions. */
    public List<Double> mainSplitDividers;
    /** Shared horizontal sidebar width ratio (gene, feature, sample sidebars). */
    public Double sidebarDivider;
    /** Horizontal multi-column content divider positions. */
    public List<Double> columnDividers;
  }

  /** Shared session-available types (observed in VCF/cache) + nested point/SV filter slices. */
  public static class VariantFilterSpec {
    public List<String> availableTypes = new ArrayList<>();
    public List<String> availableEffects = new ArrayList<>();

    public ClassFilterSpec point = new ClassFilterSpec();
    public ClassFilterSpec sv = new ClassFilterSpec();

    // Legacy flat fields (schema v1). Read on restore when point/sv are empty.
    public double minQuality;
    public int minDepth;
    public double minAlleleFraction;
    public boolean cancerGenesOnly;
    public int minSharedSamples = 1;
    public int maxSharedSamples = Integer.MAX_VALUE;
    public boolean geneLevel;
    public int comparisonWindowBp;
    public List<String> allowedTypes = new ArrayList<>();
    public List<String> allowedEffects = new ArrayList<>();
    public Map<String, String> infoFieldFilters = new LinkedHashMap<>();
    public List<String> allowedFilterValues = new ArrayList<>();
  }

  /** Per-class (point vs structural) filter + sample-comparison settings. */
  public static class ClassFilterSpec {
    public double minQuality;
    public int minDepth;
    public double minAlleleFraction;
    public boolean cancerGenesOnly;
    public long minSvLengthBp;
    public long maxSvLengthBp = Long.MAX_VALUE;
    public int minSharedSamples = 1;
    public int maxSharedSamples = Integer.MAX_VALUE;
    public boolean geneLevel;
    public int comparisonWindowBp;
    /** {@code ALL} or {@code ANY} for multi-group present/genotype roles. */
    public String presentMatchMode;
    /** Active group roles ({@link #GroupRoleSpec}); IGNORE roles are omitted. */
    public List<GroupRoleSpec> groupRoles = new ArrayList<>();
    public List<String> allowedTypes = new ArrayList<>();
    public List<String> allowedEffects = new ArrayList<>();
    public Map<String, String> infoFieldFilters = new LinkedHashMap<>();
    public List<String> allowedFilterValues = new ArrayList<>();
  }

  /** One sample-group comparison role (ids match {@link SampleGroupSpec#id}). */
  public static class GroupRoleSpec {
    public int groupId;
    public String role;
  }

  public static class SampleGroupSpec {
    public int id;
    public String name;
    public String color;
  }

  public static class ViewportSpec {
    public int first = -1;
    public int last = -1;
    public double rowHeight;
    public double scroll;
    public double masterBandHeight;
  }

  public static class SampleFilterSpec {
    public String query = "";
    public String focusedGene;
  }

  public static class StackSpec {
    public String chromosome;
    public double viewStart;
    public double viewEnd;
  }

  public static class PathSpec {
    public String path;
    public String pathRelative;
  }

  public static class SampleTrackSpec {
    public String displayName;
    /** Membership in {@link #sampleGroups} (ordered); empty / null = ungrouped. */
    public List<Integer> groupIds = new ArrayList<>();
    public List<SampleFileSpec> samples = new ArrayList<>();
  }

  public static class SampleFileSpec {
    public String type; // BAM, BED, VCF
    public String path;
    public String pathRelative;
    public boolean visible = true;
    public boolean overlay;
  }

  public static class FeatureTrackSpec {
    public String kind; // bed, bigwig, ucsc, gnomad
    public String path;
    public String pathRelative;
    public String ucscTrackId;
    public String displayName;
    public boolean visible = true;
    public String color;
    public Double min;
    public Double max;
  }
}

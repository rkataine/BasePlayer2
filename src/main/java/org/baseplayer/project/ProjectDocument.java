package org.baseplayer.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned JSON DTO for BasePlayer session save/load.
 * Live runtime state stays in DrawStack / registries; this is snapshot-only.
 */
public class ProjectDocument {

  public int schemaVersion = 1;
  public String name;

  public GenomeSpec genome = new GenomeSpec();
  public Map<String, Object> settings = new LinkedHashMap<>();
  public UiSpec ui = new UiSpec();
  public VariantFilterSpec variantFilter = new VariantFilterSpec();
  public ViewportSpec sampleViewport = new ViewportSpec();
  public ViewportSpec featureViewport = new ViewportSpec();
  public SampleFilterSpec sampleFilter = new SampleFilterSpec();
  public List<StackSpec> stacks = new ArrayList<>();
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
  }

  public static class VariantFilterSpec {
    public double minQuality;
    public int minDepth;
    public double minAlleleFraction;
    public boolean cancerGenesOnly;
    public List<String> allowedTypes = new ArrayList<>();
    public List<String> allowedEffects = new ArrayList<>();
    public Map<String, String> infoFieldFilters = new LinkedHashMap<>();
    public List<String> allowedFilterValues = new ArrayList<>();
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

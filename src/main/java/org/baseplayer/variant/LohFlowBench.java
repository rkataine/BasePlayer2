package org.baseplayer.variant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.samples.SampleTag;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.utils.ChromosomeNames;

import javafx.application.Platform;
import javafx.scene.paint.Color;

/**
 * Headless LOH comparison bench: load one parental + one child VCF, run
 * {@link VariantList#rebuildForComparison}, print {@link LohTiming} lines and
 * a sample of LOH regions — without opening the GUI.
 *
 * <pre>
 * Usage:
 *   LohFlowBench --parental parent.vcf.gz --child child.vcf.gz
 *                [--chrom 1] [--gap 100000] [--repeat 2] [--cold]
 *                [--show 20] [--parental-sample NAME] [--child-sample NAME]
 * </pre>
 */
public final class LohFlowBench {

  private LohFlowBench() {}

  public static void main(String[] args) throws Exception {
    Args parsed = Args.parse(args);
    if (parsed == null) {
      printUsage();
      System.exit(2);
      return;
    }

    ensureFxToolkit();
    ProjectSessionState.get().setSuppressDirty(true);
    ServiceRegistry.reset();

    Path parentalPath = parsed.parental;
    Path childPath = parsed.child;

    try (VcfReader parentalReader = new VcfReader(parentalPath);
         VcfReader childReader = new VcfReader(childPath)) {

      String parentalSample = resolveSampleName(
          parentalReader, parsed.parentalSample, "parental");
      String childSample = resolveSampleName(
          childReader, parsed.childSample, "child");

      SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
      SampleTrack parentalTrack = new SampleTrack(parentalSample);
      parentalTrack.addSample(new Sample(parentalPath, Sample.DataType.VCF));
      parentalTrack.addTag(SampleTag.PARENTAL);

      SampleTrack childTrack = new SampleTrack(childSample);
      childTrack.addSample(new Sample(childPath, Sample.DataType.VCF));
      childTrack.addTag(SampleTag.CHILD);

      registry.getSampleTracks().add(parentalTrack);
      registry.getSampleTracks().add(childTrack);
      registry.getSampleList().add(parentalSample);
      registry.getSampleList().add(childSample);

      SampleGroup group = registry.createGroupForTracks(
          List.of(parentalTrack, childTrack), "loh-bench", Color.web("#7dcea0"));
      if (group == null) {
        throw new IllegalStateException("Failed to create sample group");
      }

      VariantLoader parentalLoader = new VariantLoader(parentalReader);
      VariantLoader childLoader = new VariantLoader(childReader);
      parentalLoader.updateMapping();
      childLoader.updateMapping();

      if (parentalLoader.getMappedSampleCount() == 0) {
        fail("Parental VCF sample '" + parentalSample + "' did not map to a track");
      }
      if (childLoader.getMappedSampleCount() == 0) {
        fail("Child VCF sample '" + childSample + "' did not map to a track");
      }

      String chrom = resolveChromosome(parsed.chrom, parentalReader, childReader);
      long chromLen = chromosomeLength(parentalReader, chrom, childReader);

      System.err.println("[LOH-bench] parental=" + parentalPath.getFileName()
          + " sample=" + parentalSample
          + " mapped=" + parentalLoader.getMappedSampleCount());
      System.err.println("[LOH-bench] child=" + childPath.getFileName()
          + " sample=" + childSample
          + " mapped=" + childLoader.getMappedSampleCount());
      System.err.println("[LOH-bench] chrom=" + chrom
          + " gapBp=" + (parsed.gapBp > 0 ? parsed.gapBp : LohRegionBuilder.DEFAULT_GAP_BP)
          + " repeat=" + parsed.repeat);

      long loadStart = System.nanoTime();
      VariantList variants = new VariantList(chrom);
      VariantNode cursor = null;
      cursor = parentalLoader.streamChromosomeVariantsToList(
          chrom, variants, cursor, null, null, chromLen);
      cursor = childLoader.streamChromosomeVariantsToList(
          chrom, variants, cursor, null, null, chromLen);
      long loadMs = (System.nanoTime() - loadStart) / 1_000_000L;
      System.err.println("[LOH-bench] loaded nodes=" + variants.size()
          + " load=" + loadMs + "ms");

      VariantFilter filter = buildLohFilter(parsed.gapBp);
      System.err.println("[LOH-bench] lohMode=" + filter.isLohMode()
          + " hetTracks=" + filter.getLohHeterozygousTrackIndices()
          + " homTracks=" + filter.getLohHomozygousTrackIndices()
          + " cohort=" + filter.comparisonCohortSummary());

      if (!filter.isLohMode()) {
        fail("Filter is not in LOH mode — check Parental/Child tags and roles");
      }

      for (int i = 1; i <= parsed.repeat; i++) {
        if (i > 1 && parsed.coldRepeat) {
          variants.invalidateVisibleFilterKey();
          variants.clearLohRegions();
        }
        System.err.println("[LOH-bench] --- pass " + i + "/" + parsed.repeat
            + (i == 1 ? " (cold)" : parsed.coldRepeat ? " (cold)" : " (warm)")
            + " ---");

        long tFilter = System.nanoTime();
        variants.rebuildForComparison(filter, null);
        long filterMs = (System.nanoTime() - tFilter) / 1_000_000L;
        System.err.println("[LOH-bench] pass=" + i
            + " filterRebuild=" + filterMs + "ms"
            + " visible=" + countVisible(variants)
            + " regionsBeforeCalc=" + variants.getLohRegions().size());

        long tCalc = System.nanoTime();
        variants.calculateLohRegions(filter, null);
        long calcMs = (System.nanoTime() - tCalc) / 1_000_000L;
        List<VariantNode> regions = variants.getLohRegions();
        System.err.println("[LOH-bench] pass=" + i
            + " calculateLoh=" + calcMs + "ms"
            + " regions=" + regions.size());
        if (i == 1 || parsed.showEveryPass) {
          printRegionSample(chrom, regions, parsed.show);
        }
      }
    }

    System.err.println("[LOH-bench] done");
    // FX toolkit keeps a non-daemon thread alive.
    System.exit(0);
  }

  private static VariantFilter buildLohFilter(int gapBp) {
    VariantFilter filter = new VariantFilter();
    Map<SampleTag, VariantFilter.GroupRole> roles = new EnumMap<>(SampleTag.class);
    roles.put(SampleTag.PARENTAL, VariantFilter.GroupRole.HETEROZYGOUS);
    roles.put(SampleTag.CHILD, VariantFilter.GroupRole.HOMOZYGOUS);
    filter.setTagRoles(roles);
    if (gapBp > 0) {
      filter.setLohRegionGapOverrideBp(gapBp);
    }
    return filter;
  }

  private static void printRegionSample(String chrom, List<VariantNode> regions, int show) {
    if (regions == null || regions.isEmpty() || show <= 0) {
      return;
    }
    int n = Math.min(show, regions.size());
    System.err.println("[LOH-bench] first " + n + " regions:");
    for (int i = 0; i < n; i++) {
      VariantNode r = regions.get(i);
      if (r == null) {
        continue;
      }
      long end = r.svEnd > r.position ? r.svEnd : r.position;
      System.err.printf(Locale.ROOT,
          "  %s:%d-%d %s samples=%d%n",
          chrom, r.position, end, r.type, r.getSampleCount());
    }
  }

  private static int countVisible(VariantList variants) {
    int n = 0;
    for (VariantNode node = variants.getVisibleHead(); node != null; node = node.nextVisible) {
      n++;
    }
    return n;
  }

  private static String resolveSampleName(VcfReader reader, String requested, String role)
      throws IOException {
    List<String> names = reader.getSampleNames();
    if (names == null || names.isEmpty()) {
      fail(role + " VCF has no sample columns");
    }
    if (requested != null && !requested.isBlank()) {
      for (String name : names) {
        if (requested.equals(name)) {
          return name;
        }
      }
      fail(role + " sample '" + requested + "' not in VCF; have " + names);
    }
    if (names.size() > 1) {
      System.err.println("[LOH-bench] warning: " + role + " VCF has " + names.size()
          + " samples; using first: " + names.get(0)
          + " (override with --" + role + "-sample)");
    }
    return names.get(0);
  }

  private static String resolveChromosome(
      String requested, VcfReader parental, VcfReader child) {
    List<String> parentalChroms = parental.getAvailableChromosomes();
    List<String> childChroms = child.getAvailableChromosomes();
    if (parentalChroms.isEmpty()) {
      fail("Parental VCF has no contigs in header");
    }
    if (requested != null && !requested.isBlank()) {
      String match = findChrom(requested, parentalChroms);
      if (match == null) {
        fail("Chromosome '" + requested + "' not in parental VCF contigs");
      }
      return ChromosomeNames.strip(match);
    }
    Set<String> childStripped = new HashSet<>();
    for (String c : childChroms) {
      childStripped.add(ChromosomeNames.strip(c));
    }
    for (String c : parentalChroms) {
      String stripped = ChromosomeNames.strip(c);
      if (childStripped.contains(stripped) && looksAutosomalOrSex(stripped)) {
        return stripped;
      }
    }
    for (String c : parentalChroms) {
      String stripped = ChromosomeNames.strip(c);
      if (childStripped.contains(stripped)) {
        return stripped;
      }
    }
    return ChromosomeNames.strip(parentalChroms.get(0));
  }

  private static boolean looksAutosomalOrSex(String stripped) {
    if (stripped == null || stripped.isBlank()) {
      return false;
    }
    if (stripped.equalsIgnoreCase("X") || stripped.equalsIgnoreCase("Y")
        || stripped.equalsIgnoreCase("M") || stripped.equalsIgnoreCase("MT")) {
      return true;
    }
    try {
      int n = Integer.parseInt(stripped);
      return n >= 1 && n <= 22;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static String findChrom(String requested, List<String> contigs) {
    for (String c : contigs) {
      if (ChromosomeNames.equals(c, requested)) {
        return c;
      }
    }
    return null;
  }

  private static long chromosomeLength(VcfReader primary, String chrom, VcfReader fallback) {
    String dataChrom = primary.toDataChrom(chrom);
    Long len = primary.getChromosomeLength(dataChrom);
    if (len == null || len <= 0) {
      for (String c : primary.getAvailableChromosomes()) {
        if (ChromosomeNames.equals(c, chrom)) {
          len = primary.getChromosomeLength(c);
          break;
        }
      }
    }
    if ((len == null || len <= 0) && fallback != null) {
      len = fallback.getChromosomeLength(fallback.toDataChrom(chrom));
    }
    return len != null && len > 0 ? len : Long.MAX_VALUE / 4;
  }

  private static void ensureFxToolkit() throws InterruptedException {
    if (Platform.isFxApplicationThread()) {
      return;
    }
    CountDownLatch latch = new CountDownLatch(1);
    try {
      Platform.startup(latch::countDown);
    } catch (IllegalStateException alreadyStarted) {
      latch.countDown();
    }
    if (!latch.await(15, TimeUnit.SECONDS)) {
      fail("JavaFX toolkit failed to start");
    }
  }

  private static void fail(String message) {
    System.err.println("[LOH-bench] error: " + message);
    System.exit(1);
  }

  private static void printUsage() {
    System.err.println("""
        Usage:
          LohFlowBench --parental <parent.vcf.gz> --child <child.vcf.gz>
                       [--chrom 1] [--gap 100000] [--repeat 1] [--cold]
                       [--show 20] [--parental-sample NAME] [--child-sample NAME]

        Gradle:
          ./gradlew lohBench -Pparental=/path/p.vcf.gz -Pchild=/path/c.vcf.gz -Pchrom=1
        """);
  }

  private static final class Args {
    Path parental;
    Path child;
    String chrom;
    String parentalSample;
    String childSample;
    int gapBp;
    int repeat = 1;
    int show = 20;
    boolean coldRepeat;
    boolean showEveryPass;

    static Args parse(String[] args) {
      if (args == null || args.length == 0) {
        return null;
      }
      Args out = new Args();
      for (int i = 0; i < args.length; i++) {
        String a = args[i];
        switch (a) {
          case "--parental", "-p" -> out.parental = pathArg(args, ++i, a);
          case "--child", "-c" -> out.child = pathArg(args, ++i, a);
          case "--chrom" -> out.chrom = stringArg(args, ++i, a);
          case "--parental-sample" -> out.parentalSample = stringArg(args, ++i, a);
          case "--child-sample" -> out.childSample = stringArg(args, ++i, a);
          case "--gap" -> out.gapBp = intArg(args, ++i, a);
          case "--repeat" -> out.repeat = Math.max(1, intArg(args, ++i, a));
          case "--show" -> out.show = Math.max(0, intArg(args, ++i, a));
          case "--cold" -> out.coldRepeat = true;
          case "--show-every" -> out.showEveryPass = true;
          case "--help", "-h" -> {
            return null;
          }
          default -> {
            System.err.println("Unknown argument: " + a);
            return null;
          }
        }
      }
      if (out.parental == null || out.child == null) {
        return null;
      }
      if (!Files.isRegularFile(out.parental)) {
        System.err.println("Parental file not found: " + out.parental);
        return null;
      }
      if (!Files.isRegularFile(out.child)) {
        System.err.println("Child file not found: " + out.child);
        return null;
      }
      return out;
    }

    private static Path pathArg(String[] args, int i, String flag) {
      if (i >= args.length) {
        throw new IllegalArgumentException(flag + " requires a path");
      }
      return Path.of(args[i]);
    }

    private static String stringArg(String[] args, int i, String flag) {
      if (i >= args.length) {
        throw new IllegalArgumentException(flag + " requires a value");
      }
      return args[i];
    }

    private static int intArg(String[] args, int i, String flag) {
      return Integer.parseInt(stringArg(args, i, flag));
    }
  }
}

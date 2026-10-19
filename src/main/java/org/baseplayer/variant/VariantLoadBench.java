package org.baseplayer.variant;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.baseplayer.io.readers.VcfReader;
import org.baseplayer.project.ProjectSessionState;
import org.baseplayer.samples.Sample;
import org.baseplayer.samples.SampleTrack;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.variant.annotation.VariantAnnotator;

import javafx.application.Platform;

/**
 * Headless sequential VCF load bench: stream each file into one {@link VariantList},
 * then annotate + rebuild — mirroring the intended populate-then-annotate path.
 *
 * <pre>
 * ./gradlew variantLoadBench \
 *   -Pvcfs=/path/a.vcf.gz,/path/b.vcf.gz,/path/c.vcf.gz \
 *   [-Pchrom=chr1]
 * </pre>
 */
public final class VariantLoadBench {

  private VariantLoadBench() {}

  public static void main(String[] args) throws Exception {
    Args parsed = Args.parse(args);
    if (parsed == null) {
      System.err.println(
          "Usage: VariantLoadBench --chrom chr1 --vcf a.vcf.gz [--vcf b.vcf.gz ...]");
      System.exit(2);
      return;
    }

    ensureFxToolkit();
    ProjectSessionState.get().setSuppressDirty(true);
    ServiceRegistry.reset();

    SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
    VariantList list = new VariantList(parsed.chrom);
    VariantFilter filter = new VariantFilter();
    VariantNode cursor = null;

    System.out.printf(Locale.ROOT,
        "Bench chrom=%s files=%d%n", parsed.chrom, parsed.vcfs.size());

    long totalStreamNs = 0;
    long totalAnnotateNs = 0;
    long totalRebuildNs = 0;

    for (int i = 0; i < parsed.vcfs.size(); i++) {
      Path vcfPath = parsed.vcfs.get(i);
      long fileStart = System.nanoTime();

      try (VcfReader reader = new VcfReader(vcfPath)) {
        String sampleName = reader.getSampleNames().isEmpty()
            ? vcfPath.getFileName().toString()
            : reader.getSampleNames().get(0);

        SampleTrack track = new SampleTrack(sampleName);
        track.addSample(new Sample(vcfPath, Sample.DataType.VCF));
        registry.getSampleTracks().add(track);
        registry.getSampleList().add(sampleName);

        VariantLoader loader = new VariantLoader(reader);
        loader.updateMapping();

        int sizeBefore = list.size();
        long streamStart = System.nanoTime();
        cursor = loader.streamRegionVariantsToList(
            parsed.chrom, 1, Long.MAX_VALUE / 4, list, cursor, null, filter);
        long streamNs = System.nanoTime() - streamStart;
        totalStreamNs += streamNs;

        long annotateStart = System.nanoTime();
        VariantAnnotator annotator = new VariantAnnotator(
            ServiceRegistry.getInstance().getReferenceGenomeService());
        annotator.annotate(list, parsed.chrom);
        long annotateNs = System.nanoTime() - annotateStart;
        totalAnnotateNs += annotateNs;

        long rebuildStart = System.nanoTime();
        list.rebuildVisibleChain(filter);
        long rebuildNs = System.nanoTime() - rebuildStart;
        totalRebuildNs += rebuildNs;

        long fileNs = System.nanoTime() - fileStart;
        System.out.printf(Locale.ROOT,
            "file[%d] %s%n"
                + "  stream=%.2fs  annotate=%.2fs  rebuild=%.2fs  wall=%.2fs%n"
                + "  listSize %d -> %d  (+%d)  cursorPos=%s%n",
            i,
            vcfPath.getFileName(),
            streamNs / 1e9,
            annotateNs / 1e9,
            rebuildNs / 1e9,
            fileNs / 1e9,
            sizeBefore,
            list.size(),
            list.size() - sizeBefore,
            cursor == null ? "-" : Long.toString(cursor.position));
      }
    }

    System.out.printf(Locale.ROOT,
        "TOTAL stream=%.2fs annotate=%.2fs rebuild=%.2fs listSize=%d%n",
        totalStreamNs / 1e9,
        totalAnnotateNs / 1e9,
        totalRebuildNs / 1e9,
        list.size());
    System.exit(0);
  }

  private static void ensureFxToolkit() throws InterruptedException {
    CountDownLatch ready = new CountDownLatch(1);
    Platform.startup(ready::countDown);
    if (!ready.await(30, TimeUnit.SECONDS)) {
      throw new IllegalStateException("JavaFX toolkit failed to start");
    }
  }

  private static final class Args {
    final String chrom;
    final List<Path> vcfs;

    Args(String chrom, List<Path> vcfs) {
      this.chrom = chrom;
      this.vcfs = vcfs;
    }

    static Args parse(String[] args) {
      String chrom = "chr1";
      List<Path> vcfs = new ArrayList<>();
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--chrom" -> {
            if (i + 1 >= args.length) return null;
            chrom = args[++i];
          }
          case "--vcf" -> {
            if (i + 1 >= args.length) return null;
            vcfs.add(Path.of(args[++i]));
          }
          default -> {
            return null;
          }
        }
      }
      if (vcfs.isEmpty()) {
        return null;
      }
      return new Args(chrom, vcfs);
    }
  }
}

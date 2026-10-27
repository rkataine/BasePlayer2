package org.baseplayer.variant.annotation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.baseplayer.io.readers.TabixBgzfScanner;
import org.baseplayer.io.readers.TabixIndex;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator.ProgressListener;
import org.baseplayer.variant.annotation.ControlFrequencyAnnotator.SearchWindow;

/**
 * Annotates sample variants with ClinVar clinical significance ({@code CLNSIG})
 * from a local bgzipped+tabix ClinVar VCF. Allele matching mirrors
 * {@link ControlFrequencyAnnotator} (exact REF/ALT at the same POS).
 */
public final class ClinVarAnnotator implements AutoCloseable {

  private Path path;
  private TabixBgzfScanner scanner;

  public boolean isOpen() {
    return scanner != null;
  }

  public Path path() {
    return path;
  }

  public synchronized void open(Path vcfPath) throws IOException {
    if (vcfPath == null) {
      throw new IOException("ClinVar VCF path is null");
    }
    close();
    Path tbi = TabixIndex.findSiblingIndex(vcfPath);
    if (tbi == null) {
      throw new IOException(
          "ClinVar VCF must be bgzipped with a sibling .tbi index: " + vcfPath.getFileName());
    }
    TabixIndex openedIndex = new TabixIndex(tbi);
    this.scanner = new TabixBgzfScanner(vcfPath, openedIndex);
    this.path = vcfPath.toAbsolutePath().normalize();
  }

  @Override
  public synchronized void close() {
    if (scanner != null) {
      try {
        scanner.close();
      } catch (IOException ignored) {
      }
    }
    scanner = null;
    path = null;
  }

  public static void clear(VariantList list) {
    if (list == null) {
      return;
    }
    for (VariantNode node = list.getFirst(); node != null; node = node.next) {
      node.clinVarClnsig = null;
      node.clinVarVariationId = null;
    }
    list.setClinVarSourcePath(null);
  }

  /** NCBI ClinVar variation page for a Variation ID from the ClinVar VCF ID column. */
  public static String variationUrl(String variationId) {
    if (variationId == null || variationId.isBlank() || ".".equals(variationId.trim())) {
      return null;
    }
    // Multi-allelic / compound IDs are rare; use the first semicolon-separated token.
    String id = variationId.trim();
    int semi = id.indexOf(';');
    if (semi >= 0) {
      id = id.substring(0, semi).trim();
    }
    if (id.isEmpty() || ".".equals(id)) {
      return null;
    }
    return "https://www.ncbi.nlm.nih.gov/clinvar/variation/" + id + "/";
  }

  public synchronized boolean needsAnnotation(VariantList list) {
    if (scanner == null || path == null || list == null) {
      return false;
    }
    String annotatedFrom = list.getClinVarSourcePath();
    return annotatedFrom == null || !annotationFingerprint().equals(annotatedFrom);
  }

  private String annotationFingerprint() {
    // Suffix bumps cached annotations when stored fields change (e.g. variation ID).
    return path.toAbsolutePath() + "|id";
  }

  public synchronized void annotate(VariantList list) throws IOException {
    annotate(list, null, false);
  }

  public synchronized void annotate(VariantList list, ProgressListener progress, boolean force)
      throws IOException {
    if (scanner == null || path == null || list == null) {
      return;
    }
    if (!force && !needsAnnotation(list)) {
      return;
    }

    String chromosome = list.getChromosome();
    // Always annotate the full allele list — never the filter-visible chain.
    // Visible chains may be empty or ClinVar-filtered before this runs (annotate-all).
    for (VariantNode node = list.getFirst(); node != null; node = node.next) {
      node.clinVarClnsig = null;
      node.clinVarVariationId = null;
    }
    List<VariantNode> nodes = collectAllNodes(list);
    if (nodes.isEmpty() || chromosome == null || chromosome.isBlank()) {
      list.setClinVarSourcePath(annotationFingerprint());
      return;
    }

    int[] matched = { 0 };
    List<SearchWindow> windows =
        ControlFrequencyAnnotator.buildSearchWindows(
            nodes, ControlFrequencyAnnotator.MAX_SEARCH_WINDOW_BP);
    int windowCount = windows.size();
    if (progress != null) {
      progress.onProgress(chromosome, 0, Math.max(1, windowCount), matched[0]);
    }
    for (int w = 0; w < windowCount; w++) {
      SearchWindow window = windows.get(w);
      final int[] sampleIdx = { window.nodeFrom() };
      final int nodeTo = window.nodeToExclusive();
      final long spanEnd = window.end();
      scanner.visitRegion(chromosome, window.start(), window.end(), (buf, off, len) -> {
        long pos = ControlFrequencyAnnotator.parseVcfPos(buf, off, len);
        if (pos < 0) {
          return true;
        }
        if (pos > spanEnd) {
          return false;
        }
        int i = sampleIdx[0];
        while (i < nodeTo && nodes.get(i).position < pos) {
          i++;
        }
        sampleIdx[0] = i;
        if (i >= nodeTo) {
          return false;
        }
        if (nodes.get(i).position == pos) {
          matchRawLine(buf, off, len, pos, nodes, i, nodeTo, matched);
        }
        return true;
      });
      if (progress != null) {
        progress.onProgress(chromosome, w + 1, Math.max(1, windowCount), matched[0]);
      }
    }
    list.setClinVarSourcePath(annotationFingerprint());
  }

  private static List<VariantNode> collectAllNodes(VariantList list) {
    List<VariantNode> nodes = new ArrayList<>();
    for (VariantNode node = list.getFirst(); node != null; node = node.next) {
      nodes.add(node);
    }
    return nodes;
  }

  private static void matchRawLine(
      byte[] buf,
      int off,
      int len,
      long pos,
      List<VariantNode> nodes,
      int sampleAtPos,
      int total,
      int[] matched) {
    int end = off + len;
    int t1 = indexOf(buf, off, end, (byte) '\t');
    int t2 = t1 < 0 ? -1 : indexOf(buf, t1 + 1, end, (byte) '\t');
    int t3 = t2 < 0 ? -1 : indexOf(buf, t2 + 1, end, (byte) '\t');
    int t4 = t3 < 0 ? -1 : indexOf(buf, t3 + 1, end, (byte) '\t');
    int t5 = t4 < 0 ? -1 : indexOf(buf, t4 + 1, end, (byte) '\t');
    int t6 = t5 < 0 ? -1 : indexOf(buf, t5 + 1, end, (byte) '\t');
    int t7 = t6 < 0 ? -1 : indexOf(buf, t6 + 1, end, (byte) '\t');
    int t8 = t7 < 0 ? -1 : indexOf(buf, t7 + 1, end, (byte) '\t');
    if (t2 < 0 || t3 < 0 || t4 < 0 || t5 < 0 || t7 < 0) {
      return;
    }
    String variationId = new String(buf, t2 + 1, t3 - (t2 + 1), StandardCharsets.US_ASCII).trim();
    if (variationId.isEmpty() || ".".equals(variationId)) {
      variationId = null;
    }
    int refStart = t3 + 1;
    int refEnd = t4;
    int altStart = t4 + 1;
    int altEnd = t5;
    int infoStart = t7 + 1;
    int infoEnd = t8 < 0 ? end : t8;

    String clnsig = infoString(buf, infoStart, infoEnd, "CLNSIG");
    if (clnsig == null || clnsig.isBlank()) {
      clnsig = "ClinVar";
    }

    for (int i = sampleAtPos; i < total && nodes.get(i).position == pos; i++) {
      VariantNode node = nodes.get(i);
      if (node.ref == null || node.alt == null) {
        continue;
      }
      if (!allelesMatch(buf, refStart, refEnd, altStart, altEnd, node.ref, node.alt)) {
        continue;
      }
      if (node.clinVarClnsig == null) {
        matched[0]++;
      }
      node.clinVarClnsig = clnsig;
      node.clinVarVariationId = variationId;
    }
  }

  private static boolean allelesMatch(
      byte[] buf,
      int refStart,
      int refEnd,
      int altStart,
      int altEnd,
      String sampleRef,
      String sampleAlt) {
    if (!equalsIgnoreCaseAscii(buf, refStart, refEnd, sampleRef)) {
      return false;
    }
    // ALT may be comma-separated multi-allelic.
    int start = altStart;
    while (start < altEnd) {
      int comma = indexOf(buf, start, altEnd, (byte) ',');
      int stop = comma < 0 ? altEnd : comma;
      if (equalsIgnoreCaseAscii(buf, start, stop, sampleAlt)) {
        return true;
      }
      if (comma < 0) {
        break;
      }
      start = comma + 1;
    }
    return false;
  }

  private static String infoString(byte[] buf, int infoStart, int infoEnd, String key) {
    if (infoStart < 0 || infoEnd <= infoStart || key == null || key.isEmpty()) {
      return null;
    }
    byte[] keyBytes = key.getBytes(StandardCharsets.US_ASCII);
    int i = infoStart;
    while (i < infoEnd) {
      int sep = indexOf(buf, i, infoEnd, (byte) ';');
      int tokenEnd = sep < 0 ? infoEnd : sep;
      int eq = indexOf(buf, i, tokenEnd, (byte) '=');
      if (eq > i) {
        if (tokenKeyEquals(buf, i, eq, keyBytes)) {
          return new String(buf, eq + 1, tokenEnd - (eq + 1), StandardCharsets.US_ASCII).trim();
        }
      }
      if (sep < 0) {
        break;
      }
      i = sep + 1;
    }
    return null;
  }

  private static boolean tokenKeyEquals(byte[] buf, int start, int end, byte[] key) {
    if (end - start != key.length) {
      return false;
    }
    for (int i = 0; i < key.length; i++) {
      byte a = buf[start + i];
      byte b = key[i];
      if (a >= 'A' && a <= 'Z') {
        a = (byte) (a + 32);
      }
      if (b >= 'A' && b <= 'Z') {
        b = (byte) (b + 32);
      }
      if (a != b) {
        return false;
      }
    }
    return true;
  }

  private static boolean equalsIgnoreCaseAscii(byte[] buf, int start, int end, String expected) {
    if (expected == null || end - start != expected.length()) {
      return false;
    }
    for (int i = 0; i < expected.length(); i++) {
      char e = expected.charAt(i);
      byte b = buf[start + i];
      char c = (char) (b & 0xff);
      if (Character.toUpperCase(e) != Character.toUpperCase(c)) {
        return false;
      }
    }
    return true;
  }

  private static int indexOf(byte[] buf, int from, int to, byte value) {
    for (int i = from; i < to; i++) {
      if (buf[i] == value) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Coarse ClinVar significance bucket for optional display-time filtering.
   * Annotation always stores the raw {@code CLNSIG} string.
   */
  public enum SignificanceClass {
    PATHOGENIC("Pathogenic / likely pathogenic"),
    VUS("Uncertain significance"),
    BENIGN("Benign / likely benign"),
    OTHER("Conflicting / other");

    private final String label;

    SignificanceClass(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** Map a raw {@code CLNSIG} value to a filter/display bucket. */
  public static SignificanceClass classify(String clnsig) {
    if (clnsig == null || clnsig.isBlank()) {
      return null;
    }
    String upper = clnsig.toUpperCase(Locale.ROOT).replace(' ', '_');
    if (upper.contains("CONFLICT")) {
      return SignificanceClass.OTHER;
    }
    boolean pathogenic = upper.contains("PATHOGENIC");
    boolean benign = upper.contains("BENIGN");
    if (pathogenic && benign) {
      return SignificanceClass.OTHER;
    }
    if (pathogenic) {
      return SignificanceClass.PATHOGENIC;
    }
    if (benign) {
      return SignificanceClass.BENIGN;
    }
    if (upper.contains("UNCERTAIN") || upper.contains("VUS")) {
      return SignificanceClass.VUS;
    }
    return SignificanceClass.OTHER;
  }

  /** True when CLNSIG looks pathogenic / likely pathogenic. */
  public static boolean looksPathogenic(String clnsig) {
    return classify(clnsig) == SignificanceClass.PATHOGENIC;
  }

  /** Short table label from raw CLNSIG (e.g. {@code Pathogenic} → {@code P}). */
  public static String shortLabel(String clnsig) {
    SignificanceClass cls = classify(clnsig);
    if (cls == null) {
      return "—";
    }
    return switch (cls) {
      case PATHOGENIC -> clnsig.toUpperCase(Locale.ROOT).contains("LIKELY") ? "LP" : "P";
      case VUS -> "VUS";
      case BENIGN -> clnsig.toUpperCase(Locale.ROOT).contains("LIKELY") ? "LB" : "B";
      case OTHER -> "oth";
    };
  }
}

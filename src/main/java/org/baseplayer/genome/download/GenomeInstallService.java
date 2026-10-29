package org.baseplayer.genome.download;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import htsjdk.samtools.reference.FastaSequenceIndexCreator;

/**
 * Downloads a curated Ensembl genome into {@code genomes/<folderId>/}:
 * uncompressed FASTA + {@code .fai}, gzipped GFF3, optional UCSC {@code bands.txt}.
 */
public final class GenomeInstallService {

  public enum Phase {
    FASTA("Downloading & decompressing FASTA"),
    INDEX("Building FASTA index (.fai)"),
    GFF3("Downloading gene annotation (GFF3)"),
    BANDS("Downloading cytobands (UCSC)"),
    COPY("Copying local files"),
    FINALIZE("Finalizing");

    private final String label;

    Phase(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  @FunctionalInterface
  public interface ProgressListener {
    /**
     * @param phase          current phase
     * @param bytesProcessed bytes copied in the current download phase (0 for non-download)
     * @param totalBytes     Content-Length when known, else -1
     * @param message        short status line
     */
    void onProgress(Phase phase, long bytesProcessed, long totalBytes, String message);
  }

  private final Path genomesRoot;
  private final HttpClient httpClient;
  private final AtomicBoolean cancelled = new AtomicBoolean();

  public GenomeInstallService(Path genomesRoot) {
    this.genomesRoot = genomesRoot;
    this.httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build();
  }

  public void cancel() {
    cancelled.set(true);
  }

  public boolean isCancelled() {
    return cancelled.get();
  }

  /** True when folder has an indexed reference FASTA ({@code .fa}/.fasta + {@code .fai}). */
  public static boolean isInstalled(Path genomesRoot, EnsemblGenomeCatalog.Entry entry) {
    return isInstalled(genomesRoot, entry.folderId());
  }

  /**
   * True when {@code genomes/<folderId>} has any FASTA with sibling {@code .fai}.
   * Gene annotation is optional.
   */
  public static boolean isInstalled(Path genomesRoot, String folderId) {
    Path dir = genomesRoot.resolve(folderId);
    if (!Files.isDirectory(dir)) {
      return false;
    }
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .filter(Files::isRegularFile)
          .anyMatch(f -> {
            String name = f.getFileName().toString().toLowerCase();
            if (!(name.endsWith(".fa") || name.endsWith(".fasta"))) {
              return false;
            }
            return Files.isRegularFile(Path.of(f.toString() + ".fai"));
          });
    } catch (IOException e) {
      return false;
    }
  }

  /** Folder names under {@code genomes/} that already have an indexed FASTA. */
  public static List<String> listInstalledFolderIds(Path genomesRoot) {
    if (!Files.isDirectory(genomesRoot)) {
      return List.of();
    }
    try (Stream<Path> dirs = Files.list(genomesRoot)) {
      return dirs
          .filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .filter(id -> !id.endsWith(".partial"))
          .filter(id -> isInstalled(genomesRoot, id))
          .sorted(String.CASE_INSENSITIVE_ORDER)
          .toList();
    } catch (IOException e) {
      return List.of();
    }
  }

  /**
   * Install {@code entry} under {@code genomesRoot}. Deletes a partial install on failure
   * or cancellation. Existing complete installs are left untouched (caller should check
   * {@link #isInstalled}).
   */
  public Path install(EnsemblGenomeCatalog.Entry entry, ProgressListener progress)
      throws IOException, InterruptedException {
    cancelled.set(false);
    Path targetDir = genomesRoot.resolve(entry.folderId());
    Path workDir = genomesRoot.resolve(entry.folderId() + ".partial");

    deleteRecursively(workDir);
    Files.createDirectories(workDir.resolve("annotation"));

    try {
      Path fasta = workDir.resolve(entry.fastaFileName());
      downloadGunzip(entry.fastaUrl(), fasta, Phase.FASTA, progress);
      checkCancelled();

      progress.onProgress(Phase.INDEX, 0, -1, Phase.INDEX.label());
      FastaSequenceIndexCreator.create(fasta, true);
      checkCancelled();

      Path gff = workDir.resolve("annotation").resolve(entry.gff3FileName());
      downloadRaw(entry.gff3Url(), gff, Phase.GFF3, progress);
      checkCancelled();

      if (entry.ucscCytoBandUrl() != null && !entry.ucscCytoBandUrl().isBlank()) {
        Path bands = workDir.resolve("bands.txt");
        String bandsUrl = entry.ucscCytoBandUrl();
        if (isGzipName(bandsUrl)) {
          downloadGunzip(bandsUrl, bands, Phase.BANDS, progress);
        } else {
          // e.g. HPRC T2T cytoband BED (already UCSC-compatible 5 columns)
          downloadRaw(bandsUrl, bands, Phase.BANDS, progress);
        }
        checkCancelled();
      }

      progress.onProgress(Phase.FINALIZE, 0, -1, "Moving into genomes/" + entry.folderId());
      moveReplace(workDir, targetDir);
      progress.onProgress(Phase.FINALIZE, 0, -1, "Installed " + entry.folderId());
      return targetDir;
    } catch (Exception ex) {
      try {
        deleteRecursively(workDir);
      } catch (IOException ignored) {
        // best-effort cleanup
      }
      if (ex instanceof InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw ie;
      }
      if (ex instanceof IOException ioe) {
        throw ioe;
      }
      throw new IOException(ex.getMessage(), ex);
    }
  }

  /**
   * Install from local FASTA (+ optional existing {@code .fai}) and optional GFF/GTF.
   * Gzipped FASTA is decompressed. Folder name must be a simple path segment.
   *
   * @param annotationSource optional; may be {@code null} for reference-only install
   */
  public Path installFromLocal(
      String folderId,
      Path fastaSource,
      Path annotationSource,
      ProgressListener progress)
      throws IOException, InterruptedException {
    cancelled.set(false);
    validateFolderId(folderId);
    if (fastaSource == null || !Files.isRegularFile(fastaSource)) {
      throw new IOException("FASTA file not found");
    }
    if (annotationSource != null && !Files.isRegularFile(annotationSource)) {
      throw new IOException("Annotation (GFF/GTF) file not found");
    }

    Path targetDir = genomesRoot.resolve(folderId);
    Path workDir = genomesRoot.resolve(folderId + ".partial");
    deleteRecursively(workDir);
    Files.createDirectories(workDir.resolve("annotation"));

    try {
      progress.onProgress(Phase.COPY, 0, -1, "Preparing FASTA…");
      String fastaName = stripGz(fastaSource.getFileName().toString());
      if (!fastaName.toLowerCase().endsWith(".fa") && !fastaName.toLowerCase().endsWith(".fasta")) {
        fastaName = fastaName + ".fa";
      }
      Path fastaDest = workDir.resolve(fastaName);
      copyMaybeGunzip(fastaSource, fastaDest);
      checkCancelled();

      Path siblingFai = Path.of(fastaSource.toString() + ".fai");
      Path destFai = Path.of(fastaDest.toString() + ".fai");
      if (Files.isRegularFile(siblingFai) && !isGzipName(fastaSource.getFileName().toString())) {
        Files.copy(siblingFai, destFai, StandardCopyOption.REPLACE_EXISTING);
      } else {
        progress.onProgress(Phase.INDEX, 0, -1, Phase.INDEX.label());
        FastaSequenceIndexCreator.create(fastaDest, true);
      }
      checkCancelled();

      if (annotationSource != null) {
        progress.onProgress(Phase.GFF3, 0, -1, "Copying annotation…");
        String annName = annotationSource.getFileName().toString();
        Path annDest = workDir.resolve("annotation").resolve(annName);
        Files.copy(annotationSource, annDest, StandardCopyOption.REPLACE_EXISTING);
        checkCancelled();
      }

      progress.onProgress(Phase.FINALIZE, 0, -1, "Moving into genomes/" + folderId);
      moveReplace(workDir, targetDir);
      progress.onProgress(Phase.FINALIZE, 0, -1, "Installed " + folderId);
      return targetDir;
    } catch (Exception ex) {
      try {
        deleteRecursively(workDir);
      } catch (IOException ignored) {
        // best-effort cleanup
      }
      if (ex instanceof InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw ie;
      }
      if (ex instanceof IOException ioe) {
        throw ioe;
      }
      throw new IOException(ex.getMessage(), ex);
    }
  }

  /**
   * Copy one or more GFF/GTF files into {@code genomes/<folderId>/annotation/}.
   * The reference genome folder must already exist with an indexed FASTA.
   *
   * @return names of files written under {@code annotation/}
   */
  public List<String> addAnnotations(
      String folderId,
      List<Path> annotationSources,
      ProgressListener progress)
      throws IOException, InterruptedException {
    cancelled.set(false);
    validateFolderId(folderId);
    if (!isInstalled(genomesRoot, folderId)) {
      throw new IOException("Reference genome not installed: " + folderId);
    }
    if (annotationSources == null || annotationSources.isEmpty()) {
      throw new IOException("No annotation files selected");
    }

    Path annotationDir = genomesRoot.resolve(folderId).resolve("annotation");
    Files.createDirectories(annotationDir);
    List<String> written = new ArrayList<>();
    int i = 0;
    for (Path source : annotationSources) {
      checkCancelled();
      if (source == null || !Files.isRegularFile(source)) {
        throw new IOException("Annotation file not found: " + source);
      }
      i++;
      progress.onProgress(
          Phase.GFF3, i, annotationSources.size(), "Copying " + source.getFileName() + "…");
      String name = source.getFileName().toString();
      Path dest = annotationDir.resolve(name);
      Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
      written.add(name);
    }
    progress.onProgress(Phase.FINALIZE, 0, -1, "Added " + written.size() + " annotation(s)");
    return written;
  }

  private static void validateFolderId(String folderId) throws IOException {
    if (folderId == null || folderId.isBlank()) {
      throw new IOException("Genome folder name is required");
    }
    if (folderId.contains("/") || folderId.contains("\\") || folderId.contains("..")) {
      throw new IOException("Invalid genome folder name: " + folderId);
    }
  }

  private static boolean isGzipName(String nameOrUrl) {
    String path = nameOrUrl;
    int q = path.indexOf('?');
    if (q >= 0) {
      path = path.substring(0, q);
    }
    String lower = path.toLowerCase();
    return lower.endsWith(".gz") || lower.endsWith(".bgz");
  }

  private static String stripGz(String name) {
    String lower = name.toLowerCase();
    if (lower.endsWith(".bgz")) {
      return name.substring(0, name.length() - 4);
    }
    if (lower.endsWith(".gz")) {
      return name.substring(0, name.length() - 3);
    }
    return name;
  }

  private static void copyMaybeGunzip(Path source, Path dest) throws IOException {
    if (isGzipName(source.getFileName().toString())) {
      try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(source)));
           OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest))) {
        in.transferTo(out);
      }
    } else {
      Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void checkCancelled() throws InterruptedException {
    if (cancelled.get() || Thread.currentThread().isInterrupted()) {
      throw new InterruptedException("Genome download cancelled");
    }
  }

  private void downloadGunzip(
      String url, Path dest, Phase phase, ProgressListener progress)
      throws IOException, InterruptedException {
    download(url, phase, progress, (in, total) -> {
      try (InputStream gzip = new GZIPInputStream(new ProgressInputStream(in, total, phase, progress));
           OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest))) {
        gzip.transferTo(out);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  private void downloadRaw(
      String url, Path dest, Phase phase, ProgressListener progress)
      throws IOException, InterruptedException {
    download(url, phase, progress, (in, total) -> {
      try (InputStream counted = new ProgressInputStream(in, total, phase, progress);
           OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest))) {
        counted.transferTo(out);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  private void download(
      String url,
      Phase phase,
      ProgressListener progress,
      BiConsumer<InputStream, Long> consumer)
      throws IOException, InterruptedException {
    progress.onProgress(phase, 0, -1, phase.label() + "…");
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofHours(2))
        .header("User-Agent", "BasePlayer2-GenomeInstall/1.0")
        .GET()
        .build();

    HttpResponse<InputStream> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      response.body().close();
      throw new IOException("HTTP " + response.statusCode() + " for " + url);
    }

    long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
    try (InputStream body = new BufferedInputStream(response.body())) {
      try {
        consumer.accept(body, total);
      } catch (UncheckedIOException uio) {
        IOException cause = uio.getCause();
        throw cause != null ? cause : new IOException(uio);
      }
    }
    checkCancelled();
  }

  private final class ProgressInputStream extends InputStream {
    private final InputStream in;
    private final long total;
    private final Phase phase;
    private final ProgressListener progress;
    private long read;
    private long lastReport;

    ProgressInputStream(InputStream in, long total, Phase phase, ProgressListener progress) {
      this.in = in;
      this.total = total;
      this.phase = phase;
      this.progress = progress;
    }

    @Override
    public int read() throws IOException {
      int b = in.read();
      if (b >= 0) {
        bump(1);
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (cancelled.get()) {
        throw new IOException("Cancelled");
      }
      int n = in.read(b, off, len);
      if (n > 0) {
        bump(n);
      }
      return n;
    }

    private void bump(int n) {
      read += n;
      if (read - lastReport >= 1024 * 1024 || (total > 0 && read >= total)) {
        lastReport = read;
        progress.onProgress(phase, read, total, phase.label());
      }
    }

    @Override
    public void close() throws IOException {
      in.close();
    }
  }

  static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
    } catch (UncheckedIOException uio) {
      throw uio.getCause() != null ? uio.getCause() : new IOException(uio);
    }
  }

  /** Prefer atomic move; fall back to copy+delete across filesystems. */
  private static void moveReplace(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException atomicFailed) {
      if (Files.exists(to)) {
        deleteRecursively(to);
      }
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}

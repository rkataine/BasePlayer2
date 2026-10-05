package org.baseplayer.io;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Streams variant-table sheets to an {@code .xlsx} workbook via {@link SXSSFWorkbook}.
 * Only a small window of rows is kept in memory; older rows are flushed to temp files.
 *
 * <p>POI's streaming zip uses data-descriptor entries that LibreOffice on Linux often
 * refuses to open. After SXSSF finishes we rewrite the package with a normal
 * {@link ZipOutputStream} (one entry at a time) so Excel/LibreOffice accept the file.
 */
public final class VariantTableExcelWriter {

    /** Rows kept in memory before flush to disk. */
    private static final int ROW_WINDOW = 200;

    /** Progress / UI update cadence. */
    private static final int PROGRESS_BATCH = 256;

    @FunctionalInterface
    public interface ProgressListener {
        /** {@code current} in {@code [0, total]}; called from the writer thread. */
        void onProgress(int current, int total);
    }

    /**
     * One sheet that can stream rows without materializing the full matrix.
     */
    public interface SheetSource {
        String name();

        List<String> headers();

        /** Best-effort row count for the progress bar; may be 0 if unknown. */
        int estimatedRows();

        /** Emit each data row (header is written by the writer). */
        void writeRows(Consumer<List<String>> rowConsumer);
    }

    /** Character widths by header; POI units are 1/256 of a character. */
    private static final Map<String, Integer> COLUMN_CHARS = Map.ofEntries(
        Map.entry("gene", 14),
        Map.entry("chromosome", 10),
        Map.entry("position", 12),
        Map.entry("start", 12),
        Map.entry("end", 12),
        Map.entry("ref", 5),
        Map.entry("alt", 12),
        Map.entry("type", 10),
        Map.entry("effect", 18),
        Map.entry("length", 10),
        Map.entry("transcript", 16),
        Map.entry("hgvs.c", 16),
        Map.entry("hgvs.p", 16),
        Map.entry("codon", 8),
        Map.entry("sample", 18),
        Map.entry("gt", 8),
        Map.entry("af", 8),
        Map.entry("gq", 6),
        Map.entry("dp", 6),
        Map.entry("site qual", 10),
        Map.entry("cancer gene", 14),
        Map.entry("mate chromosome", 14),
        Map.entry("mate position", 12),
        Map.entry("gene description", 48)
    );

    private VariantTableExcelWriter() {}

    public static void write(Path path, List<SheetSource> sheets, ProgressListener progress)
            throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        if (sheets == null || sheets.isEmpty()) {
            throw new IllegalArgumentException("at least one sheet is required");
        }

        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path sxssfPart = path.resolveSibling(path.getFileName().toString() + ".sxssf.part");
        Path finalPart = path.resolveSibling(path.getFileName().toString() + ".part");
        Files.deleteIfExists(sxssfPart);
        Files.deleteIfExists(finalPart);

        int fillEstimate = estimateFillWork(sheets);
        // fill + zip write + compatibility repack
        int totalWork = fillEstimate + Math.max(fillEstimate / 5, 2);
        int[] done = {0};
        report(progress, 0, totalWork);

        try {
            writeSxssf(sxssfPart, sheets, progress, done, fillEstimate, totalWork);
            report(progress, fillEstimate + Math.max(fillEstimate / 10, 1), totalWork);
            repackCompatibleZip(sxssfPart, finalPart);
            Files.deleteIfExists(sxssfPart);
            try {
                Files.move(finalPart, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(finalPart, path, StandardCopyOption.REPLACE_EXISTING);
            }
            report(progress, totalWork, totalWork);
        } catch (RuntimeException ex) {
            Files.deleteIfExists(sxssfPart);
            Files.deleteIfExists(finalPart);
            throw new IOException(ex.getMessage() != null ? ex.getMessage() : "Excel export failed", ex);
        } catch (IOException ex) {
            Files.deleteIfExists(sxssfPart);
            Files.deleteIfExists(finalPart);
            throw ex;
        }
    }

    private static void writeSxssf(
            Path sxssfPart,
            List<SheetSource> sheets,
            ProgressListener progress,
            int[] done,
            int fillEstimate,
            int totalWork) throws IOException {
        // Do not call flushRows(0) before workbook.write — that produced dimension=A1
        // while sheet XML still held all rows, which some apps reject.
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW)) {
            workbook.setCompressTempFiles(true);
            workbook.setShouldCalculateSheetDimensions(true);

            int sheetsWritten = 0;
            for (SheetSource source : sheets) {
                if (source == null || source.headers() == null || source.headers().isEmpty()) {
                    continue;
                }
                SXSSFSheet sheet = workbook.createSheet(sanitizeSheetName(source.name()));
                // Keep every data row one line tall; long text is clipped in the cell, not wrapped.
                sheet.setDefaultRowHeightInPoints(15f);
                List<String> headers = source.headers();
                Row headerRow = sheet.createRow(0);
                for (int c = 0; c < headers.size(); c++) {
                    String header = xmlSafe(headers.get(c));
                    Cell cell = headerRow.createCell(c);
                    cell.setCellValue(header);
                    sheet.setColumnWidth(c, columnWidthUnits(headers.get(c)));
                }
                sheet.createFreezePane(0, 1);
                done[0]++;
                report(progress, Math.min(done[0], fillEstimate), totalWork);

                int[] rowIndex = {0};
                source.writeRows(values -> {
                    if (values == null) {
                        return;
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IllegalStateException("Excel export cancelled");
                    }
                    Row row = sheet.createRow(rowIndex[0] + 1);
                    row.setHeightInPoints(15f);
                    rowIndex[0]++;
                    int cols = values.size();
                    for (int c = 0; c < cols; c++) {
                        Cell cell = row.createCell(c);
                        cell.setCellValue(xmlSafe(values.get(c)));
                    }
                    done[0]++;
                    if ((rowIndex[0] % PROGRESS_BATCH) == 0) {
                        report(progress, Math.min(done[0], fillEstimate), totalWork);
                    }
                });
                report(progress, Math.min(done[0], fillEstimate), totalWork);
                sheetsWritten++;
            }

            if (sheetsWritten == 0) {
                throw new IOException("No sheets to write");
            }

            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(sxssfPart))) {
                workbook.write(out);
                out.flush();
            }
        }
    }

    /**
     * Rewrite the OPC zip without streaming data-descriptors. LibreOffice (and some
     * file managers) fail on POI streaming zips even when the XML inside is valid.
     */
    private static void repackCompatibleZip(Path sourceZip, Path destZip) throws IOException {
        try (ZipFile zipFile = new ZipFile(sourceZip.toFile());
             ZipOutputStream zos = new ZipOutputStream(
                 new BufferedOutputStream(Files.newOutputStream(destZip)))) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            byte[] buffer = new byte[64 * 1024];
            while (entries.hasMoreElements()) {
                ZipEntry in = entries.nextElement();
                ZipEntry out = new ZipEntry(in.getName());
                zos.putNextEntry(out);
                try (InputStream is = zipFile.getInputStream(in)) {
                    int n;
                    while ((n = is.read(buffer)) >= 0) {
                        zos.write(buffer, 0, n);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    private static int estimateFillWork(List<SheetSource> sheets) {
        int total = 0;
        for (SheetSource source : sheets) {
            if (source == null || source.headers() == null || source.headers().isEmpty()) {
                continue;
            }
            total += 1;
            total += Math.max(0, source.estimatedRows());
        }
        return Math.max(1, total);
    }

    private static int columnWidthUnits(String header) {
        String key = header != null ? header.trim().toLowerCase(Locale.ROOT) : "";
        int chars = COLUMN_CHARS.getOrDefault(key, Math.max(10, key.length() + 2));
        return Math.min(255 * 256, chars * 256);
    }

    private static void report(ProgressListener progress, int current, int total) {
        if (progress != null) {
            progress.onProgress(Math.min(current, total), total);
        }
    }

    private static String sanitizeSheetName(String name) {
        String base = name != null && !name.isBlank() ? name.trim() : "Sheet";
        String cleaned = WorkbookUtil.createSafeSheetName(base);
        return cleaned == null || cleaned.isBlank() ? "Sheet" : cleaned;
    }

    /**
     * Strip characters that are illegal in XML 1.0, and flatten newlines/tabs to spaces
     * so long gene descriptions stay on one spreadsheet row.
     */
    static String xmlSafe(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int len = value.length();
        StringBuilder sb = null;
        for (int i = 0; i < len; ) {
            int cp = value.codePointAt(i);
            int n = Character.charCount(cp);
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                if (sb == null) {
                    sb = new StringBuilder(len);
                    sb.append(value, 0, i);
                }
                sb.append(' ');
            } else if (isXml10Char(cp)) {
                if (sb != null) {
                    sb.appendCodePoint(cp);
                }
            } else {
                if (sb == null) {
                    sb = new StringBuilder(len);
                    sb.append(value, 0, i);
                }
            }
            i += n;
        }
        return sb == null ? value : sb.toString();
    }

    private static boolean isXml10Char(int cp) {
        return cp == 0x9 || cp == 0xA || cp == 0xD
            || (cp >= 0x20 && cp <= 0xD7FF)
            || (cp >= 0xE000 && cp <= 0xFFFD)
            || (cp >= 0x10000 && cp <= 0x10FFFF);
    }
}

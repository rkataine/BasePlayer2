package org.baseplayer.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Writes flat variant-table sheets to an {@code .xlsx} workbook.
 */
public final class VariantTableExcelWriter {

    public record SheetData(String name, List<String> headers, List<List<String>> rows) {}

    @FunctionalInterface
    public interface ProgressListener {
        /** {@code current} in {@code [0, total]}; called from the writer thread. */
        void onProgress(int current, int total);
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

    public static void write(Path path, List<SheetData> sheets) throws IOException {
        write(path, sheets, null);
    }

    public static void write(Path path, List<SheetData> sheets, ProgressListener progress)
            throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        if (sheets == null || sheets.isEmpty()) {
            throw new IllegalArgumentException("at least one sheet is required");
        }

        int fillWork = estimateFillWork(sheets);
        int totalWork = fillWork + Math.max(fillWork / 9, 1);
        int[] done = {0};
        report(progress, done[0], totalWork);

        try (Workbook workbook = new XSSFWorkbook()) {
            CellStyle wrapStyle = workbook.createCellStyle();
            wrapStyle.setWrapText(true);

            for (SheetData data : sheets) {
                if (data == null || data.headers() == null || data.headers().isEmpty()) {
                    continue;
                }
                Sheet sheet = workbook.createSheet(sanitizeSheetName(data.name()));
                List<String> headers = data.headers();
                Row headerRow = sheet.createRow(0);
                int descriptionCol = -1;
                for (int c = 0; c < headers.size(); c++) {
                    String header = headers.get(c) != null ? headers.get(c) : "";
                    Cell cell = headerRow.createCell(c);
                    cell.setCellValue(header);
                    sheet.setColumnWidth(c, columnWidthUnits(header));
                    if ("gene description".equalsIgnoreCase(header.trim())) {
                        descriptionCol = c;
                    }
                }
                sheet.createFreezePane(0, 1);
                done[0]++;
                report(progress, done[0], totalWork);

                List<List<String>> rows = data.rows() != null ? data.rows() : List.of();
                for (int r = 0; r < rows.size(); r++) {
                    List<String> values = rows.get(r);
                    Row row = sheet.createRow(r + 1);
                    int cols = values != null ? values.size() : 0;
                    for (int c = 0; c < cols; c++) {
                        Cell cell = row.createCell(c);
                        String value = values.get(c);
                        cell.setCellValue(value != null ? value : "");
                        if (c == descriptionCol && value != null && !value.isBlank()) {
                            cell.setCellStyle(wrapStyle);
                        }
                    }
                    done[0]++;
                    if ((r & 31) == 0 || r == rows.size() - 1) {
                        report(progress, Math.min(done[0], fillWork), totalWork);
                    }
                }
            }

            if (workbook.getNumberOfSheets() == 0) {
                throw new IOException("No sheets to write");
            }

            report(progress, fillWork, totalWork);
            try (OutputStream out = Files.newOutputStream(path)) {
                workbook.write(out);
            }
            report(progress, totalWork, totalWork);
        }
    }

    private static int estimateFillWork(List<SheetData> sheets) {
        int total = 0;
        for (SheetData data : sheets) {
            if (data == null || data.headers() == null || data.headers().isEmpty()) {
                continue;
            }
            total += 1;
            total += data.rows() != null ? data.rows().size() : 0;
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
}

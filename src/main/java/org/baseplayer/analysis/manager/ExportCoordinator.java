package org.baseplayer.analysis.manager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import org.baseplayer.MainApp;
import org.baseplayer.analysis.groupstat.GroupStatExcelExport;
import org.baseplayer.io.UserPreferences;
import org.baseplayer.io.VariantTableExcelWriter;
import org.baseplayer.samples.SampleGroup;
import org.baseplayer.services.LoadingManager;
import org.baseplayer.services.SampleRegistry;
import org.baseplayer.services.ServiceRegistry;
import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.ui.components.AbstractNestedVariantTable.TableRow;
import org.baseplayer.variant.ui.components.SvVariantTable;
import org.baseplayer.variant.ui.components.VariantExcelExportBuilder;
import org.baseplayer.variant.ui.components.VariantTable;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.Alert;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Excel export orchestration for Analysis Manager (variant sheets + group tabs).
 */
public final class ExportCoordinator {

    private Supplier<Stage> stageSupplier = () -> null;
    private Supplier<VariantTable> pointTable = () -> null;
    private Supplier<SvVariantTable> svTable = () -> null;

    public void bind(
            Supplier<Stage> stageSupplier,
            Supplier<VariantTable> pointTable,
            Supplier<SvVariantTable> svTable) {
        this.stageSupplier = stageSupplier != null ? stageSupplier : () -> null;
        this.pointTable = pointTable != null ? pointTable : () -> null;
        this.svTable = svTable != null ? svTable : () -> null;
    }

    public void exportVariantsToExcel(boolean structural) {
        boolean hasGroupSheets = !GroupStatExcelExport.sheetSourcesFromSession().isEmpty();
        if (structural) {
            SvVariantTable table = svTable.get();
            if ((table == null
                    || table.getDisplayedAllRows() == null
                    || table.getDisplayedAllRows().isEmpty())
                    && !hasGroupSheets) {
                showExcelInfo("No variants available to export.");
                return;
            }
        } else {
            VariantTable table = pointTable.get();
            if ((table == null
                    || (isEmpty(table.getDisplayedCodingRows())
                        && isEmpty(table.getDisplayedIntronicRows())
                        && isEmpty(table.getDisplayedIntergenicRows())))
                    && !hasGroupSheets) {
                showExcelInfo("No variants available to export.");
                return;
            }
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Variants to Excel");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Excel Workbook", "*.xlsx"));
        chooser.setInitialFileName(structural ? "structural_variants.xlsx" : "variants.xlsx");
        File lastDir = UserPreferences.getLastDirectory("XLSX");
        if (lastDir != null) {
            try {
                chooser.setInitialDirectory(lastDir);
            } catch (IllegalArgumentException ignored) {
                // Directory unavailable; FileChooser uses its default.
            }
        }

        Stage stage = stageSupplier.get();
        Window chooserOwner = stage != null ? stage : MainApp.stage;
        org.baseplayer.analysis.ui.SignalPatternHeatmap.prepareForNativeDialog();
        org.baseplayer.analysis.ui.SignalPatternHeatmap.raiseWindow(chooserOwner);
        File chosen;
        try {
            chosen = chooser.showSaveDialog(chooserOwner);
        } finally {
            org.baseplayer.analysis.ui.SignalPatternHeatmap.restoreAfterNativeDialog(chooserOwner);
        }
        if (chosen == null) {
            return;
        }
        File target = chosen.getName().toLowerCase(Locale.ROOT).endsWith(".xlsx")
            ? chosen
            : new File(chosen.getParentFile(), chosen.getName() + ".xlsx");
        UserPreferences.setLastDirectory("XLSX", target);

        if (stage != null) {
            stage.requestFocus();
        } else if (MainApp.stage != null) {
            MainApp.stage.requestFocus();
        }

        final Path path = target.toPath();
        final boolean exportSv = structural;
        Platform.runLater(() -> startExcelExportTask(path, exportSv));
    }

    private void startExcelExportTask(Path path, boolean exportSv) {
        final ThreadRunner.RunnerTask[] taskRef = new ThreadRunner.RunnerTask[1];
        Stage stage = stageSupplier.get();
        taskRef[0] = ThreadRunner.get().submit(
            "Exporting to Excel…",
            () -> {
                try {
                    reportExcelProgress(taskRef[0], "Preparing sheets…", 0, 100);
                    List<VariantTableExcelWriter.SheetSource> sheetsToWrite = exportSv
                        ? buildSvExcelSheets()
                        : buildPointExcelSheets();
                    appendGroupComparisonSheets(sheetsToWrite);
                    if (sheetsToWrite.isEmpty()) {
                        return new IOException("No variants available to export.");
                    }
                    reportExcelProgress(taskRef[0], "Writing rows…", 5, 100);
                    VariantTableExcelWriter.write(
                        path,
                        sheetsToWrite,
                        (current, total) -> {
                            int mapped = 5 + (int) ((current * 90L) / Math.max(1, total));
                            reportExcelProgress(taskRef[0], null, Math.min(95, mapped), 100);
                        });
                    reportExcelProgress(taskRef[0], "Finishing…", 100, 100);
                    return null;
                } catch (Exception ex) {
                    return ex;
                }
            },
            error -> {
                if (error != null) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Export to Excel");
                    alert.setHeaderText("Failed to write Excel file");
                    alert.setContentText(
                        error.getMessage() != null ? error.getMessage() : error.toString());
                    if (stage != null) {
                        alert.initOwner(stage);
                    }
                    alert.showAndWait();
                }
            });
    }

    private static void reportExcelProgress(
            ThreadRunner.RunnerTask task, String suffix, int current, int total) {
        if (task != null && suffix != null) {
            task.setProgressSuffix(suffix);
            ThreadRunner.get().notifyDescriptionChanged();
        }
        LoadingManager.get().setProgress(current, total);
    }

    private void showExcelInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Export to Excel");
        alert.setHeaderText(null);
        alert.setContentText(message);
        Stage stage = stageSupplier.get();
        if (stage != null) {
            alert.initOwner(stage);
        }
        alert.showAndWait();
    }

    private static boolean isEmpty(ObservableList<?> rows) {
        return rows == null || rows.isEmpty();
    }

    private List<VariantTableExcelWriter.SheetSource> buildPointExcelSheets() {
        VariantTable table = pointTable.get();
        if (table == null) {
            return List.of();
        }
        VariantFilter filter = table.getDisplayFilter();
        List<VariantTableExcelWriter.SheetSource> sheets = new ArrayList<>();
        addBuiltSheet(sheets, VariantExcelExportBuilder.pointSheet(
            "Coding", table.getDisplayedCodingRows(), filter, false));
        addBuiltSheet(sheets, VariantExcelExportBuilder.pointSheet(
            "Intronic", table.getDisplayedIntronicRows(), filter, false));
        addBuiltSheet(sheets, VariantExcelExportBuilder.pointSheet(
            "Intergenic", table.getDisplayedIntergenicRows(), filter, true));

        List<TableRow> allPointRows = mergeTableRows(
            table.getDisplayedCodingRows(),
            table.getDisplayedIntronicRows(),
            table.getDisplayedIntergenicRows());
        addSampleGroupSheets(sheets, allPointRows, filter, false);
        return sheets;
    }

    private List<VariantTableExcelWriter.SheetSource> buildSvExcelSheets() {
        SvVariantTable table = svTable.get();
        if (table == null) {
            return List.of();
        }
        VariantFilter filter = table.getDisplayFilter();
        List<TableRow> rows = table.getDisplayedAllRows();
        List<VariantTableExcelWriter.SheetSource> sheets = new ArrayList<>();
        addBuiltSheet(sheets, VariantExcelExportBuilder.structuralSheet(
            "Structural", rows, filter));
        addSampleGroupSheets(sheets, rows, filter, true);
        return sheets;
    }

    private void addSampleGroupSheets(
            List<VariantTableExcelWriter.SheetSource> sheets,
            List<TableRow> rows,
            VariantFilter filter,
            boolean structural) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        SampleRegistry registry = ServiceRegistry.getInstance().getSampleRegistry();
        List<SampleGroup> groups = registry != null ? registry.getSampleGroups() : List.of();
        if (groups == null || groups.isEmpty()) {
            return;
        }
        HashSet<String> usedNames = new HashSet<>();
        for (VariantTableExcelWriter.SheetSource existing : sheets) {
            if (existing != null && existing.name() != null) {
                usedNames.add(existing.name().toLowerCase(Locale.ROOT));
            }
        }
        for (SampleGroup group : groups) {
            if (group == null) {
                continue;
            }
            String sheetName = uniqueGroupSheetName(group.getName(), usedNames);
            usedNames.add(sheetName.toLowerCase(Locale.ROOT));
            var sampleFilter = VariantExcelExportBuilder.sampleInGroup(group);
            if (structural) {
                addBuiltSheet(sheets, VariantExcelExportBuilder.structuralSheet(
                    sheetName, rows, filter, sampleFilter));
            } else {
                addBuiltSheet(sheets, VariantExcelExportBuilder.pointSheet(
                    sheetName, rows, filter, true, sampleFilter));
            }
        }
    }

    private static String uniqueGroupSheetName(String groupName, Set<String> usedLower) {
        String base = (groupName != null && !groupName.isBlank()) ? groupName.trim() : "Group";
        if (usedLower.contains(base.toLowerCase(Locale.ROOT))) {
            base = "Group - " + base;
        }
        String candidate = base;
        int n = 2;
        while (usedLower.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = base + " (" + n + ")";
            n++;
        }
        return candidate;
    }

    @SafeVarargs
    private static List<TableRow> mergeTableRows(List<TableRow>... lists) {
        List<TableRow> merged = new ArrayList<>();
        if (lists == null) {
            return merged;
        }
        for (List<TableRow> list : lists) {
            if (list == null || list.isEmpty()) {
                continue;
            }
            merged.addAll(list);
        }
        return merged;
    }

    private static void addBuiltSheet(
            List<VariantTableExcelWriter.SheetSource> sheets,
            VariantTableExcelWriter.SheetSource sheet) {
        if (sheet != null) {
            sheets.add(sheet);
        }
    }

    private static void appendGroupComparisonSheets(
            List<VariantTableExcelWriter.SheetSource> sheets) {
        if (sheets == null) {
            return;
        }
        HashSet<String> used = new HashSet<>();
        for (VariantTableExcelWriter.SheetSource existing : sheets) {
            if (existing != null && existing.name() != null) {
                used.add(existing.name().toLowerCase(Locale.ROOT));
            }
        }
        for (VariantTableExcelWriter.SheetSource sheet : GroupStatExcelExport.sheetSourcesFromSession()) {
            if (sheet == null) {
                continue;
            }
            String name = sheet.name() != null ? sheet.name() : "Group";
            String unique = name;
            int n = 2;
            while (used.contains(unique.toLowerCase(Locale.ROOT))) {
                unique = name + " (" + n + ")";
                n++;
            }
            used.add(unique.toLowerCase(Locale.ROOT));
            final String sheetName = unique;
            sheets.add(new VariantTableExcelWriter.SheetSource() {
                @Override
                public String name() {
                    return sheetName;
                }

                @Override
                public List<String> headers() {
                    return sheet.headers();
                }

                @Override
                public int estimatedRows() {
                    return sheet.estimatedRows();
                }

                @Override
                public void writeRows(java.util.function.Consumer<List<String>> rowConsumer) {
                    sheet.writeRows(rowConsumer);
                }
            });
        }
    }
}

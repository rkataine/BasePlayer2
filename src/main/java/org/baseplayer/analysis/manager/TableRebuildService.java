package org.baseplayer.analysis.manager;

import org.baseplayer.io.VcfManager;
import org.baseplayer.utils.GeneBiotypeVisibility;
import org.baseplayer.variant.ComparisonProgress;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.VariantNode;
import org.baseplayer.variant.VariantTypeVisuals;
import org.baseplayer.variant.VcfVariantType;
import org.baseplayer.variant.annotation.VariantAnnotation;
import org.baseplayer.variant.annotation.VariantEffect;
import org.baseplayer.variant.ui.components.LohVariantTable;
import org.baseplayer.variant.ui.components.SvVariantTable;
import org.baseplayer.variant.ui.components.VariantTable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Worker-side Point / SV / LOH table row construction for Analysis Manager.
 * Scene-graph binding stays in the shell controller.
 */
public final class TableRebuildService {
    private TableRebuildService() {}

    /** Opaque (to VcfManager) payload from a worker rebuild. */
    public record TableRebuildResult(
        VariantFilter filter,
        List<VariantTable.TableRow> coding,
        List<VariantTable.TableRow> intronic,
        List<VariantTable.TableRow> intergenic,
        List<VariantTable.TableRow> svAll,
        Map<VcfVariantType, List<VariantTable.TableRow>> svByType,
        List<VariantTable.TableRow> lohAll,
        Map<VcfVariantType, List<VariantTable.TableRow>> lohByType,
        boolean redrawCanvases) {}

    /**
     * Build Point/SV/LOH row lists from cached chromosomes. Must not touch the scene graph.
     *
     * @param stageSink optional progress suffix updater (nullable)
     * @param progressSink optional LoadingManager-style progress (nullable)
     */
    public static TableRebuildResult build(
            List<VcfManager.CachedChromosomeVariants> snapshots,
            VariantFilter filterSnapshot,
            boolean reportProgress,
            Consumer<String> stageSink,
            BiConsumer<Integer, Integer> progressSink) {
        List<VariantTable.TableRow> coding = new ArrayList<>();
        List<VariantTable.TableRow> intronic = new ArrayList<>();
        List<VariantTable.TableRow> intergenic = new ArrayList<>();
        List<VariantTable.TableRow> svAll = new ArrayList<>();
        Map<VcfVariantType, List<VariantTable.TableRow>> svByType = new EnumMap<>(VcfVariantType.class);
        for (VcfVariantType type : SvVariantTable.TYPE_ORDER) {
            svByType.put(type, new ArrayList<>());
        }
        List<VariantTable.TableRow> lohAll = new ArrayList<>();
        Map<VcfVariantType, List<VariantTable.TableRow>> lohByType = new EnumMap<>(VcfVariantType.class);
        for (VcfVariantType type : LohVariantTable.TYPE_ORDER) {
            lohByType.put(type, new ArrayList<>());
        }

        try {
            int total = Math.max(1, snapshots.size());
            String cohort = reportProgress ? filterSnapshot.comparisonCohortSummary() : "";
            ComparisonProgress progress = reportProgress
                ? new ComparisonProgress(
                    total,
                    cohort,
                    stage -> {
                        if (stageSink != null) {
                            stageSink.accept(stage);
                        }
                    },
                    (cur, tot) -> {
                        if (progressSink != null) {
                            progressSink.accept(cur, tot);
                        }
                    })
                : null;

            int index = 0;
            for (VcfManager.CachedChromosomeVariants cached : snapshots) {
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }
                index++;
                String sourceChromosome = cached.chromosome();
                VariantList variants = cached.variants();
                if (variants == null || variants.isEmpty()) {
                    if (progress != null) {
                        progress.beginChrom(index, sourceChromosome, 0);
                        progress.finishChrom();
                    }
                    continue;
                }

                if (progress != null) {
                    progress.beginChrom(index, sourceChromosome, variants.size());
                }

                // Basic visible-chain rebuild only. LOH regions come from Calculate LOH.
                if (!filterSnapshot.isLohMode()) {
                    variants.clearLohRegions();
                }
                variants.ensureVisibleChain(filterSnapshot);

                if (progress != null) {
                    progress.setStage("Building tables", 0.98, 1.0);
                }
                appendLohTableRows(
                    sourceChromosome, variants, filterSnapshot, lohAll, lohByType);
                appendVisibleChainTableRows(
                    sourceChromosome, variants, filterSnapshot,
                    coding, intronic, intergenic, svAll, svByType);

                if (progress != null) {
                    progress.finishChrom();
                }
            }
            if (progress != null) {
                progress.done();
            }
            return new TableRebuildResult(
                filterSnapshot,
                coding, intronic, intergenic, svAll, svByType, lohAll, lohByType, reportProgress);
        } catch (Throwable t) {
            System.err.println("Variant table rebuild failed: " + t.getMessage());
            t.printStackTrace();
            return null;
        }
    }

    public static void appendLohTableRows(
            String sourceChromosome,
            VariantList variants,
            VariantFilter filterSnapshot,
            List<VariantTable.TableRow> lohAll,
            Map<VcfVariantType, List<VariantTable.TableRow>> lohByType) {
        for (VariantNode region : variants.getLohRegions()) {
            if (region == null || !VariantTypeVisuals.isLohRegion(region.type)) {
                continue;
            }
            int passSamples = 0;
            for (VariantNode.SampleCall call : region.getSamples()) {
                if (filterSnapshot.passesSampleDisplay(region, call)) {
                    passSamples++;
                }
            }
            if (passSamples <= 0) {
                continue;
            }
            List<String> genes = List.of();
            if (region.annotation != null
                    && region.annotation.overlappingGenes() != null
                    && !region.annotation.overlappingGenes().isEmpty()) {
                genes = region.annotation.overlappingGenes();
            } else if (region.annotation != null
                    && region.annotation.geneName() != null
                    && !region.annotation.geneName().isBlank()) {
                genes = List.of(region.annotation.geneName());
            }
            if (genes.isEmpty()) {
                VariantTable.TableRow row = new VariantTable.TableRow(sourceChromosome, region);
                lohAll.add(row);
                List<VariantTable.TableRow> bucket = lohByType.get(region.type);
                if (bucket != null) {
                    bucket.add(row);
                }
            } else {
                GeneBiotypeVisibility biotypes = GeneBiotypeVisibility.get();
                for (String gene : genes) {
                    if (gene == null || gene.isBlank()) {
                        continue;
                    }
                    String trimmed = gene.trim();
                    if (!biotypes.isVisibleGeneName(trimmed)) {
                        continue;
                    }
                    VariantTable.TableRow row = new VariantTable.TableRow(
                        sourceChromosome, region, List.of(trimmed));
                    lohAll.add(row);
                    List<VariantTable.TableRow> bucket = lohByType.get(region.type);
                    if (bucket != null) {
                        bucket.add(row);
                    }
                }
            }
        }
    }

    /**
     * Emit table rows from the already-built visible chain (no third full-list filter walk).
     * Display-eligible samples come from the chain generation display cache when present.
     */
    public static void appendVisibleChainTableRows(
            String sourceChromosome,
            VariantList variants,
            VariantFilter filterSnapshot,
            List<VariantTable.TableRow> coding,
            List<VariantTable.TableRow> intronic,
            List<VariantTable.TableRow> intergenic,
            List<VariantTable.TableRow> svAll,
            Map<VcfVariantType, List<VariantTable.TableRow>> svByType) {
        VariantFilter svSlice = filterSnapshot.getSvSlice() != null
            ? filterSnapshot.getSvSlice()
            : filterSnapshot;
        boolean svGeneLevel = svSlice.isGeneLevel();
        Map<VcfVariantType, Set<String>> passingGenesByType = null;
        if (svGeneLevel) {
            passingGenesByType = variants.computePassingGenesByType(svSlice);
        }
        GeneBiotypeVisibility biotypes = GeneBiotypeVisibility.get();
        // Snapshot under the list lock so a concurrent chain rebuild cannot empty the walk.
        for (VariantNode node : variants.snapshotVisibleNodes(filterSnapshot)) {
            if (node == null) {
                continue;
            }
            boolean isSv = VariantTypeVisuals.isStructural(node.type);
            int passSamples = filterSnapshot.countDisplaySamples(node);
            if (passSamples <= 0) {
                continue;
            }
            if (isSv && svGeneLevel) {
                Set<String> passing = passingGenesByType != null
                    ? passingGenesByType.get(node.type)
                    : null;
                List<String> displayGenes = VariantList.displayGenesPassing(node, passing);
                if (displayGenes.isEmpty()) {
                    continue;
                }
                for (String gene : displayGenes) {
                    if (gene == null || gene.isBlank()) {
                        continue;
                    }
                    String trimmed = gene.trim();
                    if (!biotypes.isVisibleGeneName(trimmed)) {
                        continue;
                    }
                    VariantTable.TableRow row = new VariantTable.TableRow(
                        sourceChromosome, node, List.of(trimmed));
                    svAll.add(row);
                    List<VariantTable.TableRow> typeBucket = svByType.get(node.type);
                    if (typeBucket != null) {
                        typeBucket.add(row);
                    }
                }
                continue;
            }
            if (!passesGeneBiotypeVisibility(node, biotypes)) {
                continue;
            }
            VariantTable.TableRow row = new VariantTable.TableRow(sourceChromosome, node);
            if (isSv) {
                svAll.add(row);
                List<VariantTable.TableRow> typeBucket = svByType.get(node.type);
                if (typeBucket != null) {
                    typeBucket.add(row);
                }
            } else {
                VariantAnnotation ann = node.annotation;
                VariantEffect effect = ann != null ? ann.effect() : VariantEffect.INTERGENIC;
                if (effect.isCoding() || effect.isSpliceSite() || effect.isRegulatory()) {
                    coding.add(row);
                } else if (effect.isIntronic()) {
                    intronic.add(row);
                } else {
                    intergenic.add(row);
                }
            }
        }
    }

    /** Intergenic / no-gene rows stay; gene-annotated rows respect the gene-canvas legend. */
    private static boolean passesGeneBiotypeVisibility(
            VariantNode node, GeneBiotypeVisibility biotypes) {
        if (node == null || biotypes == null) {
            return true;
        }
        VariantAnnotation ann = node.annotation;
        if (ann == null) {
            return true;
        }
        String geneName = ann.geneName();
        if (geneName == null || geneName.isBlank()) {
            return true;
        }
        return biotypes.isVisibleGeneName(geneName);
    }
}

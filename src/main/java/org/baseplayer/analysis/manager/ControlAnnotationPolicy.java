package org.baseplayer.analysis.manager;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.baseplayer.io.VcfManager;
import org.baseplayer.variant.VariantList;
import org.baseplayer.variant.ui.components.ControlFilesPanel;

/**
 * Shared Point→SV control AF / ClinVar policy: Point runs first (AF + ClinVar);
 * SV control AF is separate and skips re-running ClinVar when Point already owns it.
 */
final class ControlAnnotationPolicy {

    private ControlAnnotationPolicy() {}

    static boolean hasOpenControlOrClinVar(ControlFilesPanel panel) {
        return panel != null
            && (panel.isControlOpen() || panel.clinVarAnnotator().isOpen());
    }

    static boolean pointOwnsClinVar(ControlFilesPanel point) {
        return point != null && point.clinVarAnnotator().isOpen();
    }

    /**
     * Annotate all open panels for currently loaded / cached sources.
     */
    static void annotateAllOpen(
            ControlFilesPanel point,
            ControlFilesPanel sv,
            Consumer<String> progress) {
        if (point != null && hasOpenControlOrClinVar(point)) {
            point.annotateLoadedSources(false, progress);
        }
        annotateSvOpen(point, sv, progress);
    }

    /**
     * Per-list annotate during annotate-all / reload (no LoadingManager bar updates).
     */
    static void annotateList(
            ControlFilesPanel point,
            ControlFilesPanel sv,
            VariantList list,
            Consumer<String> progress) {
        if (list == null) {
            return;
        }
        if (point != null && hasOpenControlOrClinVar(point)) {
            point.annotateIfOpen(list, progress);
        }
        annotateSvList(point, sv, list, progress);
    }

    /**
     * Post-load worker annotate for a single freshly loaded list.
     *
     * @return true if any annotation ran
     */
    static boolean annotatePostLoadList(
            ControlFilesPanel point,
            ControlFilesPanel sv,
            VariantList list,
            Consumer<String> progress,
            Predicate<ControlFilesPanel> needsPoint,
            Predicate<ControlFilesPanel> needsSv) {
        if (list == null) {
            return false;
        }
        boolean doPoint = needsPoint != null && needsPoint.test(point);
        boolean doSv = needsSv != null && needsSv.test(sv);
        if (!doPoint && !doSv) {
            return false;
        }
        if (doPoint && point != null) {
            point.annotateIfOpen(list, progress);
        }
        if (sv != null && sv.isControlOpen()
                && (doSv || sv.needsControlAnnotation(list))) {
            if (pointOwnsClinVar(point)) {
                sv.annotateControlAfIfOpen(list, progress);
            } else if (doSv) {
                sv.annotateIfOpen(list, progress);
            } else {
                sv.annotateControlAfIfOpen(list, progress);
            }
        } else if (doSv && sv != null) {
            sv.annotateIfOpen(list, progress);
        }
        return true;
    }

    static Consumer<VariantList> afterChromosomeHook(
            ControlFilesPanel point,
            ControlFilesPanel sv) {
        if (!hasOpenControlOrClinVar(point) && !hasOpenControlOrClinVar(sv)) {
            return null;
        }
        return list -> annotateList(point, sv, list, null);
    }

    static boolean anySourceNeeds(
            ControlFilesPanel panel,
            List<VcfManager.CachedChromosomeVariants> sources,
            Predicate<VariantList> needs) {
        if (panel == null || sources == null || needs == null) {
            return false;
        }
        for (VcfManager.CachedChromosomeVariants cached : sources) {
            if (cached != null && needs.test(cached.variants())) {
                return true;
            }
        }
        return false;
    }

    private static void annotateSvOpen(
            ControlFilesPanel point,
            ControlFilesPanel sv,
            Consumer<String> progress) {
        if (sv == null) {
            return;
        }
        if (sv.isControlOpen()) {
            if (pointOwnsClinVar(point)) {
                sv.annotateControlAfLoadedSources(progress);
            } else {
                sv.annotateLoadedSources(false, progress);
            }
        } else if (hasOpenControlOrClinVar(sv) && !pointOwnsClinVar(point)) {
            sv.annotateLoadedSources(false, progress);
        }
    }

    private static void annotateSvList(
            ControlFilesPanel point,
            ControlFilesPanel sv,
            VariantList list,
            Consumer<String> progress) {
        if (sv == null) {
            return;
        }
        if (sv.isControlOpen()) {
            if (pointOwnsClinVar(point)) {
                sv.annotateControlAfIfOpen(list, progress);
            } else {
                sv.annotateIfOpen(list, progress);
            }
        } else if (hasOpenControlOrClinVar(sv) && !pointOwnsClinVar(point)) {
            sv.annotateIfOpen(list, progress);
        }
    }
}

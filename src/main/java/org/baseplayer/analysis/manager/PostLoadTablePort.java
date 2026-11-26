package org.baseplayer.analysis.manager;

import org.baseplayer.services.ThreadRunner;
import org.baseplayer.variant.VariantFilter;
import org.baseplayer.variant.VariantList;

/**
 * Region-load continuation for Analysis Manager tables.
 * <p>
 * Worker builds an opaque payload; FX thread applies it. {@link org.baseplayer.io.VcfManager}
 * depends on this port only — not the FXML controller.
 */
public interface PostLoadTablePort {

    /**
     * Background continuation of a region load (same ThreadRunner task as gene annotate):
     * optional control AF, then table row construction. Must not touch the scene graph.
     *
     * @return opaque payload for {@link #applyPostLoadTables}, or {@code null}
     */
    Object buildPostLoadTablesOnWorker(
        VariantList list,
        String chrom,
        VariantFilter filter,
        ThreadRunner.RunnerTask loadTask);

    /**
     * Apply tables built on the region-load worker. Stamps revision so a subsequent
     * {@code loadData} does not start another annotate/table task.
     */
    void applyPostLoadTables(Object opaqueResult, VariantList list);
}

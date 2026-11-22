package org.baseplayer.analysis.source;

import java.util.List;

import org.baseplayer.analysis.CollectContext;
import org.baseplayer.analysis.grid.FeatureValueMatrix;
import org.baseplayer.analysis.signal.SignalSourceSpec;
import org.baseplayer.analysis.ValueAggregation;
import org.baseplayer.samples.SampleTrack;

/**
 * Strategy that fills a {@link FeatureValueMatrix} from per-sample data files.
 */
public interface ValueSource {

  String label();

  /**
   * Collect values for the given sample tracks into {@code matrix}.
   * @return human-readable notes (files used, skips), never null
   */
  String collect(List<SampleTrack> tracks, FeatureValueMatrix matrix, SignalSourceSpec spec);

  /**
   * Collect with progress / cooperative cancel. Default ignores {@code ctx}.
   * Implementations should call {@link CollectContext#checkCancelled()} in loops
   * and {@link CollectContext#progress(int, int, String)} as work advances.
   */
  default String collect(
      List<SampleTrack> tracks,
      FeatureValueMatrix matrix,
      SignalSourceSpec spec,
      CollectContext ctx) {
    return collect(tracks, matrix, spec);
  }

  /** Legacy entry used by callers that only pass aggregation. */
  default String collect(List<SampleTrack> tracks, FeatureValueMatrix matrix, ValueAggregation aggregation) {
    SignalSourceSpec spec = new SignalSourceSpec();
    if (aggregation != null) {
      spec.setAggregation(aggregation);
    }
    return collect(tracks, matrix, spec);
  }
}

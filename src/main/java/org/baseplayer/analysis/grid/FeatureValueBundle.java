package org.baseplayer.analysis.grid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.baseplayer.samples.SampleTrack;
import org.baseplayer.analysis.signal.SignalChannel;

/**
 * Multi-channel gene×sample values: one {@link FeatureValueMatrix} per channel id.
 */
public final class FeatureValueBundle {

  private final List<SignalChannel> channels;
  private final Map<String, FeatureValueMatrix> byChannel = new LinkedHashMap<>();

  public FeatureValueBundle(List<SignalChannel> channels) {
    this.channels = channels != null ? List.copyOf(channels) : List.of();
    for (SignalChannel ch : this.channels) {
      byChannel.put(ch.id(), new FeatureValueMatrix());
    }
  }

  public List<SignalChannel> channels() {
    return channels;
  }

  public FeatureValueMatrix matrix(String channelId) {
    return byChannel.get(channelId);
  }

  public FeatureValueMatrix primaryMatrix() {
    if (channels.isEmpty()) {
      return new FeatureValueMatrix();
    }
    FeatureValueMatrix m = byChannel.get(channels.get(0).id());
    return m != null ? m : new FeatureValueMatrix();
  }

  public void put(String channelId, String unitId, SampleTrack track, double value) {
    FeatureValueMatrix m = byChannel.get(channelId);
    if (m != null) {
      m.put(unitId, track, value);
    }
  }

  public void putLocus(String unitId, String chromosome, long start1Based, long end1Based) {
    for (FeatureValueMatrix m : byChannel.values()) {
      m.putLocus(unitId, chromosome, start1Based, end1Based);
    }
  }

  public void setMissingDefault(Double missingDefault) {
    for (FeatureValueMatrix m : byChannel.values()) {
      m.setMissingDefault(missingDefault);
    }
  }

  public boolean isEmpty() {
    for (FeatureValueMatrix m : byChannel.values()) {
      if (m != null && !m.isEmpty()) {
        return false;
      }
    }
    return true;
  }

  public Set<String> unitIds() {
    Set<String> ids = new LinkedHashSet<>();
    for (FeatureValueMatrix m : byChannel.values()) {
      if (m != null) {
        ids.addAll(m.unitIds());
      }
    }
    return ids;
  }

  public List<SampleTrack> tracks() {
    LinkedHashSet<SampleTrack> tracks = new LinkedHashSet<>();
    for (FeatureValueMatrix m : byChannel.values()) {
      if (m != null) {
        tracks.addAll(m.tracks());
      }
    }
    return new ArrayList<>(tracks);
  }

  public Map<String, FeatureValueMatrix> asMap() {
    return Collections.unmodifiableMap(byChannel);
  }
}

package org.baseplayer.analysis.signal;

import java.util.Objects;

import org.baseplayer.io.table.MappedTableColumnRole;

/**
 * One numeric Signal channel (file column) with type and absolute-value thresholds.
 */
public final class SignalChannel {

  private final String id;
  private final String label;
  private final SignalValueType type;
  private final MappedTableColumnRole sourceRole;
  private SignalValueThresholds thresholds;

  public SignalChannel(
      String id,
      String label,
      SignalValueType type,
      MappedTableColumnRole sourceRole,
      SignalValueThresholds thresholds) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("channel id required");
    }
    this.id = id;
    this.label = label != null && !label.isBlank() ? label : id;
    this.type = type != null ? type : SignalValueType.RAW;
    this.sourceRole = sourceRole;
    this.thresholds = thresholds != null
        ? thresholds.copy()
        : SignalValueThresholds.defaultsFor(this.type);
  }

  public String id() {
    return id;
  }

  public String label() {
    return label;
  }

  public SignalValueType type() {
    return type;
  }

  public MappedTableColumnRole sourceRole() {
    return sourceRole;
  }

  public SignalValueThresholds thresholds() {
    return thresholds;
  }

  public void setThresholds(SignalValueThresholds thresholds) {
    this.thresholds = thresholds != null
        ? thresholds.copy()
        : SignalValueThresholds.defaultsFor(type);
  }

  public SignalChannel withType(SignalValueType newType) {
    SignalValueType t = newType != null ? newType : SignalValueType.RAW;
    return new SignalChannel(id, label, t, sourceRole, SignalValueThresholds.defaultsFor(t));
  }

  public SignalChannel copy() {
    return new SignalChannel(id, label, type, sourceRole, thresholds);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SignalChannel that)) {
      return false;
    }
    return Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}

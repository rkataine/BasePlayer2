package org.baseplayer.controllers;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.SplitPane;

/**
 * Unified controller for synchronizing all sidebar split panes.
 * Ensures dividers stay aligned across gene annotation, feature tracks, and sample data panels.
 */
public class SidebarController {
  
  private static final double DEFAULT_DIVIDER_POSITION = 0.15;
  private static final double CORRUPTION_THRESHOLD = 0.3;
  
  private final List<SplitPane> syncedPanes = new ArrayList<>();
  private double targetPosition = DEFAULT_DIVIDER_POSITION;
  private boolean isUpdating = false;
  private boolean isActive = false;
  
  // Corruption prevention listeners (removed after initial layout stabilizes)
  private final List<ChangeListener<Number>> corruptionListeners = new ArrayList<>();
  
  /**
   * Add a split pane to be synchronized.
   */
  public void addPane(SplitPane pane) {
    if (pane == null || pane.getDividers().isEmpty()) return;
    
    syncedPanes.add(pane);
    
    // Set initial position
    pane.setDividerPosition(0, targetPosition);
    
    // Add synchronization listener
    pane.getDividers().get(0).positionProperty().addListener((obs, oldVal, newVal) -> {
      if (!isActive || isUpdating) return;
      syncDividers(newVal.doubleValue());
    });
    
    // Add corruption prevention listener
    ChangeListener<Number> corruptionListener = (obs, oldVal, newVal) -> {
      if (newVal.doubleValue() > CORRUPTION_THRESHOLD && 
          oldVal.doubleValue() < CORRUPTION_THRESHOLD) {
        Platform.runLater(() -> pane.setDividerPosition(0, targetPosition));
      }
    };
    corruptionListeners.add(corruptionListener);
    pane.getDividers().get(0).positionProperty().addListener(corruptionListener);
  }
  
  /**
   * Synchronize all panes to the given position.
   */
  private void syncDividers(double position) {
    if (isUpdating) return;
    
    isUpdating = true;
    targetPosition = position;
    
    for (SplitPane pane : syncedPanes) {
      if (!pane.getDividers().isEmpty()) {
        double currentPos = pane.getDividerPositions()[0];
        if (Math.abs(currentPos - position) > 0.001) {
          pane.setDividerPosition(0, position);
        }
      }
    }
    
    isUpdating = false;
  }
  
  /**
   * Enable synchronization (call after window is shown and stable).
   */
  public void setActive(boolean active) {
    this.isActive = active;
    
    if (active) {
      // Remove corruption listeners - no longer needed after layout is stable
      for (int i = 0; i < syncedPanes.size(); i++) {
        SplitPane pane = syncedPanes.get(i);
        if (i < corruptionListeners.size() && !pane.getDividers().isEmpty()) {
          pane.getDividers().get(0).positionProperty().removeListener(corruptionListeners.get(i));
        }
      }
      corruptionListeners.clear();
      
      // Force sync to ensure all panes are aligned
      syncDividers(targetPosition);
    }
  }
  
  /**
   * Get the current divider position.
   */
  public double getDividerPosition() {
    return targetPosition;
  }
  
  /**
   * Set divider position for all panes.
   */
  public void setDividerPosition(double position) {
    targetPosition = position;
    syncDividers(position);
  }
  
  /**
   * Lock sidebar constraints after initial layout.
   */
  public void lockConstraints() {
    // Force all panes to the target position
    for (SplitPane pane : syncedPanes) {
      if (!pane.getDividers().isEmpty()) {
        pane.setDividerPosition(0, targetPosition);
      }
    }
  }
}

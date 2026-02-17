package org.baseplayer.services;

import java.util.List;

import org.baseplayer.draw.DrawFunctions;
import org.baseplayer.draw.DrawSampleData;
import org.baseplayer.draw.DrawStack;
import org.baseplayer.draw.SideBarStack;
import org.baseplayer.tracks.FeatureTracksSidebar;
import org.baseplayer.utils.BaseUtils;

import javafx.beans.property.IntegerProperty;
import javafx.scene.control.SplitPane;

/**
 * Coordinates events and communication between components.
 * Handles draw updates, divider synchronization, memory monitoring.
 */
public class EventCoordinator {
  
  private final Runtime runtime = Runtime.getRuntime();
  private final List<DrawStack> drawStacks;
  private FeatureTracksSidebar featureTracksSidebar;
  private SideBarStack sideBarStack;
  
  public EventCoordinator(List<DrawStack> drawStacks) {
    this.drawStacks = drawStacks;
  }
  
  public void setFeatureTracksSidebar(FeatureTracksSidebar sidebar) {
    this.featureTracksSidebar = sidebar;
  }
  
  public void setSideBarStack(SideBarStack sideBarStack) {
    this.sideBarStack = sideBarStack;
  }
  
  /**
   * Setup draw update listener that triggers redraws across all components.
   */
  public void setupDrawUpdateListener(IntegerProperty memoryUsage) {
    DrawSampleData.update.addListener((observable, oldValue, newValue) -> {
      // Always draw all stacks so that data updates (e.g. BAM fetch completion)
      // are reflected everywhere, not just on the hover stack
      for (DrawStack pane : drawStacks) {
        pane.cytobandCanvas.draw();
        pane.chromCanvas.draw();
        pane.drawCanvas.draw();
      }
      
      // Update feature tracks when region changes
      for (DrawStack stack : drawStacks) {
        if (stack.featureTracksCanvas != null) {
          stack.featureTracksCanvas.draw();
        }
      }
      
      if (featureTracksSidebar != null) {
        featureTracksSidebar.draw();
      }
      
      // Update track info sidebar
      if (sideBarStack != null && sideBarStack.trackInfo != null) {
        sideBarStack.trackInfo.draw();
      }
      
      // Update memory usage
      memoryUsage.set(BaseUtils.toMegabytes.apply(runtime.totalMemory() - runtime.freeMemory()));
    });
  }
  
  /**
   * Synchronize divider positions across multiple split panes.
   * When one divider moves, the corresponding dividers in other panes move too.
   */
  public static void synchronizeDividers(SplitPane drawPane, SplitPane chromPane, SplitPane featureTracksPane) {
    // Listen to drawPane dividers and sync to others
    drawPane.getDividers().forEach(divider -> {
      divider.positionProperty().addListener((obs, oldVal, newVal) -> {
        int index = drawPane.getDividers().indexOf(divider);
        if (index < chromPane.getDividers().size()) {
          chromPane.getDividers().get(index).setPosition(newVal.doubleValue());
        }
        if (index < featureTracksPane.getDividers().size()) {
          featureTracksPane.getDividers().get(index).setPosition(newVal.doubleValue());
        }
      });
    });
    
    // Listen to chromPane dividers and sync to others
    chromPane.getDividers().forEach(divider -> {
      divider.positionProperty().addListener((obs, oldVal, newVal) -> {
        int index = chromPane.getDividers().indexOf(divider);
        if (index < drawPane.getDividers().size()) {
          drawPane.getDividers().get(index).setPosition(newVal.doubleValue());
        }
        if (index < featureTracksPane.getDividers().size()) {
          featureTracksPane.getDividers().get(index).setPosition(newVal.doubleValue());
        }
      });
    });
    
    // Listen to featureTracksPane dividers and sync to others
    featureTracksPane.getDividers().forEach(divider -> {
      divider.positionProperty().addListener((obs, oldVal, newVal) -> {
        int index = featureTracksPane.getDividers().indexOf(divider);
        if (index < drawPane.getDividers().size()) {
          drawPane.getDividers().get(index).setPosition(newVal.doubleValue());
        }
        if (index < chromPane.getDividers().size()) {
          chromPane.getDividers().get(index).setPosition(newVal.doubleValue());
        }
      });
    });
  }
  
  /**
   * Take snapshots of all draw canvases for smooth scrolling/zooming.
   */
  public void takeCanvasSnapshots() {
    for (DrawStack pane : drawStacks) {
      pane.drawCanvas.snapshot = pane.drawCanvas.snapshot(null, null);
    }
  }
  
  /**
   * Trigger a full redraw update.
   */
  public static void triggerUpdate() {
    DrawFunctions.update.set(!DrawFunctions.update.get());
  }
}

package org.baseplayer.components;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

import org.baseplayer.draw.DrawStack;
import org.baseplayer.services.DrawStackManager;
import org.baseplayer.services.ServiceRegistry;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.paint.Color;

/**
 * Handles locus navigation history (undo/redo) for the menubar controls.
 */
public class NavigationUndoComponent {

  private static final int MAX_NAV_HISTORY = 200;
  private static final Color HISTORY_ACTIVE = Color.web("#7e99b4");
  private static final Color HISTORY_DISABLED = Color.web("#555555");

  private record NavigationLocation(String chromosome, double start, double end) {}

  private static NavigationUndoComponent activeInstance;

  private final DrawStackManager stackManager = ServiceRegistry.getInstance().getDrawStackManager();
  private final Deque<NavigationLocation> undoHistory = new ArrayDeque<>();
  private final Deque<NavigationLocation> redoHistory = new ArrayDeque<>();

  private final Button undoButton;
  private final Button redoButton;
  private FontIcon undoIcon;
  private FontIcon redoIcon;

  private boolean applyingHistoryNavigation = false;

  public NavigationUndoComponent(Button undoButton, Button redoButton) {
    this.undoButton = undoButton;
    this.redoButton = redoButton;
    setupButtons();
    activeInstance = this;
  }

  public static void pushCurrentNavigationToUndo() {
    if (activeInstance != null) {
      activeInstance.pushCurrentNavigationToUndoInternal();
    }
  }

  public void updateButtonStates() {
    boolean canUndo = !undoHistory.isEmpty();
    boolean canRedo = !redoHistory.isEmpty();

    if (undoButton != null) undoButton.setDisable(!canUndo);
    if (redoButton != null) redoButton.setDisable(!canRedo);
    if (undoIcon != null) undoIcon.setIconColor(canUndo ? HISTORY_ACTIVE : HISTORY_DISABLED);
    if (redoIcon != null) redoIcon.setIconColor(canRedo ? HISTORY_ACTIVE : HISTORY_DISABLED);
  }

  private void setupButtons() {
    if (undoButton != null) {
      undoIcon = new FontIcon(FontAwesomeSolid.UNDO);
      undoIcon.setIconSize(13);
      undoIcon.setIconColor(HISTORY_DISABLED);
      undoButton.setText("");
      undoButton.setGraphic(undoIcon);
      undoButton.setTooltip(new Tooltip("Undo navigation"));
      undoButton.setOnAction(e -> undoNavigation());
    }

    if (redoButton != null) {
      redoIcon = new FontIcon(FontAwesomeSolid.REDO);
      redoIcon.setIconSize(13);
      redoIcon.setIconColor(HISTORY_DISABLED);
      redoButton.setText("");
      redoButton.setGraphic(redoIcon);
      redoButton.setTooltip(new Tooltip("Redo navigation"));
      redoButton.setOnAction(e -> redoNavigation());
    }

    updateButtonStates();
  }

  private void pushCurrentNavigationToUndoInternal() {
    if (applyingHistoryNavigation) return;

    NavigationLocation current = captureCurrentLocation();
    if (current == null) return;
    if (!undoHistory.isEmpty() && undoHistory.peek().equals(current)) return;

    undoHistory.push(current);
    while (undoHistory.size() > MAX_NAV_HISTORY) {
      undoHistory.removeLast();
    }

    redoHistory.clear();
    updateButtonStates();
  }

  private NavigationLocation captureCurrentLocation() {
    DrawStack stack = stackManager.getHoverStack();
    if (stack == null && !stackManager.getStacks().isEmpty()) {
      stack = stackManager.getFirst();
    }
    if (stack == null) return null;
    return new NavigationLocation(stack.chromosome, stack.start, stack.end);
  }

  private void undoNavigation() {
    if (undoHistory.isEmpty()) return;

    NavigationLocation target = undoHistory.pop();
    NavigationLocation current = captureCurrentLocation();
    if (current != null) {
      redoHistory.push(current);
      while (redoHistory.size() > MAX_NAV_HISTORY) {
        redoHistory.removeLast();
      }
    }

    applyingHistoryNavigation = true;
    navigateToHistoryLocation(target);
    applyingHistoryNavigation = false;
    updateButtonStates();
  }

  private void redoNavigation() {
    if (redoHistory.isEmpty()) return;

    NavigationLocation target = redoHistory.pop();
    NavigationLocation current = captureCurrentLocation();
    if (current != null) {
      undoHistory.push(current);
      while (undoHistory.size() > MAX_NAV_HISTORY) {
        undoHistory.removeLast();
      }
    }

    applyingHistoryNavigation = true;
    navigateToHistoryLocation(target);
    applyingHistoryNavigation = false;
    updateButtonStates();
  }

  private void navigateToHistoryLocation(NavigationLocation location) {
    if (location == null) return;

    DrawStack stack = stackManager.getHoverStack();
    if (stack == null && !stackManager.getStacks().isEmpty()) {
      stack = stackManager.getFirst();
    }
    if (stack == null) return;

    if (!Objects.equals(location.chromosome(), stack.chromosome)) {
      stack.switchToChromosome(location.chromosome());
    }
    stack.alignmentCanvas.zoomAnimation(location.start(), location.end());
  }
}

package org.baseplayer.components;

import java.util.Map;
import java.util.WeakHashMap;

import org.baseplayer.ui.theme.AppTheme;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Effect;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * Soft pulsed glow that can be attached to any JavaFX {@link Node} (buttons,
 * text fields, labels, …) to draw attention. Uses {@link Node#setEffect}; does
 * not wrap or replace the control in the scene graph.
 *
 * <pre>{@code
 * AttentionGlow.start(applyButton);
 * // …
 * AttentionGlow.stop(applyButton);
 * }</pre>
 */
public final class AttentionGlow {

  private static final Duration PULSE = Duration.millis(450);
  private static final int PULSE_CYCLES = 3;
  private static final double RADIUS_LO = 8;
  private static final double RADIUS_HI = 18;
  private static final double OPACITY = 0.9;

  private static final Map<Node, Session> SESSIONS = new WeakHashMap<>();

  private AttentionGlow() {}

  /** True when a glow session is active on {@code node}. */
  public static boolean isActive(Node node) {
    return node != null && SESSIONS.containsKey(node);
  }

  /**
   * Start (or restart) an accent glow pulse on {@code node}. After a few pulses
   * the glow holds steady until {@link #stop(Node)}.
   */
  public static void start(Node node) {
    if (node == null) {
      return;
    }
    stop(node);

    Effect previous = node.getEffect();
    DropShadow glow = new DropShadow();
    glow.setColor(accentColor(OPACITY));
    glow.setRadius(RADIUS_LO);
    glow.setSpread(0.35);
    glow.setOffsetX(0);
    glow.setOffsetY(0);
    node.setEffect(glow);

    // Pulse radius only (Color keyframes need a custom interpolator).
    Timeline pulse = new Timeline(
        new KeyFrame(Duration.ZERO, new KeyValue(glow.radiusProperty(), RADIUS_LO)),
        new KeyFrame(PULSE, new KeyValue(glow.radiusProperty(), RADIUS_HI)));
    pulse.setCycleCount(PULSE_CYCLES * 2);
    pulse.setAutoReverse(true);
    pulse.setOnFinished(e -> glow.setRadius(RADIUS_HI));
    pulse.play();

    SESSIONS.put(node, new Session(previous, glow, pulse));
  }

  /**
   * Stop the glow on {@code node} and restore any effect that was present before
   * {@link #start}. Safe to call when no session is active.
   */
  public static void stop(Node node) {
    if (node == null) {
      return;
    }
    Session session = SESSIONS.remove(node);
    if (session == null) {
      return;
    }
    session.timeline.stop();
    if (node.getEffect() == session.glow) {
      node.setEffect(session.previous);
    }
  }

  private static Color accentColor(double opacity) {
    Color base = Color.web(AppTheme.chrome().focusHex());
    return Color.color(base.getRed(), base.getGreen(), base.getBlue(), opacity);
  }

  private record Session(Effect previous, DropShadow glow, Timeline timeline) {}
}

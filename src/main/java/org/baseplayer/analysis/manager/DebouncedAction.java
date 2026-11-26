package org.baseplayer.analysis.manager;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

/**
 * One-shot Timeline debounce: stop + playFromStart, with optional next-pulse before restart.
 */
final class DebouncedAction {
    private final Timeline timeline;
    private final Runnable fallback;
    private final boolean requestPulse;

    DebouncedAction(Duration delay, Runnable action) {
        this(delay, action, false);
    }

    DebouncedAction(Duration delay, Runnable action, boolean requestPulse) {
        this.fallback = action;
        this.requestPulse = requestPulse;
        this.timeline = new Timeline(new KeyFrame(delay, e -> action.run()));
        this.timeline.setCycleCount(1);
    }

    void schedule() {
        if (requestPulse) {
            Platform.requestNextPulse();
        }
        if (timeline != null) {
            timeline.stop();
            timeline.playFromStart();
        } else if (fallback != null) {
            fallback.run();
        }
    }

    void stop() {
        if (timeline != null) {
            timeline.stop();
        }
    }
}

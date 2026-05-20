package org.baseplayer;

/**
 * Non-JavaFX entry point for fat JAR execution.
 * JavaFX Application subclasses cannot be launched directly from a fat JAR
 * due to module system checks — this wrapper bypasses that restriction.
 */
public class Launcher {
    public static void main(String[] args) {
        MainApp.main(args);
    }
}

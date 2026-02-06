package org.baseplayer.utils;

import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Central font configuration for the entire application.
 * Uses VS Code-inspired font stacks for consistent cross-platform appearance.
 */
public class AppFonts {
    
    // UI Font - matches VS Code's UI font
    // Windows: Segoe UI, Linux: Ubuntu/system, macOS: -apple-system
    private static final String UI_FONT_FAMILY;
    private static final String UI_FONT_FAMILY_FALLBACK = "system-ui";
    
    // Monospace Font - for reference bases, coordinates, etc.
    // Matches VS Code's editor font preferences
    private static final String MONO_FONT_FAMILY;
    
    static {
        String os = System.getProperty("os.name").toLowerCase();
        
        if (os.contains("win")) {
            UI_FONT_FAMILY = "Segoe UI";
            MONO_FONT_FAMILY = "Consolas";
        } else if (os.contains("mac")) {
            UI_FONT_FAMILY = "SF Pro Text";
            MONO_FONT_FAMILY = "Menlo";
        } else {
            // Linux
            UI_FONT_FAMILY = "Ubuntu";
            MONO_FONT_FAMILY = "DejaVu Sans Mono";
        }
    }
    
    // Standard font sizes
    public static final double SIZE_SMALL = 9;
    public static final double SIZE_NORMAL = 12;
    public static final double SIZE_LARGE = 14;
    
    /**
     * Get the standard UI font at normal size (12pt)
     */
    public static Font getUIFont() {
        return getUIFont(SIZE_NORMAL);
    }
    
    /**
     * Get the UI font at specified size
     */
    public static Font getUIFont(double size) {
        Font font = Font.font(UI_FONT_FAMILY, size);
        // Fallback if primary font not available
        if (font.getFamily().equals("System")) {
            font = Font.font(UI_FONT_FAMILY_FALLBACK, size);
        }
        return font;
    }
    
    /**
     * Get a bold UI font at specified size
     */
    public static Font getBoldFont(double size) {
        Font font = Font.font(UI_FONT_FAMILY, FontWeight.BOLD, size);
        if (font.getFamily().equals("System")) {
            font = Font.font(UI_FONT_FAMILY_FALLBACK, FontWeight.BOLD, size);
        }
        return font;
    }
    
    /**
     * Get the monospace font at normal size (12pt)
     */
    public static Font getMonoFont() {
        return getMonoFont(SIZE_NORMAL);
    }
    
    /**
     * Get the monospace font at specified size
     */
    public static Font getMonoFont(double size) {
        Font font = Font.font(MONO_FONT_FAMILY, size);
        // Fallback to generic monospace if primary not available
        if (font.getFamily().equals("System")) {
            font = Font.font("Monospace", size);
        }
        return font;
    }
    
    /**
     * Get the UI font family name for CSS usage
     */
    public static String getUIFontFamily() {
        return UI_FONT_FAMILY;
    }
    
    /**
     * Get the monospace font family name for CSS usage
     */
    public static String getMonoFontFamily() {
        return MONO_FONT_FAMILY;
    }
}

package org.baseplayer.controllers.commands;

import org.baseplayer.MainApp;
import org.baseplayer.controllers.MainController;
import org.baseplayer.controllers.SettingsDialog;

import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Handles view and UI operations: stack management, dark mode, window controls, settings.
 */
public class ViewCommands {
  
  /**
   * Add a new draw stack.
   */
  public static void addStack() {
    MainController.addStack(true);
  }
  
  /**
   * Remove the last draw stack.
   */
  public static void removeStack() {
    MainController.addStack(false);
  }
  
  /**
   * Toggle dark mode.
   */
  public static void toggleDarkMode() {
    MainApp.setDarkMode();
  }
  
  /**
   * Run garbage collection to free memory.
   */
  public static void cleanMemory() {
    System.gc();
  }
  
  /**
   * Open the settings dialog.
   */
  public static void openSettings() {
    try {
      new SettingsDialog().show();
    } catch (Exception e) {
      System.err.println("Error opening settings: " + e.getMessage());
    }
  }
  
  /**
   * Minimize the application window.
   */
  public static void minimizeWindow() {
    Window window = MainApp.stage.getScene().getWindow();
    if (window instanceof Stage stage) {
      stage.setIconified(true);
    }
  }
  
  /**
   * Toggle maximize/restore the application window.
   */
  public static void maximizeWindow() {
    Window window = MainApp.stage.getScene().getWindow();
    if (window instanceof Stage stage) {
      if (stage.isMaximized()) {
        Stage newStage = new Stage(StageStyle.DECORATED);
        newStage.setScene(MainApp.stage.getScene());
        newStage.getIcons().add(MainApp.icon);
        newStage.setTitle("BasePlayer 2");
        MainApp.stage.close();
        MainApp.stage = newStage;
        MainApp.stage.show();
        MainApp.stage.setMaximized(false);
      } else {
        Stage newStage = new Stage(StageStyle.UNDECORATED);
        newStage.setScene(MainApp.stage.getScene());
        MainApp.stage.close();
        MainApp.stage = newStage;
        MainApp.stage.show();
        MainApp.stage.setMaximized(true);
      }
    }
  }
  
  /**
   * Close the application window.
   */
  public static void closeWindow() {
    Window window = MainApp.stage.getScene().getWindow();
    if (window instanceof Stage stage) {
      stage.close();
    }
  }
}

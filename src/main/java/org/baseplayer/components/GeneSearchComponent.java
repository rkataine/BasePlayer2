package org.baseplayer.components;

import java.util.ArrayList;
import java.util.List;

import org.baseplayer.annotation.AnnotationData;
import org.baseplayer.controllers.MenuBarController;
import org.baseplayer.controllers.commands.NavigationCommands;
import org.baseplayer.controllers.commands.SearchCommands;
import org.baseplayer.genome.gene.GeneLocation;
import org.baseplayer.services.ViewportState;
import org.baseplayer.utils.GeneColors;

import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/**
 * Encapsulates gene-name autocomplete search behaviour for the menu bar.
 * Attach to a {@link TextField} by constructing {@code new GeneSearchComponent(field, viewportState)}.
 * Extracted from {@link MenuBarController#setupGeneSearch()}.
 */
public class GeneSearchComponent {

  private final TextField geneSearchField;
  private final ViewportState viewportState;
  private final ContextMenu autoComplete;
  private List<String> currentSuggestions = new ArrayList<>();
  private final List<HBox> suggestionContainers = new ArrayList<>();
  private int selectedSuggestionIndex = -1;

  public GeneSearchComponent(TextField geneSearchField, ViewportState viewportState) {
    this.geneSearchField = geneSearchField;
    this.viewportState = viewportState;
    this.autoComplete = new ContextMenu();
    this.autoComplete.setAutoHide(true);
    setup();
  }

  private void setup() {
    // Event filter on ContextMenu to intercept Enter/Up/Down/Escape before JavaFX consumes them
    autoComplete.addEventFilter(KeyEvent.KEY_PRESSED, ke -> {
      if (null != ke.getCode()) switch (ke.getCode()) {
        case ENTER -> {
          if (selectedSuggestionIndex >= 0 && selectedSuggestionIndex < currentSuggestions.size()) {
            String geneToNavigate = currentSuggestions.get(selectedSuggestionIndex);
            autoComplete.hide();
            selectedSuggestionIndex = -1;
            navigateToGene(geneToNavigate);
            ke.consume();
          } else {
            String text = geneSearchField.getText();
            if (text != null && !text.isEmpty()) {
              autoComplete.hide();
              List<String> suggestions = AnnotationData.searchGenes(text);
              if (!suggestions.isEmpty()) {
                String exactMatch = suggestions.stream()
                    .filter(s -> s.equalsIgnoreCase(text))
                    .findFirst()
                    .orElse(suggestions.get(0));
                navigateToGene(exactMatch);
              } else {
                navigateToGene(text);
              }
            }
            ke.consume();
          }
        }
        case DOWN, TAB -> {
          if (!currentSuggestions.isEmpty()) {
            if (selectedSuggestionIndex >= 0 && selectedSuggestionIndex < suggestionContainers.size()) {
              suggestionContainers.get(selectedSuggestionIndex)
                  .setStyle("-fx-background-color: transparent;");
            }
            if (ke.getCode() == javafx.scene.input.KeyCode.TAB && ke.isShiftDown()) {
              selectedSuggestionIndex--;
              if (selectedSuggestionIndex < 0) selectedSuggestionIndex = currentSuggestions.size() - 1;
            } else {
              selectedSuggestionIndex++;
              if (selectedSuggestionIndex >= currentSuggestions.size()) selectedSuggestionIndex = 0;
            }
            if (selectedSuggestionIndex >= 0 && selectedSuggestionIndex < suggestionContainers.size()) {
              suggestionContainers.get(selectedSuggestionIndex)
                  .setStyle("-fx-background-color: #444444;");
              String selectedGene = currentSuggestions.get(selectedSuggestionIndex);
              GeneLocation loc = AnnotationData.getGeneLocation(selectedGene);
              if (loc != null && loc.chrom().equals(viewportState.getCurrentChromosome())) {
                AnnotationData.setHighlightedGene(loc);
              }
            }
          }
          ke.consume();
        }
        case UP -> {
          if (!currentSuggestions.isEmpty()) {
            if (selectedSuggestionIndex >= 0 && selectedSuggestionIndex < suggestionContainers.size()) {
              suggestionContainers.get(selectedSuggestionIndex)
                  .setStyle("-fx-background-color: transparent;");
            }
            selectedSuggestionIndex--;
            if (selectedSuggestionIndex < 0) selectedSuggestionIndex = currentSuggestions.size() - 1;
            if (selectedSuggestionIndex >= 0 && selectedSuggestionIndex < suggestionContainers.size()) {
              suggestionContainers.get(selectedSuggestionIndex)
                  .setStyle("-fx-background-color: #444444;");
              String selectedGene = currentSuggestions.get(selectedSuggestionIndex);
              GeneLocation loc = AnnotationData.getGeneLocation(selectedGene);
              if (loc != null && loc.chrom().equals(viewportState.getCurrentChromosome())) {
                AnnotationData.setHighlightedGene(loc);
              }
            }
          }
          ke.consume();
        }
        case ESCAPE -> {
          autoComplete.hide();
          AnnotationData.clearHighlightedGene();
          selectedSuggestionIndex = -1;
          geneSearchField.requestFocus();
          ke.consume();
        }
        default -> {}
      }
    });

    geneSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
      if (newVal == null || newVal.length() < 2) {
        autoComplete.hide();
        AnnotationData.clearHighlightedGene();
        currentSuggestions.clear();
        suggestionContainers.clear();
        selectedSuggestionIndex = -1;
        return;
      }

      List<String> suggestions = AnnotationData.searchGenes(newVal);
      if (suggestions.isEmpty()) {
        autoComplete.hide();
        AnnotationData.clearHighlightedGene();
        currentSuggestions.clear();
        suggestionContainers.clear();
        selectedSuggestionIndex = -1;
        return;
      }

      currentSuggestions = new ArrayList<>(suggestions);
      suggestionContainers.clear();
      selectedSuggestionIndex = -1;

      // Highlight exact-match gene on the chromosome canvas while typing
      String exactMatch = suggestions.stream()
          .filter(s -> s.equalsIgnoreCase(newVal))
          .findFirst()
          .orElse(null);
      if (exactMatch != null) {
        GeneLocation loc = AnnotationData.getGeneLocation(exactMatch);
        if (loc != null && loc.chrom().equals(viewportState.getCurrentChromosome())) {
          AnnotationData.setHighlightedGene(loc);
        }
      } else {
        AnnotationData.clearHighlightedGene();
      }

      autoComplete.getItems().clear();
      for (String geneName : suggestions) {
        GeneLocation loc = AnnotationData.getGeneLocation(geneName);

        Label nameLabel = new Label(geneName);
        Color geneColor = GeneColors.getGeneColor(geneName, AnnotationData.getGeneBiotype(geneName));
        nameLabel.setStyle("-fx-text-fill: " + GeneColors.toHexString(geneColor) + ";");

        HBox container = new HBox(5);
        container.setCursor(Cursor.HAND);
        container.setPrefWidth(300);
        container.setStyle("-fx-background-color: transparent;");
        suggestionContainers.add(container);

        if (loc != null) {
          String locText = String.format("%s:%,d-%,d", loc.chrom(), loc.start(), loc.end());
          Label locLabel = new Label(locText);
          locLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
          Region spacer = new Region();
          HBox.setHgrow(spacer, Priority.ALWAYS);
          container.getChildren().addAll(nameLabel, spacer, locLabel);
        } else {
          container.getChildren().add(nameLabel);
        }

        container.setOnMouseEntered(e -> {
          if (loc != null && loc.chrom().equals(viewportState.getCurrentChromosome())) {
            AnnotationData.setHighlightedGene(loc);
          }
        });
        container.setOnMouseExited(e -> {
          String currentText = geneSearchField.getText();
          if (currentText != null) {
            GeneLocation exactLoc = AnnotationData.getGeneLocation(currentText);
            if (exactLoc != null && exactLoc.chrom().equals(viewportState.getCurrentChromosome())) {
              AnnotationData.setHighlightedGene(exactLoc);
              return;
            }
          }
          AnnotationData.clearHighlightedGene();
        });

        CustomMenuItem item = new CustomMenuItem(container);
        item.setHideOnClick(true);
        item.setOnAction(e -> navigateToGene(geneName));
        autoComplete.getItems().add(item);
      }

      if (!autoComplete.isShowing()) {
        autoComplete.show(geneSearchField, Side.BOTTOM, 0, 0);
      }
    });

    // Clear highlight and selection when field loses focus
    geneSearchField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
      if (!isFocused) {
        autoComplete.hide();
        AnnotationData.clearHighlightedGene();
        selectedSuggestionIndex = -1;
      } else {
        selectedSuggestionIndex = -1;
      }
    });

    // Direct Enter press when autocomplete is not showing
    geneSearchField.setOnAction(e -> {
      if (!autoComplete.isShowing()) {
        String text = geneSearchField.getText();
        if (text != null && text.length() >= 2) {
          List<String> suggestions = AnnotationData.searchGenes(text);
          if (!suggestions.isEmpty()) {
            String exactMatch = suggestions.stream()
                .filter(s -> s.equalsIgnoreCase(text))
                .findFirst()
                .orElse(suggestions.get(0));
            navigateToGene(exactMatch);
          }
        }
      }
    });
  }

  private void navigateToGene(String geneName) {
    autoComplete.hide();
    SearchCommands.clearGeneHighlight();
    NavigationCommands.navigateToGene(geneName);
    geneSearchField.setText(geneName);
    geneSearchField.selectAll();
    geneSearchField.getParent().requestFocus();
  }
}

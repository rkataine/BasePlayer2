package org.baseplayer.analysis;

/**
 * Results-toolbar all-chromosomes action driven by the selected Analysis tool tab
 * (Filters → Annotate, Sample Comparison → Discover, Group comparison → Compare).
 */
public enum AllChromAction {
  ANNOTATE(
      "Annotate All Chromosomes",
      "List units across all chromosomes. Not group differential or pattern discovery."),
  DISCOVER(
      "Discover All Chromosomes",
      "Pattern discovery across all chromosomes; table shows significant markers only."),
  COMPARE(
      "Compare All Chromosomes",
      "Group differential on every chromosome; table keeps statistically significant features only.");

  private final String buttonText;
  private final String tooltip;

  AllChromAction(String buttonText, String tooltip) {
    this.buttonText = buttonText;
    this.tooltip = tooltip;
  }

  public String buttonText() {
    return buttonText;
  }

  public String tooltip() {
    return tooltip;
  }

  /** Map tool-tab title → action (Signal and Point/SV share the same titles). */
  public static AllChromAction fromToolTabText(String tabText) {
    if (tabText == null) {
      return ANNOTATE;
    }
    String t = tabText.trim();
    if (t.equalsIgnoreCase("Group comparison")) {
      return COMPARE;
    }
    if (t.equalsIgnoreCase("Sample Comparison")) {
      return DISCOVER;
    }
    return ANNOTATE;
  }
}

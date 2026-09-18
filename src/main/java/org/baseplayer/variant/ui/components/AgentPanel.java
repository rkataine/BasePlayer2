package org.baseplayer.variant.ui.components;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Agent (AI analysis) tab: API key / model UI, Gemini HTTP, and results pane toggle.
 */
public class AgentPanel {

    private static final String PREF_API_KEY = "gemini_api_key";
    private static final String PREF_API_MODEL = "gemini_model";

    public record Nodes(
        TabPane filterTabPane,
        javafx.scene.Node resultsPane,
        Tab agentTab,
        PasswordField apiKeyField,
        TextField agentModelField,
        TextArea agentPromptArea,
        TextArea agentResponseArea,
        Label agentStatusLabel,
        Button agentSubmitButton
    ) {}

    private Nodes nodes;
    private Supplier<String> variantContextSupplier;
    private volatile boolean agentRunning;

    public void install(Nodes nodes, Supplier<String> variantContextSupplier) {
        this.nodes = nodes;
        this.variantContextSupplier = variantContextSupplier != null
            ? variantContextSupplier
            : () -> "No variants currently loaded.";
        setupAgentTab();
        if (nodes.agentSubmitButton() != null) {
            nodes.agentSubmitButton().setOnAction(e -> handleAgentSubmit());
        }
    }

    public void setupAgentTab() {
        if (nodes == null) {
            return;
        }
        Preferences prefs = Preferences.userNodeForPackage(org.baseplayer.variant.ui.VariantManagerController.class);
        if (nodes.apiKeyField() != null) {
            nodes.apiKeyField().setText(prefs.get(PREF_API_KEY, ""));
        }
        if (nodes.agentModelField() != null) {
            nodes.agentModelField().setText(prefs.get(PREF_API_MODEL, "gemini-2.0-flash"));
        }

        if (nodes.filterTabPane() != null && nodes.agentTab() != null && nodes.resultsPane() != null) {
            nodes.filterTabPane().getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                boolean isAgent = (newTab == nodes.agentTab());
                nodes.resultsPane().setVisible(!isAgent);
                nodes.resultsPane().setManaged(!isAgent);
                VBox.setVgrow(nodes.filterTabPane(), isAgent ? Priority.ALWAYS : Priority.NEVER);
            });
        }
    }

    public void handleAgentSubmit() {
        if (nodes == null) {
            return;
        }
        String apiKey = nodes.apiKeyField().getText().trim();
        if (apiKey.isEmpty()) {
            nodes.agentStatusLabel().setText("Please enter an AI Studio API key.");
            return;
        }
        String model = nodes.agentModelField().getText().trim();
        if (model.isEmpty()) {
            model = "gemini-2.0-flash";
        }
        String prompt = nodes.agentPromptArea().getText().trim();
        if (prompt.isEmpty()) {
            nodes.agentStatusLabel().setText("Please enter a prompt.");
            return;
        }
        if (agentRunning) {
            return;
        }

        Preferences prefs = Preferences.userNodeForPackage(org.baseplayer.variant.ui.VariantManagerController.class);
        prefs.put(PREF_API_KEY, apiKey);
        prefs.put(PREF_API_MODEL, model);

        agentRunning = true;
        nodes.agentSubmitButton().setDisable(true);
        nodes.agentStatusLabel().setText("Analyzing…");
        nodes.agentResponseArea().clear();

        final String capturedModel = model;
        final String capturedContext = variantContextSupplier.get();
        final String fullPrompt = "You are a genomics expert assistant. Below is a summary of the genomic variants currently loaded in the BasePlayer2 viewer.\n\n"
            + capturedContext + "\n\nUser question: " + prompt;

        Thread thread = new Thread(() -> {
            try {
                String response = callGeminiApi(apiKey, capturedModel, fullPrompt);
                Platform.runLater(() -> {
                    nodes.agentResponseArea().setText(response);
                    nodes.agentStatusLabel().setText("Done.");
                    agentRunning = false;
                    nodes.agentSubmitButton().setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    nodes.agentStatusLabel().setText("Error: " + e.getMessage());
                    agentRunning = false;
                    nodes.agentSubmitButton().setDisable(false);
                });
            }
        }, "agent-api-call");
        thread.setDaemon(true);
        thread.start();
    }

    public String callGeminiApi(String apiKey, String model, String prompt) throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
            + model + ":generateContent?key=" + apiKey;

        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(message);
        JsonObject body = new JsonObject();
        body.add("contents", contents);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String errorMsg;
            try {
                JsonObject err = JsonParser.parseString(response.body()).getAsJsonObject();
                errorMsg = err.getAsJsonObject("error").get("message").getAsString();
            } catch (Exception ignored) {
                errorMsg = "HTTP " + response.statusCode();
            }
            throw new RuntimeException(errorMsg);
        }

        JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
        return responseJson.getAsJsonArray("candidates")
            .get(0).getAsJsonObject()
            .getAsJsonObject("content")
            .getAsJsonArray("parts")
            .get(0).getAsJsonObject()
            .get("text").getAsString();
    }

    public boolean isAgentRunning() {
        return agentRunning;
    }

    public Button getSubmitButton() {
        return nodes != null ? nodes.agentSubmitButton() : null;
    }
}

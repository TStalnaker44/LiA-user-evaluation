package chatbot;

import chatbot.tools.ChatTool;
import chatbot.tools.ChatToolRegistry;
import com.example.my_plugin.ModelProvider;
import com.example.my_plugin.ModelSettings;
import com.example.my_plugin.UsageLogger;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

public class HttpLlmChatSession extends ChatbotSession
{
    private static final int MAX_TOOL_ITERATIONS = 4;
    private static final String INTENT_GENERIC_CHAT = "GENERIC_CHAT";
    private static final String INTENT_LIST_DEPENDENCIES = "LIST_DEPENDENCIES";
    private static final String INTENT_DEPENDENCY_TREE = "DEPENDENCY_TREE";
    private static final String INTENT_DEPENDENCY_RELATIONSHIP_LOOKUP = "DEPENDENCY_RELATIONSHIP_LOOKUP";
    private static final String INTENT_PROJECT_LICENSE_INFO = "PROJECT_LICENSE_INFO";
    private static final String INTENT_PROJECT_LICENSE_ISSUES = "PROJECT_LICENSE_ISSUES";
    private static final String INTENT_DEPENDENCY_LICENSE_LOOKUP = "DEPENDENCY_LICENSE_LOOKUP";
    private static final String CONTEXT_LICENSE_SURVEY = "LICENSE_SURVEY";
    private static final String CONTEXT_DEPENDENCY_SUMMARY = "DEPENDENCY_SUMMARY";
    private static final String CONTEXT_DEPENDENCY_TREE = "DEPENDENCY_TREE";
    private static final String CONTEXT_DEPENDENCY_RELATIONSHIP = "DEPENDENCY_RELATIONSHIP";
    private static final String CONTEXT_DEPENDENCY_LICENSE = "DEPENDENCY_LICENSE";
    private String host;
    private String model;
    private JsonArray history;
    private String apiKey;
    private final ChatToolRegistry toolRegistry;
    private final ModelProvider provider;
    private final Project project;

    public HttpLlmChatSession(String host, String model){
        this(host, model, null, null, null, null, null);
    }

    //TODO this is currently implemented as a generic class that can host any model, but ideally we should have different implementations for different models
    public HttpLlmChatSession(String host, String model, String systemPrompt){
        this(host, model, systemPrompt, null, null, null, null);
    }

    public HttpLlmChatSession(String host, String model, String systemPrompt, String apiKey){
        this(host, model, systemPrompt, apiKey, null, null, null);
    }

    public HttpLlmChatSession(String host, String model, String systemPrompt, String apiKey, ChatToolRegistry toolRegistry){
        this(host, model, systemPrompt, apiKey, toolRegistry, null, null);
    }

    public HttpLlmChatSession(String host, String model, String systemPrompt, String apiKey, ChatToolRegistry toolRegistry, ModelProvider provider){
        this(host, model, systemPrompt, apiKey, toolRegistry, provider, null);
    }

    public HttpLlmChatSession(String host, String model, String systemPrompt, String apiKey, ChatToolRegistry toolRegistry, ModelProvider provider, Project project){
        this.host = host;
        this.model = model;
        this.apiKey = apiKey;
        this.history = new JsonArray();
        this.toolRegistry = toolRegistry;
        this.provider = provider;
        this.project = project;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            addToHistory("system", systemPrompt);
        }
        String toolInstructions = buildToolInstructionMessage();
        if (!toolInstructions.isBlank()) {
            addToHistory("system", toolInstructions);
        }
    }

    public String promptModel(String host, String model, String prompt, JsonArray history) throws Exception{
        long startedAtNs = System.nanoTime();
        String resolvedModel = (model == null || model.isBlank()) ? "llama3.2:latest" : model;
        boolean isOpenAi = provider == ModelProvider.OPENAI || (provider == null && isOpenAiModel(resolvedModel));
        String resolvedHost = resolveHost(host, isOpenAi);
        String resolvedKey = this.apiKey;
        if (isOpenAi && (resolvedKey == null || resolvedKey.isBlank())) {
            throw new IllegalStateException("Please configure the tool by providing an OpenAI API Key.");
        }

        // Create JSON request
        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("model", resolvedModel);

        // Build request history without mutating session state on network failures
        JsonArray requestHistory = new JsonArray();
        for (JsonElement message : history) {
            requestHistory.add(message.deepCopy());
        }
        if (prompt != null && !prompt.isBlank()) {
            JsonObject interaction = new JsonObject();
            interaction.addProperty("role", "user");
            interaction.addProperty("content", prompt);
            requestHistory.add(interaction);
        }
        jsonBody.add("messages", requestHistory);

        // Send request to LLM
        HttpClient client = HttpClient.newHttpClient();
        int timeoutSeconds = project == null
                ? (isOpenAi ? 90 : 30)
                : ModelSettings.INSTANCE.getRequestTimeoutSeconds(project);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(new URI(resolvedHost))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody.toString()));
        if (resolvedKey != null && !resolvedKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + resolvedKey);
        }
        HttpRequest request = requestBuilder.build();

        // Get response from LLM
        HttpResponse<String> jsonresponse;
        try {
            jsonresponse = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            logModelRuntimeFailure(resolvedModel, resolvedHost, isOpenAi, startedAtNs, "timeout");
            String hint = isOpenAi
                    ? "OpenAI request timed out after " + timeoutSeconds + "s (" + resolvedHost + "). Check internet/proxy/firewall, then retry."
                    : "Please configure the tool by providing a valid Ollama host, and ensure Ollama is running.";
            throw new IllegalStateException(hint, e);
        } catch (ConnectException | UnknownHostException e) {
            logModelRuntimeFailure(resolvedModel, resolvedHost, isOpenAi, startedAtNs, "unreachable");
            String hint = isOpenAi
                    ? "Cannot reach OpenAI endpoint (" + resolvedHost + "). Check internet/proxy/firewall or use a reachable OpenAI-compatible host."
                    : "Please configure the tool by providing a valid Ollama host, and ensure Ollama is running.";
            throw new IllegalStateException(hint, e);
        } catch (IOException e) {
            logModelRuntimeFailure(resolvedModel, resolvedHost, isOpenAi, startedAtNs, "io_error");
            throw new IllegalStateException("Network error while contacting model host (" + resolvedHost + "): " + e.getMessage(), e);
        }

        if (jsonresponse.statusCode() < 200 || jsonresponse.statusCode() >= 300) {
            logModelRuntimeFailure(resolvedModel, resolvedHost, isOpenAi, startedAtNs, "http_" + jsonresponse.statusCode());
            String body = jsonresponse.body() == null ? "" : jsonresponse.body();
            if (body.length() > 500) body = body.substring(0, 500) + "...";
            if (isOpenAi) {
                throw new IllegalStateException("Model request failed with HTTP " + jsonresponse.statusCode() + " from " + resolvedHost + (body.isBlank() ? "" : " - " + body));
            }
            throw new IllegalStateException("Please configure the tool by providing a valid Ollama host, and ensure Ollama is running.");
        }

        // Parse JSON response and return relevant portion
        JsonObject obj = JsonParser.parseString(jsonresponse.body()).getAsJsonObject();
        logTokenUsage(obj, resolvedModel, resolvedHost, isOpenAi, requestHistory, startedAtNs, jsonresponse.statusCode());
        if (!obj.has("choices") || obj.get("choices").getAsJsonArray().isEmpty()) {
            throw new IllegalStateException("Model response does not contain choices.");
        }
        String response = obj.get("choices").getAsJsonArray()
                .get(0).getAsJsonObject()
                .get("message").getAsJsonObject()
                .get("content").getAsString();

        return response;

    }

    private void logTokenUsage(
            JsonObject responseObj,
            String resolvedModel,
            String resolvedHost,
            boolean isOpenAi,
            JsonArray requestHistory,
            long startedAtNs,
            int statusCode
    ) {
        if (project == null || responseObj == null) {
            return;
        }
        TokenUsage usage = extractTokenUsage(responseObj);
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("model", resolvedModel);
        payload.put("provider", isOpenAi ? "OPENAI" : "OLLAMA");
        payload.put("endpoint", resolvedHost);
        payload.put("durationMs", (System.nanoTime() - startedAtNs) / 1_000_000L);
        payload.put("httpStatus", statusCode);
        payload.put("requestMessageCount", requestHistory == null ? 0 : requestHistory.size());
        payload.put("usageSource", usage.source);
        if (usage.inputTokens != null) payload.put("inputTokens", usage.inputTokens);
        if (usage.outputTokens != null) payload.put("outputTokens", usage.outputTokens);
        if (usage.totalTokens != null) payload.put("totalTokens", usage.totalTokens);
        UsageLogger.INSTANCE.logInteraction(project, null, "model_token_usage", payload);
    }

    private void logModelRuntimeFailure(String resolvedModel, String resolvedHost, boolean isOpenAi, long startedAtNs, String failureType) {
        if (project == null) {
            return;
        }
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("model", resolvedModel);
        payload.put("provider", isOpenAi ? "OPENAI" : "OLLAMA");
        payload.put("endpoint", resolvedHost);
        payload.put("durationMs", (System.nanoTime() - startedAtNs) / 1_000_000L);
        payload.put("failureType", failureType);
        UsageLogger.INSTANCE.logInteraction(project, null, "model_runtime_failed", payload);
    }

    private TokenUsage extractTokenUsage(JsonObject responseObj) {
        if (responseObj.has("usage") && responseObj.get("usage").isJsonObject()) {
            JsonObject usage = responseObj.getAsJsonObject("usage");
            Long input = firstLong(usage, "prompt_tokens", "input_tokens");
            Long output = firstLong(usage, "completion_tokens", "output_tokens");
            Long total = firstLong(usage, "total_tokens");
            if (total == null && input != null && output != null) {
                total = input + output;
            }
            return new TokenUsage(input, output, total, "usage");
        }

        Long input = firstLong(responseObj, "prompt_eval_count", "prompt_token_count");
        Long output = firstLong(responseObj, "eval_count", "completion_token_count");
        Long total = input != null && output != null ? input + output : null;
        if (input != null || output != null || total != null) {
            return new TokenUsage(input, output, total, "provider_fields");
        }
        return new TokenUsage(null, null, null, "unavailable");
    }

    private Long firstLong(JsonObject obj, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (!obj.has(fieldName) || obj.get(fieldName).isJsonNull()) {
                continue;
            }
            try {
                return obj.get(fieldName).getAsLong();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static final class TokenUsage {
        private final Long inputTokens;
        private final Long outputTokens;
        private final Long totalTokens;
        private final String source;

        private TokenUsage(Long inputTokens, Long outputTokens, Long totalTokens, String source) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.totalTokens = totalTokens;
            this.source = source;
        }
    }

    public String submitPrompt(String prompt) throws Exception {
        return submitPromptInternal(prompt);
    }

    //TODO ideally we don't need this, but the async nature of it is breaking things in some places
    public String submitPromptBlocking(String prompt) throws Exception {
        return submitPromptInternal(prompt);
    }

    public void clearHistory(){
        this.history = new JsonArray();
    }

    public JsonArray getHistory(){
        return this.history;
    }

    public void addToHistory(String role, String content){
        JsonObject iteration = new JsonObject();
        iteration.addProperty("role", role);
        iteration.addProperty("content", content);
        this.history.add(iteration);
    }

    private boolean isOpenAiModel(String model) {
        String m = model.toLowerCase();
        return m.startsWith("gpt") || m.startsWith("o1") || m.startsWith("o3");
    }

    private String resolveHost(String host, boolean isOpenAi) {
        String defaultOpenAi = "https://api.openai.com/v1/chat/completions";
        if (host == null || host.isBlank()) {
            if (!isOpenAi) {
                throw new IllegalStateException("Please configure the tool by providing a valid Ollama host.");
            }
            return defaultOpenAi;
        }
        String normalized = host.trim();
        if (isOpenAi) {
            boolean looksLikeOpenAi = normalized.contains("openai.com");
            if (!looksLikeOpenAi && !normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                return defaultOpenAi;
            }
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                normalized = "https://" + normalized;
            }
        } else {
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                if (!normalized.contains(":")) {
                    normalized = normalized + ":11434";
                }
                normalized = "http://" + normalized;
            }
        }
        if (!normalized.contains("/v1/")) {
            if (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            normalized = normalized + "/v1/chat/completions";
        }
        return normalized;
    }

    private String submitPromptInternal(String prompt) throws Exception {
        JsonArray workingHistory = deepCopyHistory(this.history);
        appendToHistory(workingHistory, "user", prompt);
        RoutingDecision routingDecision = classifyIntent(prompt);
        String deterministicResponse = tryBuildDeterministicResponse(prompt, routingDecision);
        if (deterministicResponse != null) {
            addToHistory("user", prompt);
            addToHistory("assistant", deterministicResponse);
            return deterministicResponse;
        }
        injectRequestedContext(workingHistory, routingDecision);
        injectAnswerInstructions(workingHistory, routingDecision);
        String nextPrompt = prompt;
        String finalResult = null;

        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            String result = promptModel(this.host, this.model, i == 0 ? nextPrompt : null, workingHistory);

            JsonObject toolCall = tryParseToolCall(result);
            if (toolCall == null || toolRegistry == null) {
                finalResult = sanitizeModelResponse(result);
                appendToHistory(workingHistory, "assistant", finalResult);
                break;
            }

            String toolName = toolCall.get("tool").getAsString();
            if (!toolRegistry.contains(toolName)) {
                finalResult = sanitizeModelResponse(result);
                appendToHistory(workingHistory, "assistant", finalResult);
                break;
            }

            JsonObject arguments = toolCall.has("arguments") && toolCall.get("arguments").isJsonObject()
                    ? toolCall.getAsJsonObject("arguments")
                    : new JsonObject();
            String toolResult = toolRegistry.execute(toolName, new JSONObject(arguments.toString()));
            appendToHistory(workingHistory, "system",
                    "Internal tool result for " + toolName + ":\n" + toolResult
                            + "\nUse this internal information to help the user."
                            + "\nDo not expose tool names, JSON payloads, arguments, or raw tool output in the final answer.");
            nextPrompt = "Continue assisting the user. If another tool is strictly necessary, respond again with JSON only."
                    + " Otherwise, provide a concise, user-friendly answer.";
        }

        if (finalResult == null) {
            finalResult = "I could not complete the tool-assisted analysis within the allowed number of steps. Please refine the request and try again.";
        }

        addToHistory("user", prompt);
        addToHistory("assistant", finalResult);
        return finalResult;
    }

    private String tryBuildDeterministicResponse(String prompt, RoutingDecision decision) throws Exception {
        if (decision == null || toolRegistry == null) {
            return null;
        }
        if (INTENT_DEPENDENCY_TREE.equals(decision.intent) && toolRegistry.contains("get_dependency_tree")) {
            String tree = executeTool("get_dependency_tree", new JSONObject());
            return tree == null || tree.isBlank() ? null : tree.trim();
        }
        if (INTENT_DEPENDENCY_RELATIONSHIP_LOOKUP.equals(decision.intent) && toolRegistry.contains("get_dependency_relationship")) {
            String dependencyQuery = decision.dependencyQuery;
            if (dependencyQuery == null || dependencyQuery.isBlank()) {
                dependencyQuery = extractDependencyQueryFromPrompt(prompt);
            }
            if (dependencyQuery == null || dependencyQuery.isBlank()) {
                return "Please specify which dependency you want to inspect.";
            }
            JSONObject arguments = new JSONObject();
            arguments.put("dependency", dependencyQuery);
            String relationship = executeTool("get_dependency_relationship", arguments);
            return relationship == null || relationship.isBlank() ? null : relationship.trim();
        }
        return null;
    }

    private String extractDependencyQueryFromPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)?)")
                        .matcher(prompt);
        return matcher.find() ? matcher.group(1) : "";
    }

    private RoutingDecision classifyIntent(String prompt) {
        if (toolRegistry == null || prompt == null || prompt.isBlank()) {
            return RoutingDecision.generic();
        }
        try {
            JsonArray routingHistory = new JsonArray();
            String routingPrompt = loadRoutingPrompt();
            if (!routingPrompt.isBlank()) {
                appendToHistory(routingHistory, "system", routingPrompt);
            }
            appendToHistory(routingHistory, "user", buildRoutingInput(prompt));
            String response = promptModel(this.host, this.model, null, routingHistory);
            RoutingDecision decision = RoutingDecision.fromJson(unwrapJsonBlock(response));
            return decision == null ? RoutingDecision.generic() : decision;
        } catch (Exception e) {
            return RoutingDecision.generic();
        }
    }

    private void injectRequestedContext(JsonArray workingHistory, RoutingDecision decision) throws Exception {
        if (decision == null || toolRegistry == null) {
            return;
        }
        String contextBlock = buildRequestedContext(decision);
        if (contextBlock.isBlank()) {
            return;
        }
        appendToHistory(
                workingHistory,
                "system",
                "Relevant project context for the current user request:\n"
                        + contextBlock
                        + "\nUse this information if helpful."
                        + "\nDo not mention internal routing, internal context retrieval, tool names, or raw internal payloads."
        );
    }

    private void injectAnswerInstructions(JsonArray workingHistory, RoutingDecision decision) {
        String instructions = buildAnswerInstructions(decision);
        if (instructions.isBlank()) {
            return;
        }
        appendToHistory(workingHistory, "system", instructions);
    }

    private String buildRequestedContext(RoutingDecision decision) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (String context : decision.requiredContext) {
            String content = switch (context) {
                case CONTEXT_LICENSE_SURVEY -> executeTool("get_licensing_for_my_project", new JSONObject());
                case CONTEXT_DEPENDENCY_SUMMARY -> executeTool("get_dependency_list", new JSONObject());
                case CONTEXT_DEPENDENCY_TREE -> executeTool("get_dependency_tree", new JSONObject());
                case CONTEXT_DEPENDENCY_RELATIONSHIP -> {
                    if (decision.dependencyQuery == null || decision.dependencyQuery.isBlank()) {
                        yield "";
                    }
                    JSONObject arguments = new JSONObject();
                    arguments.put("dependency", decision.dependencyQuery);
                    yield executeTool("get_dependency_relationship", arguments);
                }
                case CONTEXT_DEPENDENCY_LICENSE -> {
                    if (decision.dependencyQuery == null || decision.dependencyQuery.isBlank()) {
                        yield "";
                    }
                    JSONObject arguments = new JSONObject();
                    arguments.put("dependency", decision.dependencyQuery);
                    yield executeTool("get_dependency_license", arguments);
                }
                default -> "";
            };
            if (content == null || content.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(context).append(":\n").append(content);
        }
        return builder.toString().trim();
    }

    private String executeTool(String toolName, JSONObject arguments) throws Exception {
        if (toolRegistry == null || !toolRegistry.contains(toolName)) {
            return "";
        }
        return toolRegistry.execute(toolName, arguments);
    }

    private JsonArray deepCopyHistory(JsonArray source) {
        JsonArray copy = new JsonArray();
        for (JsonElement element : source) {
            copy.add(element.deepCopy());
        }
        return copy;
    }

    private void appendToHistory(JsonArray target, String role, String content) {
        JsonObject iteration = new JsonObject();
        iteration.addProperty("role", role);
        iteration.addProperty("content", content);
        target.add(iteration);
    }

    private JsonObject tryParseToolCall(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String candidate = unwrapJsonBlock(response.trim());
        try {
            JsonObject json = JsonParser.parseString(candidate).getAsJsonObject();
            if (json.has("mode")
                    && "tool_call".equalsIgnoreCase(json.get("mode").getAsString())
                    && json.has("tool")) {
                if (!json.has("arguments") || !json.get("arguments").isJsonObject()) {
                    json.add("arguments", new JsonObject());
                }
                return json;
            }
            if (json.has("toolCall") && json.get("toolCall").isJsonObject()) {
                return normalizeLegacyToolCall(json.getAsJsonObject("toolCall"));
            }
            if (json.has("functionCall") && json.get("functionCall").isJsonObject()) {
                return normalizeLegacyToolCall(json.getAsJsonObject("functionCall"));
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonObject normalizeLegacyToolCall(JsonObject legacy) {
        if (!legacy.has("functionName")) {
            return null;
        }
        JsonObject normalized = new JsonObject();
        normalized.addProperty("mode", "tool_call");
        normalized.addProperty("tool", legacy.get("functionName").getAsString());
        if (legacy.has("parameters") && legacy.get("parameters").isJsonObject()) {
            normalized.add("arguments", legacy.getAsJsonObject("parameters"));
        } else {
            normalized.add("arguments", new JsonObject());
        }
        return normalized;
    }

    private String unwrapJsonBlock(String response) {
        if (!response.startsWith("```")) {
            return response;
        }
        int firstLineEnd = response.indexOf('\n');
        int lastFence = response.lastIndexOf("```");
        if (firstLineEnd == -1 || lastFence <= firstLineEnd) {
            return response;
        }
        return response.substring(firstLineEnd + 1, lastFence).trim();
    }

    private String sanitizeModelResponse(String response) {
        if (response == null || response.isBlank()) {
            return response;
        }
        String[] lines = response.replace("\r", "").split("\n");
        StringBuilder builder = new StringBuilder();
        boolean insideFence = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                insideFence = !insideFence;
                continue;
            }
            if (insideFence) {
                continue;
            }
            if (trimmed.startsWith("Using the ") && trimmed.contains("tool")) {
                continue;
            }
            if (trimmed.startsWith("Tool result for ")) {
                continue;
            }
            if (trimmed.contains("\"mode\"") && trimmed.contains("tool_call")) {
                continue;
            }
            if (trimmed.startsWith("{") && trimmed.contains("\"tool\"") && trimmed.contains("\"arguments\"")) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(line);
        }
        String sanitized = builder.toString().trim();
        return sanitized.isBlank() ? response.trim() : sanitized;
    }

    private String buildToolInstructionMessage() {
        if (toolRegistry == null || toolRegistry.getTools().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("You can request local project tools when strictly needed.\n");
        builder.append("If a tool is needed, respond with JSON only using this exact shape:\n");
        builder.append("{\"mode\":\"tool_call\",\"tool\":\"tool_name\",\"arguments\":{...}}\n");
        builder.append("If no tool is needed, answer normally in plain text.\n");
        builder.append("Tool calls and tool outputs are internal. Never expose tool names, JSON payloads, arguments, or raw tool results to the end user.\n");
        builder.append("After using tools, answer only with a concise, user-friendly explanation.\n");
        builder.append("Available tools:\n");
        for (ChatTool tool : toolRegistry.getTools()) {
            builder.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append('\n');
        }
        return builder.toString().trim();
    }

    private String buildAnswerInstructions(RoutingDecision decision) {
        if (decision == null) {
            return "";
        }
        return switch (decision.intent) {
            case INTENT_LIST_DEPENDENCIES -> loadResourceText("prompts/answer-list-dependencies.txt");
            case INTENT_DEPENDENCY_TREE -> loadResourceText("prompts/answer-dependency-tree.txt");
            case INTENT_PROJECT_LICENSE_INFO -> loadResourceText("prompts/answer-project-license-info.txt");
            case INTENT_PROJECT_LICENSE_ISSUES -> loadResourceText("prompts/answer-project-license-issues.txt");
            default -> "";
        };
    }

    private String loadRoutingPrompt() {
        return loadResourceText("prompts/routing.txt");
    }

    private String buildRoutingInput(String prompt) {
        StringBuilder builder = new StringBuilder();
        String recentConversation = buildRecentConversationSummary();
        if (!recentConversation.isBlank()) {
            builder.append("Recent conversation:\n").append(recentConversation).append("\n\n");
        }
        builder.append("Latest user request:\n").append(prompt).append("\n\n");
        builder.append("Available local contexts:\n");
        builder.append("- ").append(CONTEXT_LICENSE_SURVEY).append('\n');
        builder.append("- ").append(CONTEXT_DEPENDENCY_SUMMARY).append('\n');
        builder.append("- ").append(CONTEXT_DEPENDENCY_TREE).append('\n');
        builder.append("- ").append(CONTEXT_DEPENDENCY_RELATIONSHIP).append('\n');
        builder.append("- ").append(CONTEXT_DEPENDENCY_LICENSE).append('\n');
        return builder.toString();
    }

    private String buildRecentConversationSummary() {
        StringBuilder builder = new StringBuilder();
        int added = 0;
        for (int i = history.size() - 1; i >= 0 && added < 4; i--) {
            JsonObject message = history.get(i).getAsJsonObject();
            String role = message.get("role").getAsString();
            if ("system".equals(role)) {
                continue;
            }
            String content = message.get("content").getAsString().trim();
            if (content.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.insert(0, '\n');
            }
            builder.insert(0, role + ": " + content);
            added++;
        }
        return builder.toString().trim();
    }

    private String loadResourceText(String resourcePath) {
        try (InputStream stream = HttpLlmChatSession.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return "";
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static final class RoutingDecision {
        private final String intent;
        private final Set<String> requiredContext;
        private final String dependencyQuery;

        private RoutingDecision(String intent, Set<String> requiredContext, String dependencyQuery) {
            this.intent = intent;
            this.requiredContext = requiredContext;
            this.dependencyQuery = dependencyQuery == null ? "" : dependencyQuery.trim();
        }

        static RoutingDecision generic() {
            return new RoutingDecision(INTENT_GENERIC_CHAT, new LinkedHashSet<>(), "");
        }

        static RoutingDecision fromJson(String rawJson) {
            try {
                JSONObject json = new JSONObject(rawJson);
                String intent = json.optString("intent", "").trim();
                if (intent.isBlank()) {
                    return generic();
                }
                Set<String> contexts = new LinkedHashSet<>();
                org.json.JSONArray required = json.optJSONArray("required_context");
                if (required != null) {
                    for (int i = 0; i < required.length(); i++) {
                        String value = required.optString(i, "").trim();
                        if (!value.isBlank()) {
                            contexts.add(value);
                        }
                    }
                }
                String dependencyQuery = json.optString("dependency_query", "");
                return new RoutingDecision(intent, contexts, dependencyQuery);
            } catch (Exception e) {
                return generic();
            }
        }
    }
}

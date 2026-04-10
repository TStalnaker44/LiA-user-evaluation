package controller;

import chatbot.ChatbotSession;
import chatbot.HttpLlmChatSession;
import chatbot.tools.ChatToolRegistry;

import com.example.my_plugin.License;

import com.example.my_plugin.MavenDependencyService;
import com.example.my_plugin.MyToolWindowBridge;
import com.example.my_plugin.MyToolWindowFactory;
import com.example.my_plugin.ApiKeyStore;
import com.example.my_plugin.ModelProvider;
import com.example.my_plugin.ModelSettings;
import com.example.my_plugin.UsageLogger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.Task;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.components.Service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;


/**
 * Central Controller for the Licensing Plugin
 * Coordinates communication between IDE, tools, and LLM components
 */
@Service(Service.Level.PROJECT)
public final class LicensingController implements Disposable {
    private static final Logger LOGGER = com.example.my_plugin.LogInitializer.getLogger(LicensingController.class);

    private final Project project;

    // Configuration
    private JSONObject userLicenseConfig;

    // State management
    private ControllerState state;

    // Component managers
    private ToolManager toolManager;
    private LLMManager llmManager;
    private IDECommunicator ideCommunicator;
    private ConfigurationManager configManager;
    private final ChatToolRegistry chatToolRegistry;
    private volatile ChatbotSession cachedSession;
    private String cachedModel;
    private String cachedHost;
    private String cachedApiKey;
    private final AtomicBoolean dependencyScanRunning = new AtomicBoolean(false);
    private final AtomicBoolean dependencyScanPending = new AtomicBoolean(false);
    private final AtomicReference<String> pendingPomPath = new AtomicReference<>(null);
    /**
     * Constructor initializes the controller and all required components
     */
    public LicensingController(Project project) {
        this.project = project;
        this.configManager = new ConfigurationManager();
        this.userLicenseConfig = configManager.loadConfiguration();
        this.state = new ControllerState();
        this.toolManager = new ToolManager();
        this.llmManager = new LLMManager();
        this.ideCommunicator = new IDECommunicator();
        this.chatToolRegistry = ChatToolRegistry.createDefault(project);

        LOGGER.info("Licensing Controller initialized");
    }

    /**
     * Initializes the controller with custom configuration
     * @param config Custom configuration
     */
    public void initialize(JSONObject config) {
        if (config != null) {
            this.userLicenseConfig = config;
            this.configManager.saveConfiguration(config);
        }
        LOGGER.info("Licensing Controller initialized with custom configuration");
    }

    public ChatToolRegistry getChatToolRegistry() {
        return chatToolRegistry;
    }

    //---------------------------------------------------------------------
    // IDE Interface Functions
    //---------------------------------------------------------------------

    /**
     * Receives events from the IDE plugin
     * @param eventData JSON containing event type and relevant data
     * @return response indicating how the controller will handle the event
     */
    public JSONObject handleIDEEvent(JSONObject eventData) {
        try {
            // Extract event type
            String eventTypeStr = eventData.getString("eventType");
            LicensingEventType eventType = LicensingEventType.valueOf(eventTypeStr);

            // Extract other relevant data
            long timestamp = eventData.getLong("timestamp");
            String projectId = eventData.getString("projectId");
            String filePath = eventData.getString("filePath");
            JSONObject details = eventData.getJSONObject("details");

            // Log event
            LOGGER.info(String.format("Received event: %s for project: %s", eventTypeStr, projectId));

            // Process the event
            boolean processed = processLicensingEvent(eventTypeStr, details);

            // Prepare response
            JSONObject response = new JSONObject();
            response.put("success", processed);
            response.put("eventId", timestamp); // Use timestamp as event ID for now
            response.put("message", "Event processed successfully");

            return response;
        } catch (Exception e) {
            LOGGER.error("Error handling IDE event: " + e.getMessage());
            JSONObject errorResponse = new JSONObject();
            errorResponse.put("success", false);
            errorResponse.put("error", "Failed to process event: " + e.getMessage());
            return errorResponse;
        }
    }

    /**
     * Processes a detected licensing event from the IDE
     * @param eventType String identifying the type of event (dependency_added, file_added, etc.)
     * @param eventContext JSONObject with context details about the event
     * @return processing status
     */
    public boolean processLicensingEvent(String eventType, JSONObject eventContext) {
        try {
            // Get project context
            JSONObject projectContext = state.getProjectContext();
            if (projectContext == null) {
                projectContext = new JSONObject();
                projectContext.put("declaredLicense", "UNKNOWN");
            }

            // Build context for LLM
            JSONObject llmContext = buildLLMContext(
                new JSONObject().put("eventType", eventType).put("details", eventContext),
                userLicenseConfig,
                projectContext
            );

            // Determine if this event should trigger notification
            boolean shouldNotify = shouldNotifyUser(llmContext);

            if (shouldNotify) {
                // Get analysis from LLM
                JSONObject llmAnalysis = getLLMAnalysis(llmContext);

                // Check if tools need to be called
                if (llmAnalysis.has("toolCall")) {
                    JSONObject toolCall = llmAnalysis.getJSONObject("toolCall");
                    String toolFunction = toolCall.getString("functionName");
                    JSONObject toolParams = toolCall.getJSONObject("parameters");

                    // Call appropriate tool based on function name
                    JSONObject toolResult = null;
                    switch (toolFunction) {
                        case "detectLicenses":
                            toolResult = detectLicenses(toolParams);
                            LOGGER.info("Tool detectLicenses result: " + toolResult);
                            break;
                        case "scanCode":
                            toolResult = scanCode(toolParams);
                            LOGGER.info("Tool scanCode result: " + toolResult);
                            break;
                        case "analyzeDependencies":
                            toolResult = analyzeDependencies(toolParams);
                            LOGGER.info("Tool analyzeDependencies result: " + toolResult);
                            break;
                        case "retrievePackageInfo":
                            toolResult = retrievePackageInfo(toolParams);
                            LOGGER.info("Tool retrievePackageInfo result: " + toolResult);
                            break;
                        default:
                            LOGGER.warn("Unknown tool function: " + toolFunction);
                    }

                    // If we got a tool result, update LLM context and get new analysis
                    if (toolResult != null) {
                        llmContext.put("toolResult", toolResult);
                        llmAnalysis = getLLMAnalysis(llmContext);
                    }
                }

                // Format LLM response
                JSONObject formattedResponse = new JSONObject(formatLLMResponse(llmAnalysis.toString()));

                // Send notification to IDE
                JSONObject notification = new JSONObject();
                notification.put("type", formattedResponse.getString("notificationType"));
                notification.put("title", formattedResponse.getString("title"));
                notification.put("message", formattedResponse.getString("message"));

                if (formattedResponse.has("actions")) {
                    notification.put("actions", formattedResponse.getJSONArray("actions"));
                }

                notification.put("contextData", new JSONObject()
                    .put("eventType", eventType)
                    .put("details", eventContext));

                sendIDENotification(notification);
            }

            // Update state with this event
            state.addEvent(new JSONObject()
                .put("type", eventType)
                .put("timestamp", System.currentTimeMillis())
                .put("details", eventContext));

            return true;
        } catch (Exception e) {
            LOGGER.error("Error processing licensing event: " + e.getMessage());
            return false;
        }
    }

    /**
     * Sends response back to the IDE to notify the user
     * @param notificationData JSON with notification content and type
     * @return boolean indicating if notification was delivered successfully
     */
    public boolean sendIDENotification(JSONObject notificationData) {
        try {
            // Extract notification components
            String type = notificationData.getString("type");
            String title = notificationData.getString("title");
            String message = notificationData.getString("message");

            // Extract optional components
            JSONArray actions = notificationData.optJSONArray("actions");
            JSONObject contextData = notificationData.optJSONObject("contextData");

            // Log notification
            LOGGER.info("Sending notification: " + type + " - " + title);

            // Send to IDE communicator
            return ideCommunicator.sendNotification(type, title, message, actions, contextData);
        } catch (Exception e) {
            LOGGER.error("Error sending IDE notification: " + e.getMessage());
            return false;
        }
    }

    //---------------------------------------------------------------------
    // Tool API Interface Functions
    //---------------------------------------------------------------------
    public void onDependencyChange(Project project)
    {
        onDependencyChange(project, null);
    }

    public void onDependencyChange(Project project, String changedPomPath)
    {
        MyToolWindowFactory.ChatUi ui = MyToolWindowBridge.Companion.getInstance(project).getUi();
        if (!dependencyScanRunning.compareAndSet(false, true)) {
            dependencyScanPending.set(true);
            pendingPomPath.set(changedPomPath);
            LOGGER.info("Dependency scan already running; queued another run.");
            return;
        }
        // Start animation on EDT
        if (ui != null) {
            ui.startSbomAnimation();
        } else {
            LOGGER.info("UI is null, running dependency scan without chat animation.");
        }
        // Do heavy work in background with progress/cancel
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Analyzing dependency changes", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    MavenDependencyService mavenService = project.getService(MavenDependencyService.class);
                    if (mavenService == null) {
                        LOGGER.warn("MavenDependencyService not available; skipping dependency analysis");
                        return;
                    }
                    mavenService.flagNewDependency(indicator, changedPomPath);
                } catch (ProcessCanceledException cancelled) {
                    LOGGER.info("Dependency analysis cancelled");
                    throw cancelled;
                } catch (Exception e) {
                    LOGGER.error("Error in onDependencyChange: " + e.getMessage());
                }
            }

            @Override
            public void onFinished() {
                if (ui != null) {
                    ui.stopAnimation();
                }
                dependencyScanRunning.set(false);
                if (dependencyScanPending.getAndSet(false)) {
                    onDependencyChange(project, pendingPomPath.getAndSet(null));
                }
            }
        });
    }

    /**
     * Interfaces with the License Detection tool
     * @param codeInfo JSONObject containing file paths and content to analyze
     * @return JSONObject with detected licenses and their details
     */
    public JSONObject detectLicenses(JSONObject codeInfo) {
        try {
            // Extract files to analyze
            JSONArray files = codeInfo.getJSONArray("files");
            JSONArray licenseFiles = codeInfo.optJSONArray("licenseFiles");
            JSONObject scanOptions = codeInfo.optJSONObject("scanOptions");

            // Log operation
            LOGGER.info("Detecting licenses for " + files.length() + " files");

            // Call tool manager to perform detection
            JSONObject result = toolManager.callLicenseDetectionTool(files, licenseFiles, scanOptions);

            // Update state with results
            if (result.getBoolean("success")) {
                JSONArray detectedLicenses = result.getJSONArray("detectedLicenses");
                for (int i = 0; i < detectedLicenses.length(); i++) {
                    JSONObject license = detectedLicenses.getJSONObject(i);
                    state.updateFileLicense(
                        license.getString("filePath"),
                        license.getString("license")
                    );
                }
            }

            return result;
        } catch (Exception e) {
            LOGGER.error("Error detecting licenses: " + e.getMessage());
            JSONObject errorResult = new JSONObject();
            errorResult.put("success", false);
            errorResult.put("error", "Failed to detect licenses: " + e.getMessage());
            return errorResult;
        }
    }

    /**
     * Interfaces with the Code Scanning tool
     * @param codeData JSONObject with code files to scan
     * @return JSONObject with scan results and potential issues
     */
    public JSONObject scanCode(JSONObject codeData) {
        try {
            // Extract files to scan
            JSONArray files = codeData.getJSONArray("files");
            JSONObject scanOptions = codeData.optJSONObject("scanOptions");
            JSONArray knownExternalSources = codeData.optJSONArray("knownExternalSources");

            // Log operation
            LOGGER.info("Scanning " + files.length() + " files for licensing issues");

            // Call tool manager to perform scan
            JSONObject result = toolManager.callCodeScanningTool(files, scanOptions, knownExternalSources);

            // Update state with results if needed
            if (result.getBoolean("success") && result.has("issues")) {
                JSONArray issues = result.getJSONArray("issues");
                state.addLicensingIssues(issues);
            }

            return result;
        } catch (Exception e) {
            LOGGER.error("Error scanning code: " + e.getMessage());
            JSONObject errorResult = new JSONObject();
            errorResult.put("success", false);
            errorResult.put("error", "Failed to scan code: " + e.getMessage());
            return errorResult;
        }
    }

    /**
     * Interfaces with the Dependency Management tool
     * @param dependencyData JSONObject with dependency information
     * @return JSONObject with analysis of dependencies and potential conflicts
     */
    public JSONObject analyzeDependencies(JSONObject dependencyData) {
        try {
            // Extract dependencies to analyze
            JSONArray directDependencies = dependencyData.getJSONArray("directDependencies");
            JSONObject buildFile = dependencyData.optJSONObject("buildFile");
            String projectLicense = dependencyData.optString("projectLicense", "UNKNOWN");
            boolean analyzeTransitive = dependencyData.optBoolean("analyzeTransitive", true);
            boolean checkCompatibility = dependencyData.optBoolean("checkCompatibility", true);

            // Log operation
            LOGGER.info("Analyzing " + directDependencies.length() + " dependencies for license compatibility");

            // Call tool manager to perform analysis
            JSONObject result = toolManager.callDependencyManagementTool(
                directDependencies, buildFile, projectLicense, analyzeTransitive, checkCompatibility);

            // Update state with results
            if (result.getBoolean("success")) {
                if (result.has("dependencyLicenses")) {
                    JSONArray dependencyLicenses = result.getJSONArray("dependencyLicenses");
                    state.updateDependencyLicenses(dependencyLicenses);
                }

                if (result.has("conflicts")) {
                    JSONArray conflicts = result.getJSONArray("conflicts");
                    state.updateLicenseConflicts(conflicts);
                }
            }

            return result;
        } catch (Exception e) {
            LOGGER.error("Error analyzing dependencies: " + e.getMessage());
            JSONObject errorResult = new JSONObject();
            errorResult.put("success", false);
            errorResult.put("error", "Failed to analyze dependencies: " + e.getMessage());
            return errorResult;
        }
    }

    /**
     * Interfaces with the Package Retrieval tool
     * @param packageQuery JSONObject with package identifiers to retrieve
     * @return JSONObject with package information including licensing data
     */
    public JSONObject retrievePackageInfo(JSONObject packageQuery) {
        try {
            // Extract packages to retrieve
            JSONArray packages = packageQuery.getJSONArray("packages");
            boolean includeLicenseText = packageQuery.optBoolean("includeLicenseText", false);
            boolean includeAlternatives = packageQuery.optBoolean("includeAlternatives", false);
            JSONArray repositories = packageQuery.optJSONArray("repositories");

            // Log operation
            LOGGER.info("Retrieving information for " + packages.length() + " packages");

            // Call tool manager to retrieve package info
            JSONObject result = toolManager.callPackageRetrievalTool(
                packages, includeLicenseText, includeAlternatives, repositories);

            // No state update needed for retrieval only

            return result;
        } catch (Exception e) {
            LOGGER.error("Error retrieving package info: " + e.getMessage());
            JSONObject errorResult = new JSONObject();
            errorResult.put("success", false);
            errorResult.put("error", "Failed to retrieve package info: " + e.getMessage());
            return errorResult;
        }
    }

    //---------------------------------------------------------------------
    // LLM/Chatbot Interface Functions
    //---------------------------------------------------------------------

    /**
     * Creates a new ChatbotSession for a given chatbot interaction
     * @return
     */
    public ChatbotSession getNewChatbotSession(String selectedModel) {
        // String selectedModel = System.getenv("LICENSE_TOOL_MODEL");
        if (selectedModel == null || selectedModel.isEmpty()) {
            selectedModel = "llama3.2:latest";
        }
        //Read in the system prompt from resources/prompts/system.txt
        String systemPrompt;
        try {
            InputStream is = LicensingController.class.getClassLoader().getResourceAsStream("prompts/system.txt");
            systemPrompt = IOUtils.toString(is, "UTF-8");
        }
        catch (Exception e)
        {
            //Unable to load system prompt
            systemPrompt = "You are a friendly assistant built into in IDE to help with software licensing problems.";
        }
        ModelProvider provider = ModelSettings.INSTANCE.getProviderForModel(selectedModel);
        boolean isOpenAiModel = provider == ModelProvider.OPENAI;
        String configuredHost = null;
        if (!isOpenAiModel) {
            configuredHost = ModelSettings.INSTANCE.getOllamaHost(project);
            if (configuredHost == null || configuredHost.isBlank()) {
                throw new IllegalStateException("Please configure the tool by providing a valid Ollama host.");
            }
        }
        String apiKey = ApiKeyStore.INSTANCE.getOpenAiKey(project);
        cachedSession = new HttpLlmChatSession(
                configuredHost,
                selectedModel,
                systemPrompt,
                apiKey,
                chatToolRegistry,
                provider,
                project
        );
        LOGGER.info(String.format("New chatbot session created. Cached model: %s Cached session: %s", selectedModel, cachedSession));
        UsageLogger.INSTANCE.logInteraction(project, null, "chat_session_created", Map.of(
                "model", selectedModel,
                "provider", provider.name(),
                "hostConfigured", configuredHost != null && !configuredHost.isBlank()
        ));
        cachedModel = selectedModel;
        cachedHost = configuredHost == null ? null : configuredHost.trim();
        cachedApiKey = apiKey;
        return cachedSession;
    }
    /**
     * Retrieves a cached ChatbotSession or creates a new one if none exists
     * @return ChatbotSession instance
     */
    public synchronized ChatbotSession getChatbotSession(String selectedModel) {
        // String selectedModel = System.getenv("LICENSE_TOOL_MODEL");
        LOGGER.info("Get chatbot session for model: " + selectedModel);
        if (selectedModel == null || selectedModel.isEmpty()) {
            LOGGER.warn("No model specified in environment, defaulting to gpt-4o");
            selectedModel = "gpt-4o";

        }

        boolean isOpenAiModel = ModelSettings.INSTANCE.getProviderForModel(selectedModel) == ModelProvider.OPENAI;
        String currentHost = isOpenAiModel ? null : ModelSettings.INSTANCE.getOllamaHost(project);
        String currentApiKey = ApiKeyStore.INSTANCE.getOpenAiKey(project);
        boolean hostChanged = !isOpenAiModel && !Objects.equals(trimToNull(currentHost), trimToNull(cachedHost));
        boolean apiKeyChanged = isOpenAiModel && !Objects.equals(currentApiKey, cachedApiKey);

        if (cachedSession == null || cachedModel == null || !cachedModel.equals(selectedModel) || hostChanged || apiKeyChanged) {
            LOGGER.info("No cachedSession, creating new chatbot session for model: " + selectedModel);
            return getNewChatbotSession(selectedModel);
        }
        return cachedSession;
    }

    /** Optional: allow resetting if config/env changed. */
    public synchronized void resetChatbotSession() {
        cachedSession = null;
        cachedModel = null;
        cachedHost = null;
        cachedApiKey = null;
        LOGGER.info("Chatbot session reset");
        UsageLogger.INSTANCE.logInteraction(project, null, "chat_session_reset", null);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Sends prompt to LLM for license analysis
     * @param promptData JSONObject with context, code, and event information
     * @return JSONObject with LLM analysis and recommendations
     */
    public JSONObject getLLMAnalysis(JSONObject promptData) {
        try {
            // Extract components needed for LLM prompt
            JSONObject eventContext = promptData.getJSONObject("eventContext");
            JSONObject projectContext = promptData.getJSONObject("projectContext");
            String userQuery = promptData.optString("userQuery", "");
            String analysisMode = promptData.optString("analysisMode", "DETECT_ISSUES");
            JSONObject licenseDetails = promptData.optJSONObject("licenseDetails");

            // Log operation
            LOGGER.info("Getting LLM analysis for event: " + eventContext.getString("eventType"));

            // Call LLM manager to get analysis
            String llmResponse = llmManager.getAnalysis(eventContext, projectContext,
                                                      userQuery, analysisMode, licenseDetails);

            // Parse response into JSON
            JSONObject parsedResponse;
            try {
                parsedResponse = new JSONObject(llmResponse);
            } catch (Exception e) {
                // If response isn't valid JSON, create a simple JSON object with the response
                parsedResponse = new JSONObject();
                parsedResponse.put("response", llmResponse);
                parsedResponse.put("structured", false);
            }

            // Check if LLM is requesting tool calls
            JSONObject functionCall = parseLLMFunctionCall(llmResponse);
            if (functionCall != null && !functionCall.isEmpty()) {
                parsedResponse.put("toolCall", functionCall);
            }

            return parsedResponse;
        } catch (Exception e) {
            LOGGER.error("Error getting LLM analysis: " + e.getMessage());
            JSONObject errorResult = new JSONObject();
            errorResult.put("success", false);
            errorResult.put("error", "Failed to get LLM analysis: " + e.getMessage());
            return errorResult;
        }
    }

    /**
     * Formats LLM response for user consumption
     * @param llmOutput Raw output from LLM
     * @return Formatted string or JSONObject for display to the user
     */
    public Object formatLLMResponse(String llmOutput) {
        try {
            // Attempt to parse as JSON
            JSONObject jsonOutput = new JSONObject(llmOutput);

            // Create formatted notification object
            JSONObject formatted = new JSONObject();

            // Determine notification type
            String notificationType = "INFO";
            if (jsonOutput.has("severity")) {
                String severity = jsonOutput.getString("severity");
                switch (severity.toUpperCase()) {
                    case "HIGH":
                        notificationType = "ERROR";
                        break;
                    case "MEDIUM":
                        notificationType = "WARNING";
                        break;
                    case "LOW":
                        notificationType = "INFO";
                        break;
                    default:
                        notificationType = "INFO";
                }
            }

            // Get title and message
            String title = jsonOutput.optString("title", "Licensing Analysis");
            String message = jsonOutput.optString("message", jsonOutput.toString());

            // Set formatted notification
            formatted.put("notificationType", notificationType);
            formatted.put("title", title);
            formatted.put("message", message);

            // Add actions if present
            if (jsonOutput.has("actions")) {
                formatted.put("actions", jsonOutput.getJSONArray("actions"));
            } else {
                // Add default actions
                JSONArray actions = new JSONArray();
                JSONObject viewDetailsAction = new JSONObject();
                viewDetailsAction.put("label", "View Details");
                viewDetailsAction.put("actionId", "VIEW_DETAILS");
                actions.put(viewDetailsAction);

                formatted.put("actions", actions);
            }

            return formatted;
        } catch (Exception e) {
            // Not valid JSON, return simple formatted version
            JSONObject formatted = new JSONObject();
            formatted.put("notificationType", "INFO");
            formatted.put("title", "Licensing Information");
            formatted.put("message", llmOutput);

            // Add default action
            JSONArray actions = new JSONArray();
            JSONObject dismissAction = new JSONObject();
            dismissAction.put("label", "Dismiss");
            dismissAction.put("actionId", "DISMISS");
            actions.put(dismissAction);

            formatted.put("actions", actions);

            return formatted;
        }
    }

    /**
     * Creates a function call for tools based on LLM output
     * @param llmOutput String output from the LLM
     * @return JSONObject with function name and parameters to call
     */
    public JSONObject parseLLMFunctionCall(String llmOutput) {
        try {
            // Try to parse as JSON
            JSONObject json = new JSONObject(llmOutput);

            // Check for function call indicators
            if (json.has("functionCall") || json.has("toolCall")) {
                JSONObject functionCall = json.optJSONObject("functionCall");
                if (functionCall == null) {
                    functionCall = json.optJSONObject("toolCall");
                }

                if (functionCall != null) {
                    return functionCall;
                }
            }

            // Parse from text if JSON structure doesn't contain explicit function call
            // This is a simplistic implementation - in practice, you'd use more sophisticated parsing
            if (llmOutput.contains("function:") || llmOutput.contains("tool:")) {
                JSONObject functionCall = new JSONObject();

                // Extract function name
                String functionName = extractBetween(llmOutput, "function:", "\n");
                if (functionName.isEmpty()) {
                    functionName = extractBetween(llmOutput, "tool:", "\n");
                }

                // Extract parameters section
                String paramsSection = extractBetween(llmOutput, "parameters:", "```");

                // Create basic parameters object
                JSONObject params = new JSONObject();
                String[] paramLines = paramsSection.split("\n");
                for (String line : paramLines) {
                    if (line.contains(":")) {
                        String[] parts = line.split(":", 2);
                        if (parts.length == 2) {
                            String key = parts[0].trim();
                            String value = parts[1].trim();
                            params.put(key, value);
                        }
                    }
                }

                // Set function call data
                functionCall.put("functionName", functionName.trim());
                functionCall.put("parameters", params);

                return functionCall;
            }

            // No function call found
            return new JSONObject();
        } catch (Exception e) {
            LOGGER.warn("Error parsing LLM function call: " + e.getMessage());
            return new JSONObject();
        }
    }

    // Helper method to extract text between two markers
    private String extractBetween(String text, String start, String end) {
        int startIndex = text.indexOf(start);
        if (startIndex == -1) return "";

        startIndex += start.length();
        int endIndex = text.indexOf(end, startIndex);
        if (endIndex == -1) endIndex = text.length();

        return text.substring(startIndex, endIndex).trim();
    }

    //---------------------------------------------------------------------
    // User Configuration Functions
    //---------------------------------------------------------------------

    /**
     * Retrieves user configuration about license preferences
     * @return JSONObject with user's licensing configuration
     */
    public JSONObject getUserLicenseConfig() {
        //return this.userLicenseConfig; //this just returns a default...
        String surveyPath = this.project.getBasePath() + "/.license-tool/license-survey.json";
        try {
            String jsonString = Files.readString(Paths.get(surveyPath)).trim();
            JSONObject json = new JSONObject(jsonString);
            return json;
        }
        catch (IOException | JSONException e)
        {
            LOGGER.warn("Error getting license config: " + e.getMessage());
            return new JSONObject();
        }
    }

    /**
     * Gets a License representing the user's target license, or an "unknown" license
     * if one cannot be found
     * @return License object named after user's target license
     */
    public License getTargetLicense()
    {
        JSONObject userConfig = getUserLicenseConfig();
        String licenseName = userConfig.optString("intendedLicense", "").trim();
        if (licenseName.isEmpty()) {
            JSONArray licenseUsed = userConfig.optJSONArray("existingLicensesUsed");
            if (licenseUsed != null) {
                licenseName = licenseUsed.optString(0, "unknown");
            } else {
                licenseName = "unknown";
            }
        }

        License myLicense = new License (licenseName, "");
        return myLicense;
    }

    /**
     * Updates user configuration for license preferences
     * @param configData New configuration data
     * @return boolean indicating success of update
     */
    public boolean updateUserLicenseConfig(JSONObject configData) {
        try {
            // Validate configuration
            if (!validateConfig(configData)) {
                LOGGER.warn("Invalid configuration provided");
                return false;
            }

            // Update configuration
            this.userLicenseConfig = configData;

            // Save configuration
            boolean saved = configManager.saveConfiguration(configData);

            // Log operation
            if (saved) {
                LOGGER.info("User license configuration updated successfully");
            } else {
                LOGGER.warn("Failed to save user license configuration");
            }

            return saved;
        } catch (Exception e) {
            LOGGER.error("Error updating user license config: " + e.getMessage());
            return false;
        }
    }

    // Helper method to validate configuration
    private boolean validateConfig(JSONObject config) {
        // Check for required fields
        if (!config.has("projectInfo") || !config.getJSONObject("projectInfo").has("intendedLicense")) {
            return false;
        }

        // Additional validation as needed
        return true;
    }

    //---------------------------------------------------------------------
    // Event Processing Functions
    //---------------------------------------------------------------------

    /**
     * Determines if an event is significant enough to notify the user
     * @param eventData Event information
     * @return boolean indicating if notification is needed
     */
    public boolean shouldNotifyUser(JSONObject eventData) {
        try {
            // Extract event context
            JSONObject eventContext = eventData.getJSONObject("eventContext");
            String eventType = eventContext.getString("eventType");

            // Get user preferences
            JSONObject userPrefs = userLicenseConfig.getJSONObject("notificationSettings");

            // Check specific event types
            switch (eventType) {
                case "DEPENDENCY_ADDED":
                case "DEPENDENCY_UPDATED":
                    return userPrefs.optBoolean("notifyOnNewDependencies", true);

                case "FILE_ADDED":
                    return userPrefs.optBoolean("notifyOnMissingHeaders", true);

                case "LICENSE_FILE_CHANGED":
                    // Always notify for license file changes
                    return true;

                default:
                    // Default to notifying for unknown event types
                    return true;
            }
        } catch (Exception e) {
            LOGGER.warn("Error determining if notification needed: " + e.getMessage());
            // Default to showing notification if there's an error
            return true;
        }
    }

    /**
     * Creates a complete context object for LLM processing
     * @param eventData Original event data
     * @param userConfig User configuration
     * @param codebaseContext Additional context from the codebase
     * @return Comprehensive context object for LLM
     */
    public JSONObject buildLLMContext(JSONObject eventData, JSONObject userConfig, JSONObject codebaseContext) {
        try {
            // Create base context object
            JSONObject context = new JSONObject();

            // Add event information
            context.put("eventContext", eventData);

            // Add project information
            JSONObject projectContext = new JSONObject();
            if (userConfig.has("projectInfo")) {
                projectContext.put("name", userConfig.getJSONObject("projectInfo").optString("name", "Unknown Project"));
                projectContext.put("organization", userConfig.getJSONObject("projectInfo").optString("organization", ""));
                projectContext.put("declaredLicense", userConfig.getJSONObject("projectInfo").optString("intendedLicense", "UNKNOWN"));
                projectContext.put("repositoryVisibility", userConfig.getJSONObject("projectInfo").optString("repositoryVisibility", "UNKNOWN"));
            } else {
                projectContext.put("declaredLicense", "UNKNOWN");
            }

            // Add codebase context if provided
            if (codebaseContext != null && !codebaseContext.isEmpty()) {
                projectContext.put("codebase", codebaseContext);
            }

            // Add dependencies from state
            JSONArray dependencies = state.getDependencies();
            if (dependencies != null && dependencies.length() > 0) {
                projectContext.put("dependencies", dependencies);
            }

            context.put("projectContext", projectContext);

            // Add user preferences
            if (userConfig.has("licensePreferences")) {
                context.put("licensePreferences", userConfig.getJSONObject("licensePreferences"));
            }

            // Add analysis mode based on event type
            String eventType = eventData.optString("eventType", "UNKNOWN");
            String analysisMode;
            switch (eventType) {
                case "DEPENDENCY_ADDED":
                    analysisMode = "ANALYZE_DEPENDENCY";
                    break;
                case "FILE_ADDED":
                    analysisMode = "CHECK_FILE_LICENSE";
                    break;
                case "LICENSE_FILE_CHANGED":
                    analysisMode = "ANALYZE_LICENSE_CHANGE";
                    break;
                default:
                    analysisMode = "DETECT_ISSUES";
            }
            context.put("analysisMode", analysisMode);

            // Add previous issues from state
            JSONArray issues = state.getLicensingIssues();
            if (issues != null && issues.length() > 0) {
                context.put("existingIssues", issues);
            }

            return context;
        } catch (Exception e) {
            LOGGER.error("Error building LLM context: " + e.getMessage());
            // Return basic context with just the event data
            JSONObject basic = new JSONObject();
            basic.put("eventContext", eventData);
            return basic;
        }
    }

    //---------------------------------------------------------------------
    // Inner Classes
    //---------------------------------------------------------------------

    /**
     * Enumeration of licensing event types
     */
    public enum LicensingEventType {
        DEPENDENCY_ADDED,
        DEPENDENCY_UPDATED,
        DEPENDENCY_REMOVED,
        FILE_ADDED,
        FILE_MODIFIED,
        MEDIA_ADDED,
        LICENSE_FILE_CHANGED
    }

    /**
     * Enumeration of notification types
     */
    public enum NotificationType {
        INFO,
        WARNING,
        ERROR,
        ACTION_REQUIRED,
        QUESTION
    }

    /**
     * Maintains the current state of the controller
     */
    private class ControllerState {
        // Current project license
        private String projectLicense;

        // Map of files to their detected licenses
        private Map<String, String> fileLicenses;

        // Dependencies and their licenses
        private Map<String, String> dependencyLicenses;

        // Detected license conflicts
        private List<JSONObject> licenseConflicts;

        // Event history for context
        private List<JSONObject> recentEvents;

        // Issues detected
        private List<JSONObject> licensingIssues;

        /**
         * Constructor initializes the state
         */
        public ControllerState() {
            this.projectLicense = "UNKNOWN";
            this.fileLicenses = new HashMap<>();
            this.dependencyLicenses = new HashMap<>();
            this.licenseConflicts = new ArrayList<>();
            this.recentEvents = new ArrayList<>();
            this.licensingIssues = new ArrayList<>();
        }

        /**
         * Update a file's detected license
         */
        public void updateFileLicense(String filePath, String license) {
            this.fileLicenses.put(filePath, license);
        }

        /**
         * Update dependency licenses from analysis
         */
        public void updateDependencyLicenses(JSONArray dependencyLicenses) {
            for (int i = 0; i < dependencyLicenses.length(); i++) {
                JSONObject dep = dependencyLicenses.getJSONObject(i);
                String id = dep.getString("id");
                String license = dep.getString("license");
                this.dependencyLicenses.put(id, license);
            }
        }

        /**
         * Update detected license conflicts
         */
        public void updateLicenseConflicts(JSONArray conflicts) {
            this.licenseConflicts.clear();
            for (int i = 0; i < conflicts.length(); i++) {
                this.licenseConflicts.add(conflicts.getJSONObject(i));
            }
        }

        /**
         * Add an event to history
         */
        public void addEvent(JSONObject event) {
            this.recentEvents.add(event);
            // Keep only recent events (e.g., last 10)
            if (this.recentEvents.size() > 10) {
                this.recentEvents.remove(0);
            }
        }

        /**
         * Add licensing issues to state
         */
        public void addLicensingIssues(JSONArray issues) {
            for (int i = 0; i < issues.length(); i++) {
                this.licensingIssues.add(issues.getJSONObject(i));
            }
        }

        /**
         * Get project context based on current state
         */
        public JSONObject getProjectContext() {
            JSONObject context = new JSONObject();
            context.put("declaredLicense", this.projectLicense);

            // Add file licenses
            JSONArray files = new JSONArray();
            for (Map.Entry<String, String> entry : this.fileLicenses.entrySet()) {
                JSONObject file = new JSONObject();
                file.put("path", entry.getKey());
                file.put("license", entry.getValue());
                files.put(file);
            }
            context.put("files", files);

            // Add dependencies
            JSONArray deps = new JSONArray();
            for (Map.Entry<String, String> entry : this.dependencyLicenses.entrySet()) {
                JSONObject dep = new JSONObject();
                dep.put("id", entry.getKey());
                dep.put("license", entry.getValue());
                deps.put(dep);
            }
            context.put("dependencies", deps);

            // Add conflicts if any
            if (!this.licenseConflicts.isEmpty()) {
                JSONArray conflicts = new JSONArray();
                for (JSONObject conflict : this.licenseConflicts) {
                    conflicts.put(conflict);
                }
                context.put("licenseConflicts", conflicts);
            }

            return context;
        }

        /**
         * Get dependencies as JSON array
         */
        public JSONArray getDependencies() {
            JSONArray deps = new JSONArray();
            for (Map.Entry<String, String> entry : this.dependencyLicenses.entrySet()) {
                JSONObject dep = new JSONObject();
                dep.put("id", entry.getKey());
                dep.put("license", entry.getValue());
                deps.put(dep);
            }
            return deps;
        }

        /**
         * Get detected licensing issues
         */
        public JSONArray getLicensingIssues() {
            JSONArray issues = new JSONArray();
            for (JSONObject issue : this.licensingIssues) {
                issues.put(issue);
            }
            return issues;
        }
    }

    /**
     * Manages persistent configuration
     */
    private class ConfigurationManager {
        private static final String CONFIG_FILE_PATH = "licensing_plugin_config.json";

        /**
         * Load configuration from disk
         */
        public JSONObject loadConfiguration() {
            try {
                // In a real implementation, read from disk
                // For this skeleton, return default config
                return getDefaultConfiguration();
            } catch (Exception e) {
                LOGGER.warn("Failed to load configuration, using defaults: " + e.getMessage());
                return getDefaultConfiguration();
            }
        }

        /**
         * Save configuration to disk
         */
        public boolean saveConfiguration(JSONObject config) {
            try {
                // In a real implementation, write to disk
                LOGGER.info("Configuration saved to " + CONFIG_FILE_PATH);
                return true;
            } catch (Exception e) {
                LOGGER.error("Failed to save configuration: " + e.getMessage());
                return false;
            }
        }

        /**
         * Get default configuration
         */
        public JSONObject getDefaultConfiguration() {
            JSONObject config = new JSONObject();

            // Project info
            JSONObject projectInfo = new JSONObject();
            projectInfo.put("name", "");
            projectInfo.put("organization", "");
            projectInfo.put("intendedLicense", "");
            projectInfo.put("repositoryVisibility", "PRIVATE");
            config.put("projectInfo", projectInfo);

            // License preferences
            JSONObject licensePrefs = new JSONObject();
            JSONArray allowedLicenses = new JSONArray();
            allowedLicenses.put("MIT");
            allowedLicenses.put("Apache-2.0");
            allowedLicenses.put("BSD-3-Clause");
            licensePrefs.put("allowedLicenses", allowedLicenses);

            JSONArray prohibitedLicenses = new JSONArray();
            prohibitedLicenses.put("GPL-3.0");
            prohibitedLicenses.put("AGPL-3.0");
            licensePrefs.put("prohibitedLicenses", prohibitedLicenses);
            config.put("licensePreferences", licensePrefs);

            // Notification settings
            JSONObject notificationSettings = new JSONObject();
            notificationSettings.put("notifyOnConflict", true);
            notificationSettings.put("notifyOnMissingHeaders", true);
            notificationSettings.put("notifyOnNewDependencies", true);
            notificationSettings.put("severityThreshold", "WARNING");
            config.put("notificationSettings", notificationSettings);

            // Tool integrations
            JSONObject toolIntegrations = new JSONObject();
            toolIntegrations.put("enableLicenseDetection", true);
            toolIntegrations.put("enableCodeScanning", true);
            toolIntegrations.put("enableDependencyManagement", true);
            toolIntegrations.put("enablePackageRetrieval", true);
            toolIntegrations.put("enableLLMAnalysis", true);
            config.put("toolIntegrations", toolIntegrations);

            return config;
        }
    }

    /**
     * Manager for tool API calls
     */
    private class ToolManager {

        /**
         * Call license detection tool
         */
        public JSONObject callLicenseDetectionTool(JSONArray files, JSONArray licenseFiles, JSONObject scanOptions) {
            // In a real implementation, call the actual tool
            // For this skeleton, return dummy result
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("toolName", "LicenseDetectionTool");
            result.put("timestamp", System.currentTimeMillis());

            JSONArray detectedLicenses = new JSONArray();
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                JSONObject license = new JSONObject();
                license.put("filePath", file.getString("path"));
                license.put("license", "Apache-2.0"); // Dummy license
                license.put("confidence", 0.95);
                detectedLicenses.put(license);
            }

            result.put("detectedLicenses", detectedLicenses);
            result.put("executionTime", 1200);

            return result;
        }

        /**
         * Call code scanning tool
         */
        public JSONObject callCodeScanningTool(JSONArray files, JSONObject scanOptions, JSONArray knownExternalSources) {
            // In a real implementation, call the actual tool
            // For this skeleton, return dummy result
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("toolName", "CodeScanningTool");
            result.put("timestamp", System.currentTimeMillis());

            JSONArray issues = new JSONArray();

            // Add dummy issue
            if (files.length() > 0) {
                JSONObject issue = new JSONObject();
                issue.put("type", "MISSING_LICENSE_HEADER");
                issue.put("filePath", files.getJSONObject(0).getString("path"));
                issue.put("severity", "MEDIUM");
                issue.put("description", "File is missing a license header");
                issues.put(issue);
            }

            result.put("issues", issues);
            result.put("executionTime", 2500);

            return result;
        }

        /**
         * Call dependency management tool
         */
        public JSONObject callDependencyManagementTool(JSONArray directDependencies, JSONObject buildFile,
                                                    String projectLicense, boolean analyzeTransitive,
                                                    boolean checkCompatibility) {
            // In a real implementation, call the actual tool
            // For this skeleton, return dummy result
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("toolName", "DependencyManagementTool");
            result.put("timestamp", System.currentTimeMillis());

            JSONArray dependencyLicenses = new JSONArray();
            JSONArray conflicts = new JSONArray();

            // Process each dependency
            for (int i = 0; i < directDependencies.length(); i++) {
                JSONObject dep = directDependencies.getJSONObject(i);
                String groupId = dep.getString("groupId");
                String artifactId = dep.getString("artifactId");
                String version = dep.getString("version");
                String id = groupId + ":" + artifactId + ":" + version;

                // Add dummy license information
                JSONObject licenseInfo = new JSONObject();
                licenseInfo.put("id", id);

                // For demo purposes, alternate between compatible and incompatible licenses
                String license = (i % 2 == 0) ? "Apache-2.0" : "GPL-3.0";
                licenseInfo.put("license", license);

                dependencyLicenses.put(licenseInfo);

                // Add conflict if incompatible
                if (license.equals("GPL-3.0") && projectLicense.equals("Apache-2.0") && checkCompatibility) {
                    JSONObject conflict = new JSONObject();

                    JSONObject dependencyA = new JSONObject();
                    dependencyA.put("id", "project");
                    dependencyA.put("license", projectLicense);

                    JSONObject dependencyB = new JSONObject();
                    dependencyB.put("id", id);
                    dependencyB.put("license", license);

                    conflict.put("dependencyA", dependencyA);
                    conflict.put("dependencyB", dependencyB);
                    conflict.put("conflictType", "INCOMPATIBLE_LICENSES");
                    conflict.put("severity", "HIGH");
                    conflict.put("explanation", "GPL-3.0 is not compatible with Apache-2.0 when distributed together");

                    conflicts.put(conflict);
                }
            }

            result.put("dependencyLicenses", dependencyLicenses);

            if (conflicts.length() > 0) {
                result.put("conflicts", conflicts);

                // Add recommendations for conflicts
                JSONArray recommendations = new JSONArray();
                for (int i = 0; i < conflicts.length(); i++) {
                    JSONObject conflict = conflicts.getJSONObject(i);
                    JSONObject dependencyB = conflict.getJSONObject("dependencyB");

                    JSONObject recommendation = new JSONObject();
                    recommendation.put("type", "REPLACE_DEPENDENCY");
                    recommendation.put("targetDependency", dependencyB.getString("id"));

                    JSONArray alternatives = new JSONArray();
                    JSONObject alt = new JSONObject();
                    alt.put("id", "com.alternative:library:1.5.0");
                    alt.put("license", "MIT");
                    alt.put("compatibility", "HIGH");
                    alternatives.put(alt);

                    recommendation.put("alternatives", alternatives);
                    recommendations.put(recommendation);
                }

                result.put("recommendations", recommendations);
            }

            result.put("executionTime", 3500);

            return result;
        }

        /**
         * Call package retrieval tool
         */
        public JSONObject callPackageRetrievalTool(JSONArray packages, boolean includeLicenseText,
                                                 boolean includeAlternatives, JSONArray repositories) {
            // In a real implementation, call the actual tool
            // For this skeleton, return dummy result
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("toolName", "PackageRetrievalTool");
            result.put("timestamp", System.currentTimeMillis());

            JSONArray packageInfos = new JSONArray();

            // Process each package
            for (int i = 0; i < packages.length(); i++) {
                JSONObject pkg = packages.getJSONObject(i);
                String groupId = pkg.getString("groupId");
                String artifactId = pkg.getString("artifactId");
                String version = pkg.getString("version");

                JSONObject packageInfo = new JSONObject();
                packageInfo.put("groupId", groupId);
                packageInfo.put("artifactId", artifactId);
                packageInfo.put("version", version);
                packageInfo.put("license", "Apache-2.0");

                if (includeLicenseText) {
                    packageInfo.put("licenseText", "Apache License, Version 2.0...");
                }

                if (includeAlternatives) {
                    JSONArray alternatives = new JSONArray();

                    JSONObject alt1 = new JSONObject();
                    alt1.put("groupId", groupId);
                    alt1.put("artifactId", artifactId);
                    alt1.put("version", "1.0.0");
                    alt1.put("license", "MIT");
                    alternatives.put(alt1);

                    packageInfo.put("alternatives", alternatives);
                }

                packageInfos.put(packageInfo);
            }

            result.put("packages", packageInfos);
            result.put("executionTime", 1800);

            return result;
        }
    }

    /**
     * Manager for LLM API calls
     */
    private class LLMManager {
        /**
         * Get analysis from LLM
         */
        public String getAnalysis(JSONObject eventContext, JSONObject projectContext,
                                String userQuery, String analysisMode, JSONObject licenseDetails) {
            // In a real implementation, call the actual LLM API
            // For this skeleton, return dummy response

            // Create a basic response structure
            JSONObject response = new JSONObject();

            String eventType = eventContext.getString("eventType");

            // Different responses based on event type
            switch (eventType) {
                case "DEPENDENCY_ADDED":
                    response.put("title", "New Dependency Added");
                    response.put("message", "A new dependency was added to your project. " +
                                "This dependency uses Apache-2.0 license which is compatible with your project license.");
                    response.put("severity", "LOW");
                    break;

                case "FILE_ADDED":
                    response.put("title", "New File Missing License Header");
                    response.put("message", "A new file was added without a license header. " +
                                "It's recommended to add a license header to ensure proper licensing.");
                    response.put("severity", "MEDIUM");

                    // Add tool call for scanning
                    JSONObject toolCall = new JSONObject();
                    toolCall.put("functionName", "scanCode");

                    JSONObject params = new JSONObject();
                    params.put("files", new JSONArray().put(
                        new JSONObject().put("path", eventContext.getJSONObject("details").getString("filePath"))
                    ));

                    toolCall.put("parameters", params);
                    response.put("toolCall", toolCall);
                    break;

                case "LICENSE_FILE_CHANGED":
                    response.put("title", "License File Changed");
                    response.put("message", "The project license file was modified. " +
                                "You should ensure all dependencies comply with the new license terms.");
                    response.put("severity", "HIGH");
                    break;

                default:
                    response.put("title", "Licensing Event Detected");
                    response.put("message", "A licensing-related event was detected in your project.");
                    response.put("severity", "LOW");
            }

            // Add typical actions
            JSONArray actions = new JSONArray();

            JSONObject viewDetailsAction = new JSONObject();
            viewDetailsAction.put("label", "View Details");
            viewDetailsAction.put("actionId", "VIEW_DETAILS");
            actions.put(viewDetailsAction);

            JSONObject dismissAction = new JSONObject();
            dismissAction.put("label", "Dismiss");
            dismissAction.put("actionId", "DISMISS");
            actions.put(dismissAction);

            response.put("actions", actions);

            return response.toString();
        }
    }

    /**
     * Communicator with IDE
     */
    private class IDECommunicator {
        /**
         * Send notification to IDE
         */
        public boolean sendNotification(String type, String title, String message,
                                      JSONArray actions, JSONObject contextData) {
            // In a real implementation, communicate with IDE plugin
            // For this skeleton, just log the notification
            LOGGER.info("Notification sent to IDE - Type: " + type + ", Title: " + title);
            return true;
        }
    }

    //---------------------------------------------------------------------
    // Public API for IDE Plugin Integration
    //---------------------------------------------------------------------

    /**
     * Processes a direct user query from the IDE
     * @param query User's question or request
     * @param contextData Additional context about the current state
     * @return Response to present to the user
     */
    public JSONObject processUserQuery(String query, JSONObject contextData) {
        try {
            LOGGER.info("Processing user query: " + query);

            // Build context for LLM
            JSONObject llmContext = new JSONObject();
            llmContext.put("eventContext", new JSONObject()
                .put("eventType", "USER_QUERY")
                .put("details", new JSONObject().put("query", query)));

            // Add project context
            llmContext.put("projectContext", state.getProjectContext());

            // Add user query
            llmContext.put("userQuery", query);

            // Set analysis mode
            llmContext.put("analysisMode", "ANSWER_QUERY");

            // Additional context if provided
            if (contextData != null && !contextData.isEmpty()) {
                llmContext.put("additionalContext", contextData);
            }

            // Get analysis from LLM
            JSONObject llmAnalysis = getLLMAnalysis(llmContext);

            // Format the response
            Object formattedResponse = formatLLMResponse(llmAnalysis.toString());

            // Return formatted response
            return new JSONObject()
                .put("response", formattedResponse)
                .put("success", true);

        } catch (Exception e) {
            LOGGER.error("Error processing user query: " + e.getMessage());
            return new JSONObject()
                .put("success", false)
                .put("error", "Failed to process query: " + e.getMessage());
        }
    }

    /**
     * Handles IDE action callbacks
     * @param actionId ID of the action that was triggered
     * @param parameters Parameters associated with the action
     * @return Response to the action
     */
    public JSONObject handleIDEAction(String actionId, JSONObject parameters) {
        try {
            LOGGER.info("Handling IDE action: " + actionId);

            JSONObject response = new JSONObject();
            response.put("success", true);

            // Process different action types
            switch (actionId) {
                case "VIEW_DETAILS":
                    // Get details for the specified item
                    if (parameters.has("dependencyId")) {
                        String dependencyId = parameters.getString("dependencyId");
                        response.put("action", "showDependencyDetails");
                        response.put("dependencyDetails", getDependencyDetails(dependencyId));
                    } else if (parameters.has("filePath")) {
                        String filePath = parameters.getString("filePath");
                        response.put("action", "showFileDetails");
                        response.put("fileDetails", getFileDetails(filePath));
                    } else if (parameters.has("licenseId")) {
                        String licenseId = parameters.getString("licenseId");
                        response.put("action", "showLicenseDetails");
                        response.put("licenseDetails", getLicenseDetails(licenseId));
                    } else {
                        response.put("action", "showGeneralDetails");
                        response.put("projectDetails", state.getProjectContext());
                    }
                    break;

                case "FIND_ALTERNATIVES":
                    // Find alternative packages for a dependency
                    if (parameters.has("dependencyId")) {
                        String dependencyId = parameters.getString("dependencyId");
                        response.put("action", "showAlternatives");
                        response.put("alternatives", findAlternatives(dependencyId));
                    }
                    break;

                case "ADD_LICENSE_HEADER":
                    // Generate license header for a file
                    if (parameters.has("filePath")) {
                        String filePath = parameters.getString("filePath");
                        String projectLicense = parameters.optString("license",
                            userLicenseConfig.getJSONObject("projectInfo").optString("intendedLicense", "UNKNOWN"));

                        response.put("action", "addLicenseHeader");
                        response.put("licenseHeader", generateLicenseHeader(filePath, projectLicense));
                    }
                    break;

                case "EXPLAIN_CONFLICT":
                    // Explain a license conflict
                    if (parameters.has("conflictId")) {
                        String conflictId = parameters.getString("conflictId");
                        response.put("action", "explainConflict");
                        response.put("explanation", explainLicenseConflict(conflictId));
                    }
                    break;

                case "DISMISS":
                    // Dismiss the notification
                    response.put("action", "dismiss");
                    break;

                default:
                    response.put("action", "unknown");
                    response.put("message", "Unknown action ID: " + actionId);
            }

            return response;
        } catch (Exception e) {
            LOGGER.error("Error handling IDE action: " + e.getMessage());
            return new JSONObject()
                .put("success", false)
                .put("error", "Failed to handle action: " + e.getMessage());
        }
    }

    /**
     * Gets detailed information about a dependency
     */
    private JSONObject getDependencyDetails(String dependencyId) {
        // In a real implementation, query dependency details
        // For this skeleton, return dummy data
        JSONObject details = new JSONObject();
        details.put("id", dependencyId);
        details.put("license", "Apache-2.0");
        details.put("website", "https://example.com/" + dependencyId);
        details.put("description", "Description for " + dependencyId);

        // Add license compatibility info
        String projectLicense = userLicenseConfig.getJSONObject("projectInfo").optString("intendedLicense", "UNKNOWN");
        boolean isCompatible = true;

        if (dependencyId.contains("gpl")) {
            details.put("license", "GPL-3.0");
            isCompatible = false;
        }

        details.put("isCompatible", isCompatible);
        details.put("compatibilityNotes", isCompatible ?
            "This license is compatible with your project's " + projectLicense + " license." :
            "This license may not be compatible with your project's " + projectLicense + " license.");

        return details;
    }

    /**
     * Gets detailed information about a file
     */
    private JSONObject getFileDetails(String filePath) {
        // In a real implementation, analyze the file
        // For this skeleton, return dummy data
        JSONObject details = new JSONObject();
        details.put("path", filePath);
        details.put("license", state.fileLicenses.getOrDefault(filePath, "UNKNOWN"));
        details.put("hasLicenseHeader", false);
        details.put("recommendations", new JSONArray()
            .put("Add a license header to this file")
            .put("Update file metadata with copyright information"));

        return details;
    }

    /**
     * Gets details about a license
     */
    private JSONObject getLicenseDetails(String licenseId) {
        // In a real implementation, get license details from a database
        // For this skeleton, return dummy data based on common licenses
        JSONObject details = new JSONObject();
        details.put("id", licenseId);

        switch (licenseId) {
            case "Apache-2.0":
                details.put("name", "Apache License 2.0");
                details.put("isPermissive", true);
                details.put("requiresAttribution", true);
                details.put("allowsCommercialUse", true);
                details.put("description", "A permissive license that allows you to use, modify, " +
                    "and distribute the code under certain conditions.");
                break;

            case "GPL-3.0":
                details.put("name", "GNU General Public License v3.0");
                details.put("isPermissive", false);
                details.put("requiresAttribution", true);
                details.put("allowsCommercialUse", true);
                details.put("description", "A copyleft license that requires derivative works " +
                    "to be available under the same license.");
                break;

            case "MIT":
                details.put("name", "MIT License");
                details.put("isPermissive", true);
                details.put("requiresAttribution", true);
                details.put("allowsCommercialUse", true);
                details.put("description", "A permissive license that is compatible with most other licenses.");
                break;

            default:
                details.put("name", licenseId);
                details.put("description", "License details not available.");
        }

        // Add compatibility information
        JSONArray compatibleWith = new JSONArray();

        if (licenseId.equals("Apache-2.0") || licenseId.equals("MIT")) {
            compatibleWith.put("MIT");
            compatibleWith.put("Apache-2.0");
            compatibleWith.put("BSD-3-Clause");
        }

        details.put("compatibleWith", compatibleWith);

        return details;
    }

    /**
     * Finds alternative dependencies with compatible licenses
     */
    private JSONArray findAlternatives(String dependencyId) {
        // In a real implementation, search for alternatives
        // For this skeleton, return dummy data
        JSONArray alternatives = new JSONArray();

        // Parse dependency ID
        String[] parts = dependencyId.split(":");
        String groupId = parts[0];
        String artifactId = parts[1];

        // Create alternatives
        for (int i = 1; i <= 3; i++) {
            JSONObject alt = new JSONObject();
            alt.put("id", "com.alternative" + i + ":" + artifactId + "-alt:" + i + ".0.0");
            alt.put("license", "MIT");
            alt.put("compatibility", "HIGH");
            alt.put("popularity", 4 - i); // Different popularity levels
            alt.put("description", "Alternative " + i + " for " + artifactId);

            alternatives.put(alt);
        }

        return alternatives;
    }

    /**
     * Generates a license header for a file
     */
    private JSONObject generateLicenseHeader(String filePath, String licenseId) {
        // In a real implementation, generate a proper header
        // For this skeleton, return a dummy header
        JSONObject result = new JSONObject();

        // Get current year
        int year = java.time.Year.now().getValue();

        // Get organization name
        String organization = userLicenseConfig.getJSONObject("projectInfo").optString("organization", "Your Organization");

        // Generate header based on license and file type
        String fileExtension = filePath.substring(filePath.lastIndexOf(".") + 1).toLowerCase();
        String commentStart = "//";
        String commentEnd = "";

        if (fileExtension.equals("java") || fileExtension.equals("js") || fileExtension.equals("c") ||
            fileExtension.equals("cpp")) {
            commentStart = "/*";
            commentEnd = " */";
        } else if (fileExtension.equals("xml") || fileExtension.equals("html")) {
            commentStart = "<!--";
            commentEnd = "-->";
        } else if (fileExtension.equals("py")) {
            commentStart = "#";
        }

        StringBuilder header = new StringBuilder();
        header.append(commentStart).append("\n");
        header.append(" * Copyright (c) ").append(year).append(" ").append(organization).append("\n");
        header.append(" * \n");
        header.append(" * Licensed under the ").append(licenseId).append(" license.\n");
        header.append(" * See LICENSE file in the project root for full license information.\n");
        if (!commentEnd.isEmpty()) {
            header.append(commentEnd).append("\n");
        }

        result.put("header", header.toString());
        result.put("success", true);

        return result;
    }

    /**
     * Explains a license conflict in detail
     */
    private JSONObject explainLicenseConflict(String conflictId) {
        // In a real implementation, retrieve conflict details and generate explanation
        // For this skeleton, return a dummy explanation
        JSONObject explanation = new JSONObject();
        explanation.put("id", conflictId);
        explanation.put("licenseA", "Apache-2.0");
        explanation.put("licenseB", "GPL-3.0");
        explanation.put("type", "INCOMPATIBLE_LICENSES");

        // Explanation
        explanation.put("summary", "The GPL-3.0 license is not compatible with Apache-2.0 when distributed together.");

        JSONArray details = new JSONArray();
        details.put("GPL-3.0 is a copyleft license that requires derivative works to be distributed under the same license.");
        details.put("Apache-2.0 has patent termination provisions that conflict with GPL-3.0.");
        details.put("Distributing software that combines code under these licenses may violate the terms of both licenses.");

        explanation.put("details", details);

        // Recommendations
        JSONArray recommendations = new JSONArray();
        recommendations.put("Replace the GPL-3.0 licensed dependency with an alternative that has a compatible license.");
        recommendations.put("Contact the maintainers of the GPL-3.0 library to see if they offer the code under alternative licensing.");
        recommendations.put("Consult with legal counsel to determine the best course of action for your specific situation.");

        explanation.put("recommendations", recommendations);

        return explanation;
    }
    @Override
    public void dispose() {
        LOGGER.info("Licensing Controller disposing");
        try {
            // If you have background tasks/listeners registered to this controller as Disposables,
            // they should be registered via Disposer.register(this, child) and will be disposed automatically.
            // Clear cached LLM session to avoid leaks.
            resetChatbotSession();
        } catch (Exception e) {
            LOGGER.warn("Error during Licensing Controller disposal: " + e.getMessage());
        } finally {
            // Clear other cached state if needed
            cachedModel = null;
        }
    }
}

package com.example.my_plugin;

import chatbot.HttpLlmChatSession;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

import controller.LicensingController;
import org.apache.commons.io.IOUtils;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.util.*;

import com.google.gson.JsonArray;

import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.diagnostic.Logger;


// Creates a project-level service for the pom.xml listener.
// The service will be automatically created when the project opens and registers the listener.
public final class MavenDependencyServiceImpl implements MavenDependencyService, Disposable
{
    private final Project project;
    private static final Logger LOG = com.example.my_plugin.LogInitializer.getLogger(MavenDependencyServiceImpl.class);
    // private final MavenDependencyListener listener;

    public MavenDependencyServiceImpl(Project project) {
        this.project = project;
    }

    @Override
    public void dispose() {
        //See parent and https://plugins.jetbrains.com/docs/intellij/disposers.html?from=jetbrains.org#automatically-disposed-objects
    }

    @Override
    public void flagNewDependency() {
        flagNewDependency(null, null);
    }

    @Override
    public void flagNewDependency(ProgressIndicator indicator) {
        flagNewDependency(indicator, null);
    }

    @Override
    public void flagNewDependency(ProgressIndicator indicator, String changedPomPath) {
        // This method is called when a new dependency is added to the pom.xml file.
        LOG.info("New dependency detected, starting analysis pipeline.");
        UsageLogger.INSTANCE.logInteraction(project, null, "dependency_change_detected", eventData(
                "changedPomPath", changedPomPath
        ));
        // return the depJson object to the controller
        JsonObject depJson = getChanges(indicator, changedPomPath);
        if(!depJson.isEmpty()) {
            LOG.debug("flagNewDependency - licenseChange called");
            // write the depJson object to file for debugging purposes
            try {
                writeJsonToFile(depJson, project.getBasePath() + "/.license-tool/dependency-diff.json");
            } catch (IOException e) {
                LOG.warn("Error writing JSON to file: " + e.getMessage());
            }

            // Always run a proactive compliance check against intended project license for changed dependencies.
            LicensingController controller = this.project.getService(LicensingController.class);
            License intendedLicense = controller.getTargetLicense();
            String intendedMode = getIntendedLicenseMode();
            ArrayList<Map<License, String>> conflicts = new ArrayList<>();
            conflicts.add(new HashMap<>());
            conflicts.add(new HashMap<>());
            conflicts.add(new HashMap<>());
            if ("SELECTED_LICENSE".equalsIgnoreCase(intendedMode)) {
                try {
                    conflicts = getConflicts(intendedLicense, depJson);
                } catch (Exception e) {
                    LOG.warn("Conflict extraction failed, continuing with LLM-only assessment: " + e.getMessage());
                    UsageLogger.INSTANCE.logInteraction(project, null, "conflict_extraction_failed", eventData(
                            "error", e.getMessage()
                    ));
                    conflicts = new ArrayList<>();
                    conflicts.add(new HashMap<>());
                    conflicts.add(new HashMap<>());
                    conflicts.add(new HashMap<>());
                }
            }
            triggerProactiveComplianceCheck(depJson, intendedLicense, conflicts);

            LOG.debug("flagNewDependency - engageChatbot called");
        }

    }

    public static void writeJsonToFile(JsonObject jsonObject, String filePath) throws IOException {
        File target = new File(filePath);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
            }
        }
        try (FileWriter file = new FileWriter(filePath)) {
            file.write(jsonObject.toString());
        }
    }

    private void notifyError(String title, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("Licensing Tool")
                    .createNotification(title, message, NotificationType.ERROR)
                    .notify(project);
        });
    }

    private void notifyInfo(String title, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("Licensing Tool")
                    .createNotification(title, message, NotificationType.INFORMATION)
                    .notify(project);
        });
    }

    private void notifyAnalysisComplete() {
        ApplicationManager.getApplication().invokeLater(() -> {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("Licensing Tool")
                    .createNotification("LiA: Licensing analysis complete", NotificationType.INFORMATION)
                    .addAction(NotificationAction.createSimple("Learn more", () -> {
                        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Licensing Assistant");
                        if (toolWindow != null) {
                            toolWindow.show();
                        }
                    }))
                    .notify(project);
        });
    }

    private boolean hasSavedLicensingConfiguration() {
        if (project.getBasePath() == null || project.getBasePath().isBlank()) {
            return false;
        }
        Path surveyPath = Paths.get(project.getBasePath(), ".license-tool", "license-survey.json");
        if (!Files.exists(surveyPath)) {
            return false;
        }
        try {
            String raw = Files.readString(surveyPath, StandardCharsets.UTF_8).trim();
            if (raw.isBlank()) {
                return false;
            }
            JsonObject survey = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            String intended = survey.has("intendedLicense") ? survey.get("intendedLicense").getAsString().trim() : "";
            return !intended.isBlank() && !"unknown".equalsIgnoreCase(intended);
        } catch (Exception e) {
            LOG.warn("Failed to inspect licensing configuration: " + e.getMessage());
            return false;
        }
    }

    private String getIntendedLicenseMode() {
        if (project.getBasePath() == null || project.getBasePath().isBlank()) {
            return "SELECTED_LICENSE";
        }
        Path surveyPath = Paths.get(project.getBasePath(), ".license-tool", "license-survey.json");
        if (!Files.exists(surveyPath)) {
            return "SELECTED_LICENSE";
        }
        try {
            String raw = Files.readString(surveyPath, StandardCharsets.UTF_8).trim();
            if (raw.isBlank()) {
                return "SELECTED_LICENSE";
            }
            JsonObject survey = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            if (survey.has("intendedLicenseMode")) {
                return survey.get("intendedLicenseMode").getAsString().trim();
            }
            String intended = survey.has("intendedLicense") ? survey.get("intendedLicense").getAsString().trim() : "";
            if ("Proprietary/commercial license".equalsIgnoreCase(intended)) {
                return "PROPRIETARY_COMMERCIAL";
            }
            if ("I don't know/Unassigned".equalsIgnoreCase(intended)) {
                return "UNASSIGNED";
            }
        } catch (Exception e) {
            LOG.warn("Failed to read intended licensing mode: " + e.getMessage());
        }
        return "SELECTED_LICENSE";
    }

    private void appendAutomaticMessageToStoredSession(String text, String model) {
        ChatSessionStore.StoredSession session = ChatSessionStore.INSTANCE.loadActiveSession(project);
        if (session == null) {
            session = ChatSessionStore.INSTANCE.createSession(project, model);
        } else {
            ChatSessionStore.INSTANCE.setActiveSession(project, session.getId());
        }
        UsageLogger.INSTANCE.setCurrentSessionId(project, session.getId());
        ChatSessionStore.INSTANCE.appendMessage(
                project,
                session.getId(),
                new ChatSessionStore.StoredMessage("bot", text, null, System.currentTimeMillis()),
                model
        );
    }

    private void triggerProactiveComplianceCheck(JsonObject depJson, License intendedLicense, ArrayList<Map<License, String>> conflicts) {
        boolean hasAdded = depJson.has("addedComponents") && depJson.getAsJsonArray("addedComponents").size() > 0;
        if (!hasAdded) {
            LOG.info("Dependency diff has no added components; skipping proactive LLM check.");
            UsageLogger.INSTANCE.logInteraction(project, null, "proactive_compliance_check_skipped", null);
            return;
        }
        long startedAtMs = System.currentTimeMillis();
        String intendedMode = getIntendedLicenseMode();

        String fixable = conflicts.size() > 0 ? summarizeConflicts(conflicts.get(0)) : "{}";
        String legal = conflicts.size() > 1 ? summarizeConflicts(conflicts.get(1)) : "{}";
        String unknown = conflicts.size() > 2 ? summarizeConflicts(conflicts.get(2)) : "{}";

        String inputPrompt;
        if ("PROPRIETARY_COMMERCIAL".equalsIgnoreCase(intendedMode)) {
            inputPrompt = "Automatic licensing guidance triggered by dependency change.\n" +
                    "Selected licensing mode: proprietary/commercial.\n" +
                    "Dependency diff (added/removed): " + depJson + "\n" +
                    "Please explain what can be inferred from the newly added dependencies and ask the user the minimum clarifying questions needed about the intended proprietary/commercial terms, distribution model, and reuse constraints.\n" +
                    "Return a concise user-facing answer. Do not pretend a final open-source license has been selected.";
        } else if ("UNASSIGNED".equalsIgnoreCase(intendedMode)) {
            inputPrompt = "Automatic licensing guidance triggered by dependency change.\n" +
                    "Selected licensing mode: unassigned.\n" +
                    "Dependency diff (added/removed): " + depJson + "\n" +
                    "Please help the user identify suitable project license options based on the current dependencies and questionnaire context. Mention key tradeoffs and uncertainty.\n" +
                    "Return a concise user-facing answer.";
        } else {
            inputPrompt = "Automatic compliance check triggered by dependency change.\n" +
                    "Project intended license: " + intendedLicense.getType() + "\n" +
                    "Dependency diff (added/removed): " + depJson + "\n" +
                    "Pre-check conflicts (actionable): " + fixable + "\n" +
                    "Pre-check conflicts (legal review likely): " + legal + "\n" +
                    "Pre-check conflicts (unknown): " + unknown + "\n" +
                    "Please determine whether the newly added dependencies are compliant with the intended project license.\n" +
                    "Return a concise result with status (COMPLIANT, POTENTIAL_ISSUE, NEEDS_LEGAL_REVIEW), impacted dependencies, rationale, and recommended actions.";
        }

        UsageLogger.INSTANCE.logInteraction(project, null, "proactive_compliance_check_started", eventData(
                "trigger", "dependency_change",
                "intendedLicenseMode", intendedMode,
                "intendedLicense", intendedLicense.getType(),
                "addedComponents", depJson.has("addedComponents") ? depJson.getAsJsonArray("addedComponents").size() : 0,
                "removedComponents", depJson.has("removedComponents") ? depJson.getAsJsonArray("removedComponents").size() : 0
        ));

        ApplicationManager.getApplication().invokeLater(() -> {
            MyToolWindowFactory.ChatUi toolWindow = MyToolWindowBridge.Companion.getInstance(project).getUi();
            if (toolWindow == null) {
                if (!hasSavedLicensingConfiguration()) {
                    UsageLogger.INSTANCE.logInteraction(project, null, "proactive_compliance_check_deferred", eventData(
                            "trigger", "dependency_change",
                            "durationMs", System.currentTimeMillis() - startedAtMs,
                            "reason", "missing_licensing_configuration"
                    ));
                    notifyInfo(
                            "Dependency Compliance Check Pending",
                            "Dependency changes were detected. Open LiA once and enter the project license configuration information to enable automatic LLM compliance messages."
                    );
                    return;
                }

                final String selectedModel = ModelSettings.INSTANCE.getSelectedModel(project);
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        appendAutomaticMessageToStoredSession(
                                "Automatic check started: verifying newly added dependencies against intended license.",
                                selectedModel
                        );
                        String response = project.getService(LicensingController.class)
                                .getChatbotSession(selectedModel)
                                .submitPrompt(inputPrompt);
                        appendAutomaticMessageToStoredSession(response, selectedModel);
                        UsageLogger.INSTANCE.logInteraction(project, null, "proactive_compliance_check_completed", eventData(
                                "trigger", "dependency_change",
                                "durationMs", System.currentTimeMillis() - startedAtMs
                        ));
                        notifyAnalysisComplete();
                    } catch (Throwable t) {
                        UsageLogger.INSTANCE.logInteraction(project, null, "proactive_compliance_check_failed", eventData(
                                "trigger", "dependency_change",
                                "durationMs", System.currentTimeMillis() - startedAtMs,
                                "error", t.getMessage()
                        ));
                        notifyError("LiA: Licensing analysis failed", t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                    }
                });
                return;
            }
            toolWindow.appendToChatHistory("Automatic check started: verifying newly added dependencies against intended license.");
            toolWindow.submitAutomaticCheckMessage(inputPrompt, () -> {
                UsageLogger.INSTANCE.logInteraction(project, null, "proactive_compliance_check_completed", eventData(
                        "trigger", "dependency_change",
                        "durationMs", System.currentTimeMillis() - startedAtMs
                ));
                if (!toolWindow.isToolWindowVisible(project)) {
                    notifyAnalysisComplete();
                }
            }, () -> UsageLogger.INSTANCE.logInteraction(project, null, "proactive_compliance_check_failed", eventData(
                    "trigger", "dependency_change",
                    "durationMs", System.currentTimeMillis() - startedAtMs
            )));
        });
    }

    private String summarizeConflicts(Map<License, String> conflicts) {
        if (conflicts == null || conflicts.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<License, String> entry : conflicts.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey().getType()).append(": ").append(entry.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    public JsonObject getChanges() {
        return getChanges(null, null);
    }

    public JsonObject getChanges(ProgressIndicator indicator) {
        return getChanges(indicator, null);
    }

    public JsonObject getChanges(ProgressIndicator indicator, String changedPomPath) {
        // This method is called when a pom.xml file is added, modified, or removed.
        // It should analyze the dependencies and generate a new SBOM.
        LOG.debug("getChanges - diffSbom called");
        LOG.info("Analyzing dependency changes via SBOM diff.");
        if (indicator != null) {
            indicator.setText("Generating SBOM");
        }
        File[] sbomFiles = genSbom(indicator, changedPomPath);
        File prevSbom = sbomFiles[0];
        File currSbom = sbomFiles[1];
        if (indicator != null) {
            indicator.checkCanceled();
            indicator.setText("Diffing SBOM");
        }
        if (currSbom == null) {
            // genSbom already logs and notifies the specific failure reason.
            LOG.error("Failed to generate SBOM files. Current SBOM is null.");
            UsageLogger.INSTANCE.logInteraction(project, null, "dependency_diff_failed", eventData("reason", "curr_sbom_null"));
            return new JsonObject(); // Return an empty JSON object if no changes are detected.
        } else if (prevSbom != null) {
            // If a previous SBOM exists, compare it with the current SBOM.
            LOG.info("Previous SBOM found, performing diff with current SBOM.");
            try {
                if (indicator != null) indicator.checkCanceled();
                SbomDiffResult results = diffSbomXml(prevSbom, currSbom);
                Set<String> addedComponents = results.added;
                Set<String> removedComponents = results.removed;
                // parse the sets to a list of Dependency objects
                List<Dependency> addedDependencies = parseSetToList(addedComponents);
                List<Dependency> removedDependencies = parseSetToList(removedComponents);
                // Create a JSON object to hold the results
                JsonObject diffResults = new JsonObject();
                JsonArray addedArray = new JsonArray();
                if (!addedDependencies.isEmpty() || !removedDependencies.isEmpty()) {
                    for (Dependency dep : addedDependencies) {
                        addedArray.add(dep.toJson());
                    }
                    JsonArray removedArray = new JsonArray();
                    for (Dependency dep : removedDependencies) {
                        removedArray.add(dep.toJson());
                    }
                    diffResults.add("addedComponents", addedArray);
                    diffResults.add("removedComponents", removedArray);
                    UsageLogger.INSTANCE.logInteraction(project, null, "dependency_diff_computed", eventData(
                            "mode", "diff",
                            "addedCount", addedDependencies.size(),
                            "removedCount", removedDependencies.size()
                    ));
                    return diffResults;
                }
            } catch (ProcessCanceledException cancelled) {
                UsageLogger.INSTANCE.logInteraction(project, null, "dependency_diff_cancelled", eventData("mode", "diff"));
                throw cancelled;
            } catch (Exception e) {
                LOG.error("Error analyzing dependencies: " + e.getMessage());
                notifyError("Dependency Analysis Error", "Error analyzing dependencies: " + e.getMessage());
                UsageLogger.INSTANCE.logInteraction(project, null, "dependency_diff_failed", eventData(
                        "mode", "diff",
                        "error", e.getMessage()
                ));
                return new JsonObject(); // Return an empty JSON object if analysis fails.
            }
        } else {
            // If no previous SBOM exists, only analyze the current SBOM (e.g. new pom.xml).
            LOG.info("No previous SBOM found, analyzing current SBOM only.");
            try {
                if (indicator != null) indicator.checkCanceled();
                List<Dependency> currDependencies = parseSetToList(extractComponentKeys(currSbom));
                // Create a JSON object to hold the results
                JsonObject diffResults = new JsonObject();
                JsonArray addedArray = new JsonArray();
                if (!currDependencies.isEmpty()) {
                    for (Dependency dep : currDependencies) {
                        addedArray.add(dep.toJson());
                    }
                    diffResults.add("addedComponents", addedArray);
                    diffResults.add("removedComponents", new JsonArray());
                    UsageLogger.INSTANCE.logInteraction(project, null, "dependency_diff_computed", eventData(
                            "mode", "initial_scan",
                            "addedCount", currDependencies.size(),
                            "removedCount", 0
                    ));
                    return diffResults;
                }
            } catch (ProcessCanceledException cancelled) {
                UsageLogger.INSTANCE.logInteraction(project, null, "dependency_diff_cancelled", eventData("mode", "initial_scan"));
                throw cancelled;
            } catch (Exception e) {
                LOG.error("Error analyzing current SBOM: " + e.getMessage());
                UsageLogger.INSTANCE.logInteraction(project, null, "dependency_diff_failed", eventData(
                        "mode", "initial_scan",
                        "error", e.getMessage()
                ));
                return new JsonObject(); // Return an empty JSON object if no changes are detected.
            }
        }
        // If no changes are detected, return an empty JSON object.
        LOG.info("No changes detected in dependencies.");
        UsageLogger.INSTANCE.logInteraction(project, null, "dependency_diff_no_changes", null);
        return new JsonObject(); // Return an empty JSON object if no changes are detected.
    }

    /**
     * Given a set of changes to project dependencies, get the set of licenses which may conflict with each other or
     * the target project's own license, paired with explanations for the conflicts
     * @param changes Json breakdown of the changes to the project (should be formatted as from getChanges())
     * @return Three mappings of licenses to descriptions of how they conflict with your repo: 1) that the tool thinks
     * it can address, 2) that it thinks need a legal expert, and 3) that are unknown/uncategorized
     */
    public ArrayList<Map<License, String>> getConflicts(License myLicense, JsonObject changes)
    {

        Map<License, String> conflicts = new HashMap<>();

        //Extract licenses from added components
        List<License> allLicenses = new ArrayList<>();
        JsonArray addedComponents = changes.getAsJsonArray("addedComponents");

        for (JsonElement depElem : addedComponents)
        {
            JsonObject dependency = depElem.getAsJsonObject();
            JsonArray licenses = dependency.getAsJsonArray("licenses");

            for (JsonElement licElem : licenses)
            {
                JsonObject license = licElem.getAsJsonObject();
                String licenseName = license.get("type").getAsString();
                String licenseUrl = license.get("url").getAsString();

                License licObj = new License(licenseName, licenseUrl);
                allLicenses.add(licObj);
            }
        }

        //Compare licenses against compatibility matrix to identify if any conflicts exist

        List<String[]> matrix = new ArrayList<>();
        loadCompatibilityMatrix(matrix);

        //Find the row corresponding with my license, then find all conflicts
        for (int i = 1; i < matrix.size(); i++)
        {
            String[] licenseArr = matrix.get(i);
            if (licenseArr[0].equals(myLicense.getType()))
            {
                for (License potentialConflict : allLicenses)
                {
                    String pcType = potentialConflict.getType();
                    for (int j = 0; j < matrix.get(0).length; j++)
                    {
                        String matLicense = matrix.get(0)[j];
                        if (matLicense.equals(pcType))
                        {
                            conflicts.put(potentialConflict, matrix.get(i)[j]);
                        }
                    }
                }
                break;
            }
        }

        //Via the chatbot, verify conflicts and derive reasons for them
        Map<License, String> descriptiveConflicts = deriveConflictReasons(myLicense, conflicts);
        //Categorize the conflicts into what we can fix vs what we can't
        ArrayList<Map<License, String>> categorizedConflicts = categorizeConflicts(myLicense, descriptiveConflicts);
        //Return the conflicts paired with their descriptors
        return categorizedConflicts;
    }

    private void loadCompatibilityMatrix(List<String[]> matrix) {
        try (InputStream stream = MavenDependencyServiceImpl.class.getClassLoader().getResourceAsStream("license_data/matrix.csv")) {
            if (stream == null) {
                throw new IllegalStateException("Compatibility matrix resource not found: license_data/matrix.csv");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line = reader.readLine();
                while (line != null) {
                    matrix.add(line.split(","));
                    line = reader.readLine();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read compatibility matrix resource", e);
        }
    }

    /**
     * Given a dictionary of potential conflicts mapped to string indicators of whether there is a conflict, prune all
     * items that are presumed to not be conflicts, and replace basic indicators with detailed descriptions for the
     * rest.
     * @param ownLicense target project's license
     * @param potentialConflicts dictionary of potentially conflict-inducing licenses mapped to yes/no/dep./? indicators
     *                           (from intermediate in getConflicts)
     * @return dictionary of confirmed conflicting licenses mapped to descriptions of the conflicts
     */
    public Map<License, String> deriveConflictReasons(License ownLicense, Map<License, String> potentialConflicts)
    {
        Set<String> checkedLicenses = new HashSet<>();
        Map<License, String> conflicts = new HashMap<>();

        //Read in the system prompt for the helper instance
        String systemPrompt;
        try {
            InputStream is = LicensingController.class.getClassLoader().getResourceAsStream("prompts/system-reasons.txt");
            systemPrompt = IOUtils.toString(is, "UTF-8");
        }
        catch (Exception e)
        {
            //Unable to load system prompt
            systemPrompt = "You are a component in an IDE designed to analyze software license conflicts, determining whether given licenses conflict, and, if so, why.";
        }

        //Read in the template to be used when prompting the helper instance
        String inputPromptTemplate;
        try {
            InputStream is = LicensingController.class.getClassLoader().getResourceAsStream("prompts/reasons-input-template.txt");
            inputPromptTemplate = IOUtils.toString(is, "UTF-8");
        }
        catch (Exception e)
        {
            //Unable to load input prompt template
            inputPromptTemplate = "My software license, {myLicense}, " +
            "may conflict with another license, {otherLicense}" +
                    ". If they conflict, please give me a concise, one-sentence description of why these two " +
                    "licenses may conflict with each other, including any conditions upon that conflict." +
                    "Otherwise, say \"NO CONFLICT\" in capital letters and nothing else. If you do not" +
                    "know the answer, say \"UNSURE\" in capital letters and nothing else.";
        }

        HttpLlmChatSession conflictChatbot = createConfiguredHelperSession(systemPrompt);

        for (Map.Entry<License, String> entry : potentialConflicts.entrySet())
        {
            if (!(checkedLicenses.contains(entry.getKey().getType())))
            {
                checkedLicenses.add(entry.getKey().getType());
                String reason;
                try
                {
                    switch (entry.getValue())
                    {
                        case "Yes":
                        case "Same":
                            break; //Don't add confirmed compatible licenses to our list of conflicts
                        case "No":
                        case "Dep.":
                        case "Check dependency":
                        case "?":
                            String inputPrompt = inputPromptTemplate.replace("{myLicense}", ownLicense.getType()).replace("{otherLicense}", entry.getKey().getType());
                            reason = conflictChatbot.submitPrompt(inputPrompt);
                            conflicts.put(entry.getKey(), reason);
                            break;
                        default:
                            reason = "Unknown license relationship.";
                            conflicts.put(entry.getKey(), reason);
                            break;
                    }
                }
                catch (Exception e)
                {
                    reason = "Chatbot failed to analyze license conflicts.";
                    conflicts.put(entry.getKey(), reason);
                }
            }
        }
        return conflicts;
    }

    public ArrayList<Map<License, String>> categorizeConflicts(License ownLicense, Map<License, String> allConflicts)
    {

        //Read in the system prompt for the helper instance
        String systemPrompt;
        try {
            InputStream is = LicensingController.class.getClassLoader().getResourceAsStream("prompts/system-categorization.txt");
            systemPrompt = IOUtils.toString(is, "UTF-8");
        }
        catch (Exception e)
        {
            //Unable to load system prompt
            systemPrompt = "You are a component in an IDE designed to analyze software license conflicts and determine whether yourself, an LLM, can safely and reasonably make recommendations to address the conflict, or if the conflict should not be " +
                    "answered by an LLM and instead requires the counsel of a legal expert.";
        }

        //Read in the template to be used when prompting the helper instance
        String inputPromptTemplate;
        try {
            InputStream is = LicensingController.class.getClassLoader().getResourceAsStream("prompts/categorization-input-template.txt");
            inputPromptTemplate = IOUtils.toString(is, "UTF-8");
        }
        catch (Exception e)
        {
            //Unable to load input prompt template
            inputPromptTemplate = "My software is licensed under {myLicense} " +
                    " and conflicts with the license {otherLicense}" +
                    " for the following reason: {reason}. " +
                    " If this is a conflict that you can safely address with what you know now, respond \"A\" and nothing else. " +
                    " If this is a conflict that would require analysis from a legal expert, respond \"B\" and nothing else.";
        }

        HttpLlmChatSession categorizationChatbot = createConfiguredHelperSession(systemPrompt);

        ArrayList<Map<License, String>> categorizedConflicts = new ArrayList<>();

        Map<License, String> fixableConflicts = new HashMap<>(); //The set of conflicts that the model thinks that it can address/make recommendations for
        Map<License, String> nonfixableConflicts = new HashMap<>(); //The set of conflicts that the model thinks it's best to send to a legal expert
        Map<License, String> unknownConflicts = new HashMap<>();

        for (Map.Entry<License, String> entry : allConflicts.entrySet())
        {
            try {
                String inputPrompt = inputPromptTemplate.replace("{myLicense}", ownLicense.getType()).replace("{otherLicense}", entry.getKey().getType()).replace("{reason}", entry.getValue());
                String cat = categorizationChatbot.submitPrompt(inputPrompt);
                if (cat.equals("A")) {
                    fixableConflicts.put(entry.getKey(), entry.getValue());
                } else if (cat.equals("B")) {
                    nonfixableConflicts.put(entry.getKey(), entry.getValue());
                }
                else {
                    throw new RuntimeException("unknown conflict category: " + cat);
                }
            }
            catch (Exception e) //The chatbot either failed or gave some invalid output
            {
                unknownConflicts.put(entry.getKey(), entry.getValue());
            }
        }

        categorizedConflicts.add(fixableConflicts);
        categorizedConflicts.add(nonfixableConflicts);
        categorizedConflicts.add(unknownConflicts);

        return categorizedConflicts;
    }

    private HttpLlmChatSession createConfiguredHelperSession(String systemPrompt) {
        String selectedModel = ModelSettings.INSTANCE.getSelectedModel(project);
        ModelProvider provider = ModelSettings.INSTANCE.getProviderForModel(selectedModel);
        String host = provider == ModelProvider.OPENAI ? null : ModelSettings.INSTANCE.getOllamaHost(project);
        String apiKey = provider == ModelProvider.OPENAI ? ApiKeyStore.INSTANCE.getOpenAiKey(project) : null;
        return new HttpLlmChatSession(host, selectedModel, systemPrompt, apiKey, null, provider, project);
    }

    public File[] genSbom() {
        return genSbom(null, null);
    }

    @Override
    public File[] genSbom(ProgressIndicator indicator) {
        return genSbom(indicator, null);
    }

    @Override
    public File[] genSbom(ProgressIndicator indicator, String changedPomPath) {
        // This method is called to analyze a dependency and return its details.
        // It should be called when a new dependency is added to the pom.xml file.
        long startedAtMs = System.currentTimeMillis();
        String basePath = project.getBasePath();
        if (indicator != null) {
            indicator.setText("Generating SBOM");
            indicator.checkCanceled();
        }
        if (basePath == null) {
            notifyError("Dependency Analysis Error", "Project base path is not set.");
            LOG.error("Project base path is not set.");
            UsageLogger.INSTANCE.logInteraction(project, null, "sbom_generation_failed", eventData("reason", "missing_project_base_path"));
            return new File[] {null, null};
        }
        File mavenProjectDir = resolveMavenProjectDir(basePath, changedPomPath);
        if (mavenProjectDir == null) {
            String where = (changedPomPath == null || changedPomPath.isBlank()) ? basePath : changedPomPath;
            notifyError("Dependency Analysis Error", "pom.xml not found for: " + where);
            LOG.error("pom.xml not found for SBOM generation. basePath=" + basePath + ", changedPomPath=" + changedPomPath);
            UsageLogger.INSTANCE.logInteraction(project, null, "sbom_generation_failed", eventData(
                    "reason", "pom_not_found",
                    "basePath", basePath,
                    "changedPomPath", changedPomPath
            ));
            return new File[] {null, null};
        }

        Path sbomDir = Paths.get(basePath, ".license-tool");
        Path newSbomPath = sbomDir.resolve("bom.xml");
        File newSbomFile = newSbomPath.toFile();
        Path prevSbomPath = sbomDir.resolve("bom-prev.xml");
        File prevSbomFile = prevSbomPath.toFile();
        try{
            Files.createDirectories(sbomDir);
            String outputDir = sbomDir.toAbsolutePath().toString();
            LOG.info("Generating SBOM for Maven directory: " + mavenProjectDir.getAbsolutePath());
            LOG.info("SBOM output directory: " + outputDir);
            UsageLogger.INSTANCE.logInteraction(project, null, "sbom_generation_started", eventData(
                    "mavenProjectDir", mavenProjectDir.getAbsolutePath(),
                    "outputDir", outputDir,
                    "changedPomPath", changedPomPath
            ));
            LOG.debug("sbomFile: " + newSbomFile.getAbsolutePath());
            LOG.debug("prevSbomFile: " + prevSbomFile.getAbsolutePath());
            LOG.debug("sbomFile.exists(): " + newSbomFile.exists());
            // Backup current SBOM before regenerating
            if (newSbomFile.exists()) {
                Files.copy(newSbomFile.toPath(), prevSbomFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Previous SBOM backed up to: " + prevSbomFile.getAbsolutePath());
            } else {
                LOG.info("No previous SBOM found.");
                prevSbomFile = null; // No previous SBOM to compare against
            }
            // Generate new SBOM
            if (indicator != null) indicator.checkCanceled();
            newSbomFile = CycloneDxMavenInvoker.INSTANCE.generateSbom(mavenProjectDir, outputDir, indicator);
            LOG.info("New SBOM generated to: " + newSbomFile.getAbsolutePath());
            UsageLogger.INSTANCE.logInteraction(project, null, "sbom_generation_completed", eventData(
                    "outputFile", newSbomFile.getAbsolutePath(),
                    "previousExists", prevSbomFile != null,
                    "durationMs", System.currentTimeMillis() - startedAtMs
            ));

            // Refresh the VFS to ensure listeners receive the change events
            try {
                LocalFileSystem localFs = LocalFileSystem.getInstance();
                VirtualFile vf = localFs.refreshAndFindFileByIoFile(newSbomFile);
                if (vf == null) vf = localFs.refreshAndFindFileByIoFile(newSbomFile.getParentFile());
                if (vf != null) {
                    VfsUtil.markDirtyAndRefresh(false, true, true, vf);
                    LOG.debug("VFS refreshed for: " + vf.getPath());
                } else {
                    VirtualFileManager.getInstance().asyncRefresh(null);
                    LOG.debug("VFS async refresh triggered");
                }
            } catch (Exception e) {
                LOG.error("Error refreshing VFS: " + e.getMessage());
            }

            // Always write a dependency summary for LLM context (full current dependencies)
            try {
                writeDependencySummary(newSbomFile, mavenProjectDir, indicator);
            } catch (Exception e) {
                LOG.warn("Failed to write dependency summary: " + e.getMessage());
            }
        } catch (ProcessCanceledException cancelled) {
            UsageLogger.INSTANCE.logInteraction(project, null, "sbom_generation_cancelled", eventData(
                    "changedPomPath", changedPomPath,
                    "durationMs", System.currentTimeMillis() - startedAtMs
            ));
            throw cancelled;
        } catch (Exception e){
            LOG.error("Error analyzing dependencies: " + e.getMessage());
            String reason = sbomFailureReason(e);
            String userMessage = sbomFailureUserMessage(e);
            notifyError("SBOM Generation Failed", userMessage);
            UsageLogger.INSTANCE.logInteraction(project, null, "sbom_generation_failed", eventData(
                    "changedPomPath", changedPomPath,
                    "reason", reason,
                    "error", e.getMessage(),
                    "durationMs", System.currentTimeMillis() - startedAtMs
            ));
            return new File[] {null, null};
        }
        return new File[] {prevSbomFile, newSbomFile};
    }

    private String sbomFailureReason(Exception e) {
        if (e instanceof CycloneDxMavenInvoker.MavenExecutionException) {
            return ((CycloneDxMavenInvoker.MavenExecutionException) e).getReason();
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("cannot run program") || message.contains("no such file") || message.contains("not found")) {
            return "maven_not_found";
        }
        if (message.contains("timed out")) {
            return "sbom_timeout";
        }
        return "sbom_generation_error";
    }

    private String sbomFailureUserMessage(Exception e) {
        String reason = sbomFailureReason(e);
        return switch (reason) {
            case "maven_not_found" -> "Maven was not found or could not be started. Install Maven, add it to PATH, configure MAVEN_HOME/M2_HOME, or add a Maven wrapper (mvnw) to the project.";
            case "sbom_timeout" -> "SBOM generation timed out. Check that Maven can resolve project dependencies, or increase the timeout if the project is slow to build.";
            case "maven_command_failed" -> "Maven failed while generating the SBOM. Check the Maven output, project pom.xml, dependency resolution, and network access.";
            case "sbom_missing" -> "Maven completed, but LiA could not find the generated bom.xml file.";
            default -> "LiA could not generate the SBOM: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        };
    }

    private Map<String, Object> eventData(Object... keyValues) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key != null) {
                data.put(String.valueOf(key), keyValues[i + 1]);
            }
        }
        return data;
    }

    private File resolveMavenProjectDir(String basePath, String changedPomPath) {
        if (changedPomPath != null && !changedPomPath.isBlank()) {
            File changedPom = new File(changedPomPath);
            if (changedPom.exists() && changedPom.isFile() && "pom.xml".equalsIgnoreCase(changedPom.getName())) {
                return changedPom.getParentFile();
            }
        }

        File rootPom = new File(basePath, "pom.xml");
        if (rootPom.exists()) {
            return rootPom.getParentFile();
        }

        File baseDir = new File(basePath);
        if (!baseDir.exists() || !baseDir.isDirectory()) {
            return null;
        }

        List<File> pomFiles = new ArrayList<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(baseDir.toPath())) {
            paths
                    .filter(path -> path.getFileName() != null && "pom.xml".equalsIgnoreCase(path.getFileName().toString()))
                    .forEach(path -> pomFiles.add(path.toFile()));
        } catch (IOException e) {
            LOG.warn("Error searching for pom.xml under " + basePath + ": " + e.getMessage());
            return null;
        }
        if (pomFiles.isEmpty()) {
            return null;
        }
        pomFiles.sort(Comparator.comparingInt(file -> file.toPath().getNameCount()));
        File chosen = pomFiles.get(0).getParentFile();
        LOG.info("Resolved Maven project directory from nested pom.xml: " + chosen.getAbsolutePath());
        return chosen;
    }

    public SbomDiffResult diffSbomXml(File prevSbom, File currSbom) throws Exception {
        Set<String> prevComponents = extractComponentKeys(prevSbom);
        Set<String> currComponents = extractComponentKeys(currSbom);

        Set<String> added = new HashSet<>(currComponents);
        added.removeAll(prevComponents);

        Set<String> removed = new HashSet<>(prevComponents);
        removed.removeAll(currComponents);

        // Print the added and removed components for debugging purposes
        if (!added.isEmpty()) {
            LOG.info("Added components: " + String.join(", ", added));
        }
        if (!removed.isEmpty()) {
            LOG.info("Removed components: " + String.join(", ", removed));
        }
        if (added.isEmpty() && removed.isEmpty()) {
            LOG.info("Nothing to do. No component changes detected.");
        }

        // Return the sets of added and removed components
        return new SbomDiffResult(added, removed);
    }

    private Set<String> extractComponentKeys(File sbomFile) throws Exception {
        Set<String> keys = new HashSet<>();
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(sbomFile);
        for (Element comp : SbomComponentExtractor.extractDependencyComponents(doc)) {
            String group = getTagValue(comp, "group");
            String name = getTagValue(comp, "name");
            String version = getTagValue(comp, "version");
            String license = getTagValue(comp, "license");
            LOG.debug("group: " + group);
            LOG.debug("name: " + name);
            LOG.debug("version: " + version);
            LOG.debug("License: " + license.trim());
            keys.add(group + ":" + name + ":" + version + (license.isEmpty() ? "" : ":" + license));
        }
        return keys;
    }

    private String getTagValue(Element element, String tag) {
        // This method retrieves the text content of a specific tag from an XML element.
        // If the tag is "license", it retrieves all license nodes and concatenates their text content.
        if ("license".equals(tag)) {
            NodeList licenseNodes = element.getElementsByTagName("license");
            StringBuilder licenses = new StringBuilder();
            for (int i = 0; i < licenseNodes.getLength(); i++) {
                String license = licenseNodes.item(i).getTextContent();
                if (license != null && !license.trim().isEmpty()) {
                    if (!licenses.isEmpty()) licenses.append(",");
                    licenses.append(license.trim());
                }
            }
            return licenses.toString();
        } else {
            NodeList nl = element.getElementsByTagName(tag);
            if (nl.getLength() > 0 && nl.item(0).getTextContent() != null) {
                return nl.item(0).getTextContent();
            }
            return "";
        }
    }

    // This class represents the result of the SBOM diff operation.
        public record SbomDiffResult(Set<String> added, Set<String> removed) {
    }

    public static List<Dependency> parseSetToList(Set<String> depSet) {
        // This method converts a set of dependency strings to a list of Dependency objects.
        List<Dependency> dependencies = new ArrayList<>();
        for (String dep : depSet) {
            String[] parts = dep.split(":", 4); // Split by ':' and limit to 4 parts
            if (parts.length >= 3) {
                String group = parts[0];
                String name = parts[1];
                String version = parts[2];
                List<License> licenses = new ArrayList<>();
                if (parts.length > 3) {
                    String[] licenseParts = parts[3].split(",");
                    for (String license : licenseParts) {
                        // Trim whitespace and create a License object for each license
                        // split the license string to extract the license type and URL if available
                        if (license.contains("\n")) {
                            String[] licenseInfo = license.split("\n", 2);
                            licenses.add(new License(licenseInfo[0].trim(), licenseInfo.length > 1 ? licenseInfo[1].trim() : ""));
                        } else {
                            // If no URL is provided, just use the license type
                            licenses.add(new License(license.trim(), ""));
                        }
                    }
                }
                dependencies.add(new Dependency(group, name, version, licenses));
            }
        }
        return dependencies;
    }

    private record DependencyTreeInfo(boolean direct, String introducedBy, List<String> path, int depth) {
    }

    private void writeDependencySummary(File sbomFile, File mavenProjectDir, ProgressIndicator indicator) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return;
        }
        Set<String> keys = extractComponentKeys(sbomFile);
        List<Dependency> deps = parseSetToList(keys);
        Set<String> directFromPom = extractDirectDependenciesFromPom(new File(mavenProjectDir, "pom.xml"));
        Map<String, DependencyTreeInfo> treeInfo = extractDependencyTreeInfo(mavenProjectDir, basePath, indicator);
        for (Dependency dep : deps) {
            String id = dep.id();
            String ga = dep.group + ":" + dep.name;
            DependencyTreeInfo info = treeInfo.get(id);
            if (info != null) {
                dep.direct = info.direct();
                dep.introducedBy = info.direct() ? null : info.introducedBy();
                dep.dependencyPath = info.path();
                dep.treeDepth = info.depth();
            } else if (directFromPom.contains(ga)) {
                dep.direct = true;
                dep.introducedBy = null;
                dep.dependencyPath = List.of(id);
                dep.treeDepth = 1;
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", System.currentTimeMillis());
        JsonArray depsArray = new JsonArray();
        for (Dependency dep : deps) {
            depsArray.add(dep.toJson());
        }
        root.add("dependencies", depsArray);
        String outPath = Paths.get(basePath, ".license-tool", "dependency-summary.json").toString();
        writeJsonToFile(root, outPath);
    }

    private Set<String> extractDirectDependenciesFromPom(File pomFile) {
        Set<String> direct = new HashSet<>();
        if (pomFile == null || !pomFile.exists()) {
            return direct;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document doc = factory.newDocumentBuilder().parse(pomFile);
            Element root = doc.getDocumentElement();
            if (root == null) {
                return direct;
            }
            NodeList projectChildren = root.getChildNodes();
            for (int i = 0; i < projectChildren.getLength(); i++) {
                Node child = projectChildren.item(i);
                if (!(child instanceof Element element) || !"dependencies".equals(element.getTagName())) {
                    continue;
                }
                NodeList dependencyNodes = element.getChildNodes();
                for (int j = 0; j < dependencyNodes.getLength(); j++) {
                    Node dependencyNode = dependencyNodes.item(j);
                    if (!(dependencyNode instanceof Element dependency) || !"dependency".equals(dependency.getTagName())) {
                        continue;
                    }
                    String group = textContent(dependency, "groupId");
                    String artifact = textContent(dependency, "artifactId");
                    if (!group.isBlank() && !artifact.isBlank()) {
                        direct.add(group + ":" + artifact);
                    }
                }
                break;
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse direct dependencies from pom.xml: " + e.getMessage());
        }
        return direct;
    }

    private Map<String, DependencyTreeInfo> extractDependencyTreeInfo(File mavenProjectDir, String basePath, ProgressIndicator indicator) {
        Map<String, DependencyTreeInfo> result = new HashMap<>();
        try {
            File treeFile = Paths.get(basePath, ".license-tool", "dependency-tree.txt").toFile();
            MavenDependencyTreeInvoker.INSTANCE.generateDependencyTree(mavenProjectDir, treeFile, indicator);
            List<String> lines = Files.readAllLines(treeFile.toPath());
            List<String> stack = new ArrayList<>();
            boolean rootSeen = false;
            for (String rawLine : lines) {
                String line = rawLine == null ? "" : rawLine.trim();
                if (line.isBlank()) {
                    continue;
                }
                if (line.startsWith("[INFO]")) {
                    line = line.substring(6).trim();
                }
                if (line.isBlank()) {
                    continue;
                }
                String prefix = extractTreePrefix(line);
                String coordinates = line.substring(prefix.length()).trim();
                String id = dependencyIdFromTreeCoordinates(coordinates);
                if (id == null) {
                    continue;
                }
                int depth = prefix.length() / 3;
                if (!rootSeen) {
                    rootSeen = true;
                    continue;
                }
                while (stack.size() >= depth) {
                    stack.remove(stack.size() - 1);
                }
                stack.add(id);
                boolean direct = depth == 1;
                String introducedBy = direct ? null : (stack.size() > 1 ? stack.get(0) : null);
                result.put(id, new DependencyTreeInfo(direct, introducedBy, new ArrayList<>(stack), depth));
            }
        } catch (ProcessCanceledException cancelled) {
            throw cancelled;
        } catch (Exception e) {
            LOG.warn("Failed to build dependency tree metadata: " + e.getMessage());
        }
        return result;
    }

    private String extractTreePrefix(String line) {
        int idx = 0;
        while (idx < line.length()) {
            char c = line.charAt(idx);
            if (Character.isLetterOrDigit(c)) {
                break;
            }
            idx++;
        }
        return line.substring(0, idx);
    }

    private String dependencyIdFromTreeCoordinates(String coordinates) {
        String[] parts = coordinates.split(":");
        if (parts.length < 4) {
            return null;
        }
        String group = parts[0].trim();
        String artifact = parts[1].trim();
        String version = parts.length >= 5 ? parts[parts.length - 2].trim() : parts[3].trim();
        if (group.isBlank() || artifact.isBlank() || version.isBlank()) {
            return null;
        }
        return group + ":" + artifact + ":" + version;
    }

    private String textContent(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0 || nodes.item(0) == null) {
            return "";
        }
        String value = nodes.item(0).getTextContent();
        return value == null ? "" : value.trim();
    }
}

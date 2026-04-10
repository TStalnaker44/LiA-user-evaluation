package chatbot.tools;

import org.json.JSONObject;

public final class GetProjectLicenseInfoTool implements ChatTool {
    private final ProjectToolData data;

    public GetProjectLicenseInfoTool(ProjectToolData data) {
        this.data = data;
    }

    @Override
    public String getName() {
        return "get_licensing_for_my_project";
    }

    @Override
    public String getDescription() {
        return "Returns the detected and intended licensing information currently stored for the developer project.";
    }

    @Override
    public JSONObject getParametersSchema() {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject());
    }

    @Override
    public String execute(JSONObject arguments) {
        JSONObject survey = data.loadSurvey();
        if (survey == null) {
            return "No license questionnaire is available for the current project.";
        }
        String intended = survey.optString("intendedLicense", "").trim();
        String intendedMode = survey.optString("intendedLicenseMode", "").trim();
        JSONObject detected = survey.optJSONObject("detectedLicense");
        String detectedType = detected == null ? "" : detected.optString("detectedType", "").trim();
        StringBuilder builder = new StringBuilder();
        if (!detectedType.isBlank()) {
            builder.append("Detected repository license: ").append(detectedType).append('\n');
        } else {
            builder.append("Detected repository license: not found\n");
        }
        if (!intended.isBlank()) {
            builder.append("Intended repository license: ").append(intended).append('\n');
        } else {
            builder.append("Intended repository license: not selected\n");
        }
        if (!intendedMode.isBlank()) {
            String modeLabel = switch (intendedMode) {
                case "PROPRIETARY_COMMERCIAL" -> "Proprietary/commercial";
                case "UNASSIGNED" -> "I don't know/Unassigned";
                default -> "Selected license";
            };
            builder.append("Intended licensing mode: ").append(modeLabel).append('\n');
            if ("PROPRIETARY_COMMERCIAL".equalsIgnoreCase(intendedMode)) {
                builder.append("Guidance: ask clarifying questions about the proprietary/commercial licensing terms and the intended distribution model.\n");
            } else if ("UNASSIGNED".equalsIgnoreCase(intendedMode)) {
                builder.append("Guidance: help the user identify a suitable license using the questionnaire answers, dependency licenses, and project context.\n");
            }
        }
        String notes = survey.optString("notes", "").trim();
        if (!notes.isBlank()) {
            builder.append("User notes: ").append(notes);
        }
        return builder.toString().trim();
    }
}

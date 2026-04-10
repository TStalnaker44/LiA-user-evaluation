package chatbot.tools;

import org.json.JSONArray;
import org.json.JSONObject;

public final class GetDependencyListTool implements ChatTool {
    private final ProjectToolData data;

    public GetDependencyListTool(ProjectToolData data) {
        this.data = data;
    }

    @Override
    public String getName() {
        return "get_dependency_list";
    }

    @Override
    public String getDescription() {
        return "Returns the dependencies currently detected for the project, including known licenses when available.";
    }

    @Override
    public JSONObject getParametersSchema() {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject());
    }

    @Override
    public String execute(JSONObject arguments) {
        JSONArray dependencies = data.loadDependencies();
        if (dependencies.isEmpty()) {
            return "No dependencies were found in the current SBOM or dependency summary.";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Dependencies currently detected:\n");
        int maxItems = Math.min(dependencies.length(), 100);
        for (int i = 0; i < maxItems; i++) {
            JSONObject dependency = dependencies.getJSONObject(i);
            builder.append("- ").append(dependency.optString("id", dependency.optString("name", "unknown")));
            builder.append(formatRelationship(dependency));
            String licenses = formatLicenses(dependency.optJSONArray("licenses"));
            if (!licenses.isBlank()) {
                builder.append(" [").append(licenses).append("]");
            }
            builder.append('\n');
        }
        if (dependencies.length() > maxItems) {
            builder.append("... truncated ").append(dependencies.length() - maxItems).append(" additional dependencies");
        }
        return builder.toString().trim();
    }

    private String formatLicenses(JSONArray licenses) {
        if (licenses == null || licenses.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < licenses.length(); i++) {
            JSONObject license = licenses.optJSONObject(i);
            if (license == null) {
                continue;
            }
            String type = license.optString("type", "").trim();
            if (type.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(type);
        }
        return builder.toString();
    }

    private String formatRelationship(JSONObject dependency) {
        if (!dependency.has("direct")) {
            return "";
        }
        if (dependency.optBoolean("direct", false)) {
            return " (direct)";
        }
        String introducedBy = dependency.optString("introducedBy", "").trim();
        if (!introducedBy.isBlank()) {
            return " (transitive via " + introducedBy + ")";
        }
        return " (transitive)";
    }
}

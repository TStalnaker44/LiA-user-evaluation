package chatbot.tools;

import org.json.JSONArray;
import org.json.JSONObject;

public final class GetDependencyLicenseTool implements ChatTool {
    private final ProjectToolData data;

    public GetDependencyLicenseTool(ProjectToolData data) {
        this.data = data;
    }

    @Override
    public String getName() {
        return "get_dependency_license";
    }

    @Override
    public String getDescription() {
        return "Returns the known license information for a specific dependency in the current project.";
    }

    @Override
    public JSONObject getParametersSchema() {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("dependency", new JSONObject()
                                .put("type", "string")
                                .put("description", "Dependency name or full Maven coordinate to inspect.")))
                .put("required", new JSONArray().put("dependency"));
    }

    @Override
    public String execute(JSONObject arguments) {
        String dependencyName = arguments == null ? "" : arguments.optString("dependency", "").trim();
        if (dependencyName.isBlank()) {
            return "Missing required parameter: dependency.";
        }
        JSONObject dependency = data.findDependency(dependencyName);
        if (dependency == null) {
            return "Dependency not found in the current SBOM or dependency summary: " + dependencyName;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Dependency: ").append(dependency.optString("id", dependencyName));
        if (dependency.has("direct")) {
            if (dependency.optBoolean("direct", false)) {
                builder.append("\nRelationship: direct");
            } else {
                String introducedBy = dependency.optString("introducedBy", "").trim();
                if (introducedBy.isBlank()) {
                    builder.append("\nRelationship: transitive");
                } else {
                    builder.append("\nRelationship: transitive via ").append(introducedBy);
                }
            }
        }
        String licenses = formatLicenses(dependency.optJSONArray("licenses"));
        if (licenses.isBlank()) {
            builder.append("\nKnown licenses: unavailable");
        } else {
            builder.append("\nKnown licenses: ").append(licenses);
        }
        return builder.toString();
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
}

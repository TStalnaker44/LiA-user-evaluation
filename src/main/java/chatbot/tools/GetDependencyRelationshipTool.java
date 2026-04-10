package chatbot.tools;

import org.json.JSONArray;
import org.json.JSONObject;

public final class GetDependencyRelationshipTool implements ChatTool {
    private final ProjectToolData data;

    public GetDependencyRelationshipTool(ProjectToolData data) {
        this.data = data;
    }

    @Override
    public String getName() {
        return "get_dependency_relationship";
    }

    @Override
    public String getDescription() {
        return "Returns whether a specific dependency is direct or transitive, and identifies the direct dependency that introduces it when known.";
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

        String id = dependency.optString("id", dependencyName);
        if (!dependency.has("direct")) {
            return "Dependency " + id + " is present, but its direct/transitive relationship is currently unavailable.";
        }
        if (dependency.optBoolean("direct", false)) {
            return "Dependency " + id + " is a direct dependency.";
        }
        String introducedBy = dependency.optString("introducedBy", "").trim();
        if (introducedBy.isBlank()) {
            return "Dependency " + id + " is a transitive dependency.";
        }
        return "Dependency " + id + " is a transitive dependency introduced by " + introducedBy + ".";
    }
}

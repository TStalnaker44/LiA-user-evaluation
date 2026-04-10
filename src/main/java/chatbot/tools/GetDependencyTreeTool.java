package chatbot.tools;

import org.json.JSONObject;

public final class GetDependencyTreeTool implements ChatTool {
    private final ProjectToolData data;

    public GetDependencyTreeTool(ProjectToolData data) {
        this.data = data;
    }

    @Override
    public String getName() {
        return "get_dependency_tree";
    }

    @Override
    public String getDescription() {
        return "Returns the current project dependency tree, including direct and transitive dependencies when available.";
    }

    @Override
    public JSONObject getParametersSchema() {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject());
    }

    @Override
    public String execute(JSONObject arguments) {
        String tree = data.loadDependencyTree();
        if (tree == null || tree.isBlank()) {
            return "No dependency tree is available for the current project.";
        }
        return "Dependency tree currently detected:\n```text\n" + tree.trim() + "\n```";
    }
}

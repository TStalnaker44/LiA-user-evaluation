package chatbot.tools;

import com.intellij.openapi.project.Project;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ChatToolRegistry {
    private final Map<String, ChatTool> toolsByName = new LinkedHashMap<>();

    private ChatToolRegistry() {
    }

    public static ChatToolRegistry createDefault(Project project) {
        ChatToolRegistry registry = new ChatToolRegistry();
        ProjectToolData data = new ProjectToolData(project);
        registry.register(new GetDependencyLicenseTool(data));
        registry.register(new GetDependencyRelationshipTool(data));
        registry.register(new GetProjectLicenseInfoTool(data));
        registry.register(new GetDependencyListTool(data));
        registry.register(new GetDependencyTreeTool(data));
        return registry;
    }

    public void register(ChatTool tool) {
        toolsByName.put(tool.getName(), tool);
    }

    public boolean contains(String toolName) {
        return toolsByName.containsKey(toolName);
    }

    public String execute(String toolName, JSONObject arguments) throws Exception {
        ChatTool tool = toolsByName.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
        return tool.execute(arguments == null ? new JSONObject() : arguments);
    }

    public JSONArray toOpenAiToolSchema() {
        JSONArray tools = new JSONArray();
        for (ChatTool tool : toolsByName.values()) {
            JSONObject function = new JSONObject();
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());
            function.put("parameters", tool.getParametersSchema());
            tools.put(new JSONObject()
                    .put("type", "function")
                    .put("function", function));
        }
        return tools;
    }

    public Collection<ChatTool> getTools() {
        return toolsByName.values();
    }
}

package chatbot.tools;

import org.json.JSONObject;

public interface ChatTool {
    String getName();

    String getDescription();

    JSONObject getParametersSchema();

    String execute(JSONObject arguments) throws Exception;
}

package chatbot;


/**
 * Represents a "session" with the chatbot - created when the chatbot
 * is opened within the plugin.
 */
public abstract class ChatbotSession
{

    /**
     *
     * @param prompt The user's prompt to the model
     * @return The model's response to the submitted prompt
     * @throws Exception
     */
    public abstract String submitPrompt(String prompt) throws Exception;

    public abstract void clearHistory();

    public abstract void addToHistory(String role, String content);

}

package chatbot;

import io.github.ollama4j.OllamaAPI;
import io.github.ollama4j.models.chat.*;


/**
 * Stores session information for a chat session with Ollama.
 * Must have Llama 3.2 running at http://localhost:11434/ to be
 * able to connect.
 *
 * DEPRECATED - use HttpLlmChatSession instead
 */
@Deprecated
public class ChatbotSessionLlama extends ChatbotSession
{
    private static final String host = "http://localhost:11434/"; //This is the address to listen to for Ollama's responses.

    private OllamaAPI ollamaAPI;
    private OllamaChatRequestBuilder builder;
    private OllamaChatRequest requestModel;
    private OllamaChatResult chatResult;

    public ChatbotSessionLlama()
    {
        String model = "llama3.2:latest"; // OllamaModelType.LLAMA3;

        this.ollamaAPI = new OllamaAPI(host);
        this.builder = OllamaChatRequestBuilder.getInstance(model);
        this.chatResult = null;
    }

    /**
     *
     * @param prompt The user's prompt to the model
     * @return The model's response to the submitted prompt
     * @throws Exception
     */
    @Override
    public String submitPrompt(String prompt) throws Exception
    {
        if (this.chatResult != null) {
            // Send the new prompt along with the full chat history to the LLM
            this.requestModel = this.builder.withMessages(this.chatResult.getChatHistory())
                    .withMessage(OllamaChatMessageRole.USER, prompt).build();
        }
        else {
            // Send an initial message to the LLM
            this.requestModel = this.builder.withMessage(OllamaChatMessageRole.USER, prompt).build();
        }
        this.chatResult = this.ollamaAPI.chat(requestModel);
        String response = this.chatResult.getChatHistory().getLast().getContent();
        return response;
    }

    @Override
    public void clearHistory()
    {
        this.chatResult = null;
    }

    @Override
    public void addToHistory(String role, String content)
    {
        //obsolete
    }

}

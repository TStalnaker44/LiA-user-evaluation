package test;

import chatbot.ChatbotSession;
import chatbot.HttpLlmChatSession;

import java.util.Scanner;

/**
 * This is a standalone class that can be used for testing the chatbot.
 * It is now defunct with respect to the application - use ChatbotSession
 * to get/create chatbot sessions for the plugin.
 */
public class ChatbotSandbox {

    public static void main(String[] args) {

        ChatbotSession c = new HttpLlmChatSession("localhost", "gpt-4o-mini", "You are a friendly assistant");
        Scanner scanner = new Scanner(System.in);

        String input = "";
        while (!input.equals("exit")){
            System.out.print("User prompt: ");
            input = scanner.nextLine();
            if (input.equals("exit")){
                break;
            }

            try {
                String result = c.submitPrompt(input);
                System.out.println("Model response: " + result);
            }
            catch (Exception e) {
                System.out.println(e.getMessage());
            }

        }

        scanner.close();

    }
}

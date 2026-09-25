import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class TelegramTest {

    // Load credentials from config.properties or environment
    private static String BOT_TOKEN = "YOUR_BOT_TOKEN_HERE";
    private static String CHAT_ID = "YOUR_CHAT_ID_HERE";

    public static void main(String[] args) {
        String message = "Hello from Java! Your Bitcoin Tracker is starting up. 🚀";

        try {
            sendMessage(BOT_TOKEN, CHAT_ID, message);
        } catch (Exception e) {
            System.err.println("Error sending message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void sendMessage(String botToken, String chatId, String text) throws Exception {
        if (botToken.equals("YOUR_BOT_TOKEN_HERE") || chatId.equals("YOUR_CHAT_ID_HERE")) {
            System.out.println("⚠️ Please set your BOT_TOKEN and CHAT_ID in TelegramTest.java first!");
            return;
        }

        // Telegram Bot API URL format
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";

        // JSON payload: {"chat_id": "...", "text": "..."}
        String jsonPayload = String.format("{\"chat_id\": \"%s\", \"text\": \"%s\"}", chatId, text);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        System.out.println("Sending message to Telegram...");
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            System.out.println("✅ Success! Check your Telegram app on your phone/desktop!");
        } else {
            System.out.println("❌ Telegram API returned status code: " + response.statusCode());
            System.out.println("Response details: " + response.body());
        }
    }
}

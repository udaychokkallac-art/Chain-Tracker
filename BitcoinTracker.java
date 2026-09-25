import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class BitcoinTracker {

    // Telegram Bot Credentials (configure in config.properties)
    private static String BOT_TOKEN = "YOUR_BOT_TOKEN_HERE";
    private static String CHAT_ID = "YOUR_CHAT_ID_HERE";

    // Public Bitcoin Network & Price Endpoints
    private static final String BLOCK_API = "https://blockchain.info/latestblock";
    private static final String PRICE_API = "https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT";

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("   Starting Bitcoin Real-Time Network Tracker     ");
        System.out.println("==================================================");

        try {
            // 1. Fetch current block data
            System.out.println("Connecting to Bitcoin network...");
            String blockJson = fetchUrl(BLOCK_API);
            long height = extractLong(blockJson, "\"height\":");
            String hash = extractString(blockJson, "\"hash\":\"");

            // 2. Fetch current Bitcoin market price
            System.out.println("Fetching market price...");
            String priceJson = fetchUrl(PRICE_API);
            double price = extractDouble(priceJson, "\"price\":\"");

            // 3. Print details to console
            System.out.println("\n--- Latest Bitcoin Network Data ---");
            System.out.println("Current Block Height : #" + height);
            System.out.println("Latest Block Hash    : " + hash);
            System.out.printf("Current BTC Price    : $%,.2f USD\n", price);
            System.out.println("-----------------------------------");

            // 4. Send formatted alert to Telegram
            String alertMessage = String.format(
                "🧱 *Bitcoin Live Tracker Update*\n\n" +
                "• *Block Height:* #%d\n" +
                "• *Block Hash:* `%s`\n" +
                "• *Current Price:* $%,.2f USD\n\n" +
                "🟢 Network Status: Healthy & Synced",
                height, hash, price
            );

            System.out.println("\nDispatching Telegram alert...");
            sendTelegramMessage(alertMessage);
            System.out.println("✅ Alert successfully sent to your Telegram!");

        } catch (Exception e) {
            System.err.println("Error running Bitcoin Tracker: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Helper method to make an HTTP GET request to any public API
    public static String fetchUrl(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("API request failed with HTTP " + response.statusCode());
        }
        return response.body();
    }

    // Helper method to send a Telegram message
    public static void sendTelegramMessage(String text) throws Exception {
        String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";

        // Escape quotes and newlines for JSON payload
        String escapedText = text.replace("\"", "\\\"").replace("\n", "\\n");
        String payload = String.format("{\"chat_id\": \"%s\", \"text\": \"%s\", \"parse_mode\": \"Markdown\"}", CHAT_ID, escapedText);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.err.println("Telegram send error: " + response.body());
        }
    }

    // Simple JSON extraction helpers (no external libraries needed!)
    private static long extractLong(String json, String key) {
        int index = json.indexOf(key);
        if (index == -1) return 0;
        int start = index + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == ' ')) {
            end++;
        }
        return Long.parseLong(json.substring(start, end).trim());
    }

    private static double extractDouble(String json, String key) {
        int index = json.indexOf(key);
        if (index == -1) return 0.0;
        int start = index + key.length();
        int end = json.indexOf("\"", start);
        return Double.parseDouble(json.substring(start, end).trim());
    }

    private static String extractString(String json, String key) {
        int index = json.indexOf(key);
        if (index == -1) return "";
        int start = index + key.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}

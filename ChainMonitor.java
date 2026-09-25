import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ChainMonitor {

    // --- Configuration (configure in config.properties) ---
    private static String BOT_TOKEN = "YOUR_BOT_TOKEN_HERE";
    private static String CHAT_ID = "YOUR_CHAT_ID_HERE";

    private static final String BLOCK_API = "https://blockchain.info/latestblock";
    private static final String PRICE_API = "https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT";

    // Check interval in seconds (every 15 seconds)
    private static final int POLL_INTERVAL_SECONDS = 15;

    // Price change alert threshold ($500 movement)
    private static final double PRICE_ALERT_THRESHOLD = 500.0;

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // --- State Variables (Memory of the monitor) ---
    private static long lastBlockHeight = -1;
    private static String lastBlockHash = "";
    private static double lastAlertedPrice = 0.0;
    private static int consecutiveErrors = 0;

    public static void main(String[] args) {
        printBanner();

        // 1. Send startup alert to Telegram
        sendTelegramMessage(
            "🟢 *ChainTracker Service Online*\n\n" +
            "• Engine: Java 26 Runtime\n" +
            "• Polling Interval: Every " + POLL_INTERVAL_SECONDS + " seconds\n" +
            "• Tracking: Block Confirmations, Reorgs, & Price Volatility"
        );

        System.out.println("[" + getTimestamp() + "] Monitoring loop started. Press Ctrl+C in console to stop.\n");

        // 2. Continuous Monitoring Loop (Infinite while loop)
        while (true) {
            try {
                long startTime = System.currentTimeMillis();

                // Fetch latest blockchain & market data
                String blockJson = fetchUrl(BLOCK_API);
                String priceJson = fetchUrl(PRICE_API);

                long latency = System.currentTimeMillis() - startTime;

                long currentHeight = extractLong(blockJson, "\"height\":");
                String currentHash = extractString(blockJson, "\"hash\":\"");
                double currentPrice = extractDouble(priceJson, "\"price\":\"");
                int txCount = countTransactions(blockJson);

                // Reset error counter upon success
                consecutiveErrors = 0;

                // Log heartbeat to console
                System.out.printf("[%s] Network OK (%dms) | Height: #%d | Txs: %,d | BTC: $%,.2f\n",
                        getTimestamp(), latency, currentHeight, txCount, currentPrice);

                // Analyze state changes
                evaluateBlockchainState(currentHeight, currentHash, currentPrice, txCount);

            } catch (Exception e) {
                consecutiveErrors++;
                System.err.printf("[%s] ⚠️ Network Error (#%d): %s\n",
                        getTimestamp(), consecutiveErrors, e.getMessage());

                // If network fails 3 times in a row, send an alert!
                if (consecutiveErrors == 3) {
                    sendTelegramMessage(
                        "🚨 *Network Alert: Node Unreachable!*\n\n" +
                        "ChainTracker failed to connect to the Bitcoin network 3 times in a row.\n" +
                        "Last Error: `" + e.getMessage() + "`"
                    );
                }
            }

            // Sleep until next check
            try {
                Thread.sleep(POLL_INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException e) {
                System.out.println("Monitor interrupted. Shutting down...");
                break;
            }
        }
    }

    // --- State Evaluation & Alert Logic ---
    private static void evaluateBlockchainState(long height, String hash, double price, int txCount) {
        // Initial setup on first run
        if (lastBlockHeight == -1) {
            lastBlockHeight = height;
            lastBlockHash = hash;
            lastAlertedPrice = price;
            System.out.println("     Initial baseline recorded. Watching for new blocks...\n");
            return;
        }

        // 1. Check for NEW BLOCK mined
        if (height > lastBlockHeight) {
            long blocksAhead = height - lastBlockHeight;
            String message = String.format(
                "🧱 *NEW BITCOIN BLOCK MINED!*\n\n" +
                "• *Height:* #%d (+%d)\n" +
                "• *Transactions:* %,d\n" +
                "• *Block Hash:*\n`%s`\n" +
                "• *BTC Price:* $%,.2f\n\n" +
                "⚡ Confirmed on Global Ledger at %s",
                height, blocksAhead, txCount, hash, price, getTimestamp()
            );

            System.out.println("\n>>> [EVENT] New Block Mined! Dispatching Telegram notification...");
            sendTelegramMessage(message);
            System.out.println(">>> Alert sent successfully.\n");

            lastBlockHeight = height;
            lastBlockHash = hash;
        }
        // 2. Check for CHAIN REORGANIZATION (Reorg Alert!)
        else if (height == lastBlockHeight && !hash.equals(lastBlockHash) && !lastBlockHash.isEmpty()) {
            String reorgAlert = String.format(
                "⚠️ *CHAIN REORGANIZATION DETECTED!*\n\n" +
                "• Height: #%d\n" +
                "• Previous Hash: `%s`\n" +
                "• New Hash: `%s`\n\n" +
                "🚨 A competing block won the proof-of-work race. Chain tip rolled over!",
                height, lastBlockHash, hash
            );

            System.out.println("\n>>> [CRITICAL] Chain Reorganization detected!");
            sendTelegramMessage(reorgAlert);
            lastBlockHash = hash;
        }

        // 3. Check for Significant Price Movement (Volatility Alert)
        double priceDiff = price - lastAlertedPrice;
        if (Math.abs(priceDiff) >= PRICE_ALERT_THRESHOLD) {
            String direction = priceDiff > 0 ? "📈 SURGE" : "📉 DROP";
            String priceAlert = String.format(
                "%s *Bitcoin Price Alert!*\n\n" +
                "• Current Price: $%,.2f USD\n" +
                "• Movement: %s$%,.2f since last alert\n" +
                "• Time: %s",
                direction, price, (priceDiff > 0 ? "+" : ""), priceDiff, getTimestamp()
            );

            System.out.println("\n>>> [MARKET] Significant price movement detected!");
            sendTelegramMessage(priceAlert);
            lastAlertedPrice = price;
        }
    }

    // --- Helper Methods ---

    public static String fetchUrl(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    public static void sendTelegramMessage(String text) {
        try {
            String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
            String escapedText = text.replace("\"", "\\\"").replace("\n", "\\n");
            String payload = String.format("{\"chat_id\": \"%s\", \"text\": \"%s\", \"parse_mode\": \"Markdown\"}",
                    CHAT_ID, escapedText);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.err.println("Failed to send Telegram alert: " + e.getMessage());
        }
    }

    private static String getTimestamp() {
        return LocalDateTime.now().format(TIME_FORMAT);
    }

    private static void printBanner() {
        System.out.println("==========================================================");
        System.out.println("           ⚡ BITCOIN REAL-TIME CHAIN TRACKER ⚡          ");
        System.out.println("                 Engineered with Java 26                  ");
        System.out.println("==========================================================");
    }

    // Rough count of transactions in txIndexes array
    private static int countTransactions(String json) {
        int idx = json.indexOf("\"txIndexes\":");
        if (idx == -1) return 0;
        int count = 1;
        int start = json.indexOf("[", idx);
        int end = json.indexOf("]", start);
        if (start == -1 || end == -1) return 0;
        for (int i = start; i < end; i++) {
            if (json.charAt(i) == ',') count++;
        }
        return count;
    }

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

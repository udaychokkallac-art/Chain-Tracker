package com.chaintracker.client;

import com.chaintracker.model.BlockInfo;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous, non-blocking Telegram notification dispatcher using rich HTML card layouts.
 */
public class TelegramAlertClient {

    private final String botToken;
    private final String chatId;
    private final HttpClient httpClient;
    private final AtomicInteger alertCounter = new AtomicInteger(0);

    public TelegramAlertClient(String botToken, String chatId) {
        this.botToken = botToken != null ? botToken.trim() : "";
        this.chatId = chatId != null ? chatId.trim() : "";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    public boolean isConfigured() {
        return !botToken.isEmpty() && !chatId.isEmpty();
    }

    public int getTotalAlertsSent() {
        return alertCounter.get();
    }

    public void sendStartup(int port, int pollSec, boolean wsEnabled) {
        String msg = String.format(Locale.US,
                "🟢 <b>ChainTracker Core Active</b>\n\n" +
                "• <b>Runtime:</b> Java 26 Native Engine\n" +
                "• <b>Ingestion:</b> %s\n" +
                "• <b>Web Dashboard:</b> <code>http://localhost:%d</code>\n" +
                "• <b>Tracking:</b> Consensus Reorgs, Blocks & Price Volatility",
                (wsEnabled ? "Event-Driven WebSocket + Resilient REST Fallback" : "Heartbeat Polling"),
                port
        );
        sendHtmlMessage(msg);
    }

    public void sendNewBlock(BlockInfo block, long blocksAhead) {
        String aheadStr = blocksAhead > 1 ? String.format(" (+%d blocks)", blocksAhead) : "";
        String msg = String.format(Locale.US,
                "🧱 <b>NEW BITCOIN BLOCK CONFIRMED</b>\n\n" +
                "• <b>Height:</b> <code>#%,d</code>%s\n" +
                "• <b>Transactions:</b> <code>%,d txs</code>\n" +
                "• <b>BTC Price:</b> <code>$%,.2f USD</code>\n" +
                "• <b>Source:</b> <code>%s</code>\n" +
                "• <b>Timestamp:</b> <code>%s</code>\n\n" +
                "🔗 <b>Block Hash:</b>\n<code>%s</code>\n\n" +
                (!block.previousHash().isEmpty() ? "⏮ <b>Parent Hash:</b>\n<code>" + block.previousHash() + "</code>\n\n" : "") +
                "🔍 <a href=\"https://mempool.space/block/%s\">Inspect on Mempool.space</a>",
                block.height(), aheadStr, block.txCount(), block.price(), block.source(), block.time(),
                block.hash(), block.hash()
        );
        sendHtmlMessage(msg);
    }

    public void sendReorg(long height, String oldHash, String newHash) {
        String msg = String.format(Locale.US,
                "⚠️ <b>CONSENSUS REORGANIZATION DETECTED</b>\n\n" +
                "• <b>Block Height:</b> <code>#%,d</code>\n" +
                "• <b>Previous Tip:</b>\n<code>%s</code>\n" +
                "• <b>New Winner:</b>\n<code>%s</code>\n\n" +
                "🚨 <i>A competing branch won the Proof-of-Work race. Chain tip rolled over!</i>",
                height, oldHash, newHash
        );
        sendHtmlMessage(msg);
    }

    public void sendPriceAlert(double priceChange, double newPrice, double oldPrice) {
        String direction = newPrice >= oldPrice ? "📈 <b>BITCOIN SURGE ALERT</b>" : "📉 <b>BITCOIN DROP ALERT</b>";
        String msg = String.format(Locale.US,
                "%s\n\n" +
                "• <b>Price Change:</b> <code>%.2f%%</code>\n" +
                "• <b>Current Spot:</b> <code>$%,.2f USD</code>\n" +
                "• <b>Previous Spot:</b> <code>$%,.2f USD</code>",
                direction, priceChange, newPrice, oldPrice
        );
        sendHtmlMessage(msg);
    }

    public void sendNetworkError(String errorMessage, int retryCadenceSec) {
        String msg = String.format(Locale.US,
                "🚨 <b>NETWORK CONNECTIVITY WARNING</b>\n\n" +
                "ChainTracker failed to reach Bitcoin endpoints 3 times consecutively.\n\n" +
                "• <b>Status:</b> Interrupted\n" +
                "• <b>Error:</b> <code>%s</code>\n" +
                "• <b>Retry Cadence:</b> Every %ds",
                escapeHtml(errorMessage), retryCadenceSec
        );
        sendHtmlMessage(msg);
    }

    public void sendHtmlMessage(String html) {
        if (!isConfigured()) return;
        alertCounter.incrementAndGet();

        String urlString = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        try {
            String payload = "chat_id=" + URLEncoder.encode(chatId, StandardCharsets.UTF_8)
                    + "&text=" + URLEncoder.encode(html, StandardCharsets.UTF_8)
                    + "&parse_mode=HTML"
                    + "&disable_web_page_preview=true";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlString))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        System.err.println("[TELEGRAM] Dispatch failed: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[TELEGRAM] Error building payload: " + e.getMessage());
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

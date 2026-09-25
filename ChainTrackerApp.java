import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ChainTrackerApp {

    // Configuration
    private static String telegramBotToken = "";
    private static String telegramChatId = "";
    private static int serverPort = 8080;
    private static int pollIntervalSeconds = 10;
    private static double priceAlertThreshold = 5.0; // percentage
    private static String blockApiUrl = "https://blockchain.info/latestblock";
    private static String priceApiUrl = "https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT";
    
    // HTTP Client
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // State
    private static final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();
    
    private static long currentHeight = 0;
    private static String currentHash = "";
    private static double currentPrice = 0.0;
    private static long lastLatencyMs = 0;
    private static String serviceStatus = "STARTING";
    private static Instant startTime = Instant.now();
    
    private static final AtomicInteger totalBlocksSeen = new AtomicInteger(0);
    private static final AtomicInteger totalAlertsSent = new AtomicInteger(0);
    
    private static final Deque<BlockInfo> blockHistory = new LinkedList<>();
    private static final Deque<EventLog> eventLogs = new LinkedList<>();
    private static final int MAX_HISTORY = 20;
    private static final int MAX_EVENTS = 50;

    private static int consecutiveErrors = 0;

    // Formatting
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    static class BlockInfo {
        long height;
        String hash;
        long txCount;
        double price;
        String time;

        public BlockInfo(long height, String hash, long txCount, double price, String time) {
            this.height = height;
            this.hash = hash;
            this.txCount = txCount;
            this.price = price;
            this.time = time;
        }

        String toJson() {
            return String.format(Locale.US, "{\"height\":%d, \"hash\":\"%s\", \"txCount\":%d, \"price\":%.2f, \"time\":\"%s\"}",
                    height, hash, txCount, price, time);
        }
    }

    static class EventLog {
        String type;
        String message;
        String time;

        public EventLog(String type, String message, String time) {
            this.type = type;
            this.message = message;
            this.time = time;
        }

        String toJson() {
            return String.format("{\"type\":\"%s\", \"message\":\"%s\", \"time\":\"%s\"}",
                    escapeJson(type), escapeJson(message), escapeJson(time));
        }
    }

    public static void main(String[] args) throws IOException {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║       ⛓  CHAINTRACKER OBSERVABILITY ENGINE      ║");
        System.out.println("║         Real-Time Bitcoin Network Monitor        ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println();

        loadConfig();
        
        logEvent("STARTUP", "ChainTrackerApp starting up...");
        
        HttpServer server = HttpServer.create(new InetSocketAddress(serverPort), 0);
        server.createContext("/", new DashboardHandler());
        server.createContext("/api/telemetry", new TelemetryHandler());
        server.createContext("/api/events", new EventsHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        
        System.out.println(">>> 🌐 Dashboard live at: http://localhost:" + serverPort);
        System.out.println(">>> 📡 API endpoint:      http://localhost:" + serverPort + "/api/telemetry");
        System.out.println(">>> ⏱  Poll interval:     " + pollIntervalSeconds + "s");
        System.out.println(">>> Press Ctrl+C to stop.\n");
        
        sendTelegramAlert(String.format(
            "🟢 <b>ChainTracker Observability Online</b>\n\n" +
            "• <b>Runtime:</b> Java 26\n" +
            "• <b>Polling Interval:</b> Every %d seconds\n" +
            "• <b>Web Dashboard:</b> <code>http://localhost:%d</code>\n" +
            "• <b>Monitoring:</b> Block Confirmations, Reorgs, & Volatility",
            pollIntervalSeconds, serverPort
        ));
        
        Thread monitorThread = new Thread(ChainTrackerApp::monitorLoop, "ChainMonitorWorker");
        monitorThread.start();

        try {
            monitorThread.join();
        } catch (InterruptedException e) {
            System.out.println("Shutting down ChainTracker...");
        }
    }

    private static void loadConfig() {
        Properties props = new Properties();
        Path configPath = Paths.get("config.properties");
        if (Files.exists(configPath)) {
            try (InputStream is = Files.newInputStream(configPath)) {
                props.load(is);
                System.out.println("Loaded config.properties");
            } catch (IOException e) {
                System.err.println("Error reading config.properties: " + e.getMessage());
            }
        } else {
            System.out.println("config.properties not found, using defaults");
        }

        telegramBotToken = props.getProperty("telegram.bot.token", "");
        telegramChatId = props.getProperty("telegram.chat.id", "");
        serverPort = Integer.parseInt(props.getProperty("server.port", "8080"));
        pollIntervalSeconds = Integer.parseInt(props.getProperty("monitor.poll.interval.seconds", "15"));
        priceAlertThreshold = Double.parseDouble(props.getProperty("monitor.price.alert.threshold", "5.0"));
        blockApiUrl = props.getProperty("api.bitcoin.latestblock", blockApiUrl).trim();
        priceApiUrl = props.getProperty("api.bitcoin.price", priceApiUrl).trim();
    }

    private static void monitorLoop() {
        while (true) {
            try {
                long startReq = System.currentTimeMillis();
                
                String blockJson = fetchUrl(blockApiUrl);
                double newPrice = fetchBinancePrice();
                
                long endReq = System.currentTimeMillis();
                
                processData(blockJson, newPrice, endReq - startReq);
                
                consecutiveErrors = 0;
                
                stateLock.writeLock().lock();
                try {
                    serviceStatus = "HEALTHY";
                } finally {
                    stateLock.writeLock().unlock();
                }
                
            } catch (Exception e) {
                System.err.println("Error in monitor loop: " + e.getMessage());
                consecutiveErrors++;
                
                stateLock.writeLock().lock();
                try {
                    serviceStatus = consecutiveErrors >= 3 ? "ERROR" : "WARNING";
                } finally {
                    stateLock.writeLock().unlock();
                }

                if (consecutiveErrors == 3) {
                    String msg = String.format(
                        "🚨 <b>NETWORK CONNECTIVITY WARNING</b>\n\n" +
                        "ChainTracker failed to reach Bitcoin endpoints 3 times consecutively.\n\n" +
                        "• <b>Status:</b> Interrupted\n" +
                        "• <b>Error:</b> <code>%s</code>\n" +
                        "• <b>Retry Cadence:</b> Every %ds",
                        escapeHtml(e.getMessage() != null ? e.getMessage() : "Unknown error"),
                        pollIntervalSeconds
                    );
                    sendTelegramAlert(msg);
                    logEvent("NETWORK_ERROR", "Failed to fetch API data 3 times.");
                }
            }
            
            try {
                Thread.sleep(pollIntervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static String fetchUrl(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    private static double fetchBinancePrice() throws IOException, InterruptedException {
        String json = fetchUrl(priceApiUrl);
        String priceStr = extractJsonValue(json, "price");
        if (priceStr != null) {
            return Double.parseDouble(priceStr);
        }
        return currentPrice;
    }

    private static void processData(String blockJson, double newPrice, long latency) {
        String heightStr = extractJsonValue(blockJson, "height");
        String hash = extractJsonValue(blockJson, "hash");
        
        // Simple extraction for tx indexes size
        long txCount = 0;
        int txIndexStart = blockJson.indexOf("\"txIndexes\":[");
        if (txIndexStart != -1) {
            int txIndexEnd = blockJson.indexOf("]", txIndexStart);
            if (txIndexEnd != -1) {
                String txArray = blockJson.substring(txIndexStart + 13, txIndexEnd);
                if (!txArray.trim().isEmpty()) {
                    txCount = txArray.split(",").length;
                }
            }
        }

        if (heightStr == null || hash == null) return;
        long height = Long.parseLong(heightStr);
        
        stateLock.writeLock().lock();
        try {
            boolean priceAlertTriggered = false;
            
            if (currentPrice > 0) {
                double priceChange = Math.abs((newPrice - currentPrice) / currentPrice * 100.0);
                if (priceChange >= priceAlertThreshold) {
                    String direction = newPrice >= currentPrice ? "📈 <b>BITCOIN PRICE SURGE</b>" : "📉 <b>BITCOIN PRICE DROP</b>";
                    String msg = String.format(Locale.US,
                        "%s\n\n" +
                        "• <b>Movement:</b> <code>%.2f%%</code>\n" +
                        "• <b>Current Price:</b> <code>$%,.2f USD</code>\n" +
                        "• <b>Previous Price:</b> <code>$%,.2f USD</code>",
                        direction, priceChange, newPrice, currentPrice
                    );
                    sendTelegramAlert(msg);
                    logEvent("PRICE_ALERT", String.format(Locale.US, "Price changed by %.2f%% ($%,.2f)", priceChange, newPrice));
                    priceAlertTriggered = true;
                }
            }
            
            currentPrice = newPrice;
            lastLatencyMs = latency;
            
            if (currentHeight > 0 && height == currentHeight && !hash.equals(currentHash)) {
                String msg = String.format(Locale.US,
                    "⚠️ <b>CHAIN REORGANIZATION DETECTED</b>\n\n" +
                    "• <b>Block Height:</b> <code>#%,d</code>\n" +
                    "• <b>Previous Tip:</b>\n<code>%s</code>\n" +
                    "• <b>Winning Branch:</b>\n<code>%s</code>\n\n" +
                    "🚨 <i>A competing block won the Proof-of-Work race. Chain tip rolled over!</i>",
                    height, currentHash, hash
                );
                sendTelegramAlert(msg);
                logEvent("REORG", "Reorg at #" + height + ": " + truncateHash(currentHash) + " -> " + truncateHash(hash));
                currentHash = hash;
                
            } else if (height > currentHeight) {
                String timeStr = timeFormatter.format(Instant.now());
                BlockInfo blockInfo = new BlockInfo(height, hash, txCount, currentPrice, timeStr);
                
                blockHistory.addFirst(blockInfo);
                if (blockHistory.size() > MAX_HISTORY) {
                    blockHistory.removeLast();
                }
                
                totalBlocksSeen.incrementAndGet();
                
                if (currentHeight > 0) {
                    long blocksAhead = height - currentHeight;
                    String blockAheadStr = blocksAhead > 1 ? " (+" + blocksAhead + " blocks)" : "";
                    String formattedMsg = String.format(Locale.US,
                        "🧱 <b>NEW BITCOIN BLOCK CONFIRMED</b>\n\n" +
                        "• <b>Height:</b> <code>#%,d</code>%s\n" +
                        "• <b>Transactions:</b> <code>%,d txs</code>\n" +
                        "• <b>BTC Price:</b> <code>$%,.2f USD</code>\n" +
                        "• <b>Timestamp:</b> <code>%s</code>\n\n" +
                        "🔗 <b>Block Hash:</b>\n<code>%s</code>\n\n" +
                        "🔍 <a href=\"https://mempool.space/block/%s\">View on Mempool.space</a>",
                        height, blockAheadStr, txCount, currentPrice, timeStr, hash, hash
                    );
                    sendTelegramAlert(formattedMsg);
                    logEvent("NEW_BLOCK", String.format(Locale.US, "New block #%,d mined (%,d txs)", height, txCount));
                }
                
                currentHeight = height;
                currentHash = hash;
            }
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private static String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int idx = json.indexOf(searchKey);
        if (idx == -1) return null;
        
        int start = idx + searchKey.length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\n')) {
            start++;
        }
        
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        } else {
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ' ' && json.charAt(end) != '\n') {
                end++;
            }
            return json.substring(start, end);
        }
    }

    private static void logEvent(String type, String message) {
        String timeStr = timeFormatter.format(Instant.now());
        EventLog event = new EventLog(type, message, timeStr);
        
        stateLock.writeLock().lock();
        try {
            eventLogs.addFirst(event);
            if (eventLogs.size() > MAX_EVENTS) {
                eventLogs.removeLast();
            }
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private static void sendTelegramAlert(String htmlMessage) {
        totalAlertsSent.incrementAndGet();
        if (telegramBotToken.isEmpty() || telegramChatId.isEmpty()) {
            return;
        }
        
        String urlString = "https://api.telegram.org/bot" + telegramBotToken + "/sendMessage";
        try {
            String payload = "chat_id=" + URLEncoder.encode(telegramChatId, StandardCharsets.UTF_8)
                    + "&text=" + URLEncoder.encode(htmlMessage, StandardCharsets.UTF_8)
                    + "&parse_mode=HTML"
                    + "&disable_web_page_preview=true";
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlString))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("Failed to send telegram alert: " + e.getMessage());
        }
    }

    private static void sendTelegramAlert(String type, String message) {
        sendTelegramAlert("<b>[" + escapeHtml(type) + "]</b> " + escapeHtml(message));
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String truncateHash(String hash) {
        if (hash == null || hash.length() < 16) return hash == null ? "" : hash;
        return hash.substring(0, 10) + "..." + hash.substring(hash.length() - 8);
    }

    private static String getUptimeStr() {
        Duration d = Duration.between(startTime, Instant.now());
        long h = d.toHours();
        long m = d.toMinutesPart();
        return h + "h " + m + "m";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    static class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ChainTracker // Bitcoin Observability</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg: #000000;
            --surface: #070708;
            --surface-elevated: #0f0f12;
            --border: rgba(255, 255, 255, 0.08);
            --border-hover: rgba(255, 255, 255, 0.2);
            --border-active: rgba(255, 255, 255, 0.35);
            --text-primary: #ffffff;
            --text-secondary: #a1a1aa;
            --text-tertiary: #52525b;
            --accent-amber: #f59e0b;
            --accent-gold: #fbbf24;
            --accent-green: #22c55e;
            --accent-red: #ef4444;
            --mono: 'JetBrains Mono', monospace;
            --sans: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
            --transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
        }

        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
        }

        body {
            font-family: var(--sans);
            background-color: var(--bg);
            color: var(--text-primary);
            line-height: 1.5;
            -webkit-font-smoothing: antialiased;
            -moz-osx-font-smoothing: grayscale;
            overflow-x: hidden;
            position: relative;
        }

        /* Opal Film Grain / Noise Overlay */
        .noise-overlay {
            position: fixed;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            pointer-events: none;
            z-index: 999;
            opacity: 0.035;
            background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='noiseFilter'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.8' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23noiseFilter)'/%3E%3C/svg%3E");
        }

        /* Subtle Ambient Spotlight Glow */
        .ambient-spotlight {
            position: fixed;
            top: -20%;
            left: 50%;
            transform: translateX(-50%);
            width: 1000px;
            height: 600px;
            background: radial-gradient(ellipse at center, rgba(255, 255, 255, 0.05) 0%, transparent 70%);
            pointer-events: none;
            z-index: 1;
        }

        /* Opal-Style Minimalist Sticky Header */
        header {
            position: sticky;
            top: 0;
            z-index: 50;
            background: rgba(0, 0, 0, 0.75);
            backdrop-filter: blur(20px);
            -webkit-backdrop-filter: blur(20px);
            border-bottom: 1px solid var(--border);
            padding: 16px 36px;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }

        .brand-area {
            display: flex;
            align-items: center;
            gap: 16px;
        }

        .brand-logo {
            display: flex;
            align-items: center;
            gap: 10px;
            text-decoration: none;
            color: var(--text-primary);
        }

        .brand-symbol {
            width: 20px;
            height: 20px;
            border-radius: 50%;
            border: 2px solid var(--text-primary);
            position: relative;
            display: flex;
            align-items: center;
            justify-content: center;
        }

        .brand-symbol::after {
            content: '';
            width: 6px;
            height: 6px;
            background-color: var(--text-primary);
            border-radius: 50%;
        }

        .brand-title {
            font-size: 13px;
            font-weight: 700;
            letter-spacing: 0.12em;
            text-transform: uppercase;
        }

        .brand-tag {
            font-family: var(--mono);
            font-size: 11px;
            color: var(--text-tertiary);
            letter-spacing: 0.05em;
            border-left: 1px solid var(--border);
            padding-left: 16px;
        }

        .header-status {
            display: flex;
            align-items: center;
            gap: 20px;
        }

        .utc-clock {
            font-family: var(--mono);
            font-size: 11px;
            color: var(--text-secondary);
            letter-spacing: 0.08em;
        }

        .status-badge {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 5px 12px;
            border-radius: 9999px;
            font-size: 10px;
            font-weight: 600;
            letter-spacing: 0.08em;
            text-transform: uppercase;
            background: rgba(34, 197, 94, 0.08);
            color: var(--accent-green);
            border: 1px solid rgba(34, 197, 94, 0.25);
            transition: var(--transition);
        }

        .status-dot {
            width: 6px;
            height: 6px;
            border-radius: 50%;
            background-color: var(--accent-green);
            box-shadow: 0 0 8px currentColor;
            animation: pulse 2.5s infinite;
        }

        @keyframes pulse {
            0%, 100% { opacity: 1; transform: scale(1); }
            50% { opacity: 0.35; transform: scale(1.15); }
        }

        /* Main Container */
        main {
            position: relative;
            z-index: 10;
            max-width: 1280px;
            margin: 0 auto;
            padding: 60px 36px 120px;
            display: flex;
            flex-direction: column;
            gap: 60px;
        }

        /* Section Eyebrow / Technical Tag */
        .section-tag {
            font-family: var(--mono);
            font-size: 11px;
            font-weight: 500;
            letter-spacing: 0.15em;
            text-transform: uppercase;
            color: var(--text-tertiary);
            margin-bottom: 24px;
            display: flex;
            align-items: center;
            gap: 12px;
        }

        .section-tag::after {
            content: '';
            flex: 1;
            height: 1px;
            background: var(--border);
        }

        /* Hero: The Ledger Presentation */
        .hero-section {
            display: grid;
            grid-template-columns: 1.5fr 1fr;
            gap: 40px;
            align-items: flex-end;
            padding-bottom: 40px;
            border-bottom: 1px solid var(--border);
        }

        .hero-headline {
            display: flex;
            flex-direction: column;
            gap: 12px;
        }

        .hero-title-small {
            font-size: 14px;
            font-weight: 500;
            letter-spacing: 0.05em;
            color: var(--text-secondary);
            text-transform: lowercase;
        }

        .hero-number {
            font-size: clamp(3.8rem, 8vw, 6.5rem);
            font-weight: 700;
            letter-spacing: -0.05em;
            line-height: 0.95;
            color: var(--text-primary);
            transition: var(--transition);
        }

        .hero-number.flash {
            color: var(--accent-gold);
            text-shadow: 0 0 30px rgba(251, 191, 36, 0.4);
        }

        .hero-sub {
            font-family: var(--mono);
            font-size: 12px;
            letter-spacing: 0.08em;
            color: var(--text-tertiary);
            text-transform: uppercase;
            margin-top: 8px;
        }

        /* Hero Secondary Metrics */
        .hero-metrics {
            display: flex;
            flex-direction: column;
            gap: 24px;
        }

        .metric-item {
            padding: 18px 24px;
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 8px;
            transition: var(--transition);
        }

        .metric-item:hover {
            border-color: var(--border-hover);
            background: var(--surface-elevated);
        }

        .metric-label {
            font-family: var(--mono);
            font-size: 10px;
            font-weight: 500;
            text-transform: uppercase;
            letter-spacing: 0.12em;
            color: var(--text-tertiary);
            margin-bottom: 6px;
        }

        .metric-value-row {
            display: flex;
            justify-content: space-between;
            align-items: baseline;
        }

        .metric-val {
            font-size: 26px;
            font-weight: 600;
            letter-spacing: -0.03em;
            color: var(--text-primary);
        }

        .metric-val.amber {
            color: var(--accent-amber);
        }

        .metric-meta {
            font-family: var(--mono);
            font-size: 11px;
            color: var(--text-secondary);
        }

        /* Opal Interactive Waveform Canvas */
        .canvas-card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 8px;
            padding: 24px;
            position: relative;
            overflow: hidden;
            transition: var(--transition);
        }

        .canvas-card:hover {
            border-color: var(--border-hover);
        }

        .canvas-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 16px;
        }

        .canvas-title {
            font-family: var(--mono);
            font-size: 11px;
            letter-spacing: 0.1em;
            text-transform: uppercase;
            color: var(--text-secondary);
        }

        .canvas-hint {
            font-family: var(--mono);
            font-size: 10px;
            letter-spacing: 0.08em;
            color: var(--text-tertiary);
            text-transform: uppercase;
        }

        .telemetry-canvas {
            display: block;
            width: 100%;
            height: 120px;
            cursor: crosshair;
        }

        /* Consensus Block Hash & Engineering Stats */
        .precision-grid {
            display: grid;
            grid-template-columns: 2fr 1fr;
            gap: 24px;
        }

        .hardware-card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 8px;
            padding: 28px;
            display: flex;
            flex-direction: column;
            justify-content: space-between;
            transition: var(--transition);
        }

        .hardware-card:hover {
            border-color: var(--border-hover);
        }

        .card-header-bar {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 20px;
        }

        .card-caption {
            font-family: var(--mono);
            font-size: 11px;
            letter-spacing: 0.12em;
            text-transform: uppercase;
            color: var(--text-tertiary);
        }

        .copy-button {
            background: var(--text-primary);
            color: var(--bg);
            border: 1px solid var(--text-primary);
            font-family: var(--sans);
            font-size: 10px;
            font-weight: 700;
            letter-spacing: 0.08em;
            text-transform: uppercase;
            padding: 6px 14px;
            border-radius: 4px;
            cursor: pointer;
            transition: var(--transition);
            display: inline-flex;
            align-items: center;
            gap: 6px;
        }

        .copy-button:hover {
            background: transparent;
            color: var(--text-primary);
        }

        .hash-engraving {
            font-family: var(--mono);
            font-size: 14px;
            line-height: 1.7;
            color: var(--text-primary);
            word-break: break-all;
            background: rgba(255, 255, 255, 0.02);
            border: 1px solid rgba(255, 255, 255, 0.04);
            border-radius: 6px;
            padding: 18px 20px;
            letter-spacing: 0.04em;
        }

        .stat-list {
            display: flex;
            flex-direction: column;
            gap: 16px;
        }

        .stat-row {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding-bottom: 12px;
            border-bottom: 1px solid rgba(255, 255, 255, 0.05);
            font-size: 13px;
        }

        .stat-row:last-child {
            border-bottom: none;
            padding-bottom: 0;
        }

        .stat-key {
            font-family: var(--mono);
            font-size: 11px;
            color: var(--text-secondary);
            text-transform: uppercase;
            letter-spacing: 0.06em;
        }

        .stat-val {
            font-family: var(--mono);
            font-size: 13px;
            font-weight: 600;
            color: var(--text-primary);
        }

        /* Stream Table */
        .table-wrap {
            width: 100%;
            overflow-x: auto;
            border: 1px solid var(--border);
            border-radius: 8px;
            background: var(--surface);
        }

        table {
            width: 100%;
            border-collapse: collapse;
            text-align: left;
        }

        th {
            font-family: var(--mono);
            font-size: 10px;
            font-weight: 500;
            text-transform: uppercase;
            letter-spacing: 0.12em;
            color: var(--text-tertiary);
            padding: 16px 24px;
            border-bottom: 1px solid var(--border);
            background: rgba(255, 255, 255, 0.015);
        }

        td {
            padding: 18px 24px;
            font-size: 13px;
            border-bottom: 1px solid rgba(255, 255, 255, 0.04);
            color: var(--text-secondary);
            transition: var(--transition);
        }

        tbody tr:last-child td {
            border-bottom: none;
        }

        tbody tr:hover td {
            background: rgba(255, 255, 255, 0.03);
            color: var(--text-primary);
        }

        td.highlight {
            font-weight: 600;
            color: var(--text-primary);
        }

        td.mono {
            font-family: var(--mono);
            font-size: 12px;
        }

        /* Event Ledger Timeline */
        .event-ledger {
            display: flex;
            flex-direction: column;
            gap: 10px;
        }

        .event-row {
            display: flex;
            align-items: center;
            gap: 18px;
            padding: 14px 20px;
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 6px;
            font-size: 13px;
            transition: var(--transition);
        }

        .event-row:hover {
            border-color: var(--border-hover);
            background: var(--surface-elevated);
        }

        .event-index {
            font-family: var(--mono);
            font-size: 10px;
            color: var(--text-tertiary);
            min-width: 32px;
        }

        .event-time-tag {
            font-family: var(--mono);
            font-size: 11px;
            color: var(--text-secondary);
            min-width: 70px;
        }

        .event-badge {
            font-family: var(--mono);
            font-size: 9px;
            font-weight: 700;
            letter-spacing: 0.1em;
            text-transform: uppercase;
            padding: 3px 8px;
            border-radius: 3px;
            border: 1px solid var(--border);
            color: var(--text-secondary);
            background: rgba(255, 255, 255, 0.03);
            white-space: nowrap;
        }

        .event-badge.new_block {
            color: var(--accent-green);
            border-color: rgba(34, 197, 94, 0.3);
            background: rgba(34, 197, 94, 0.06);
        }

        .event-badge.reorg {
            color: var(--accent-red);
            border-color: rgba(239, 68, 68, 0.3);
            background: rgba(239, 68, 68, 0.06);
        }

        .event-badge.price_alert {
            color: var(--accent-amber);
            border-color: rgba(245, 158, 11, 0.3);
            background: rgba(245, 158, 11, 0.06);
        }

        .event-desc {
            color: var(--text-primary);
            flex: 1;
        }

        /* Opal-Style Editorial Footer */
        footer {
            border-top: 1px solid var(--border);
            padding: 80px 36px 40px;
            max-width: 1280px;
            margin: 0 auto;
            display: flex;
            flex-direction: column;
            gap: 40px;
            color: var(--text-tertiary);
            font-size: 12px;
        }

        .footer-top {
            display: flex;
            justify-content: space-between;
            align-items: flex-start;
            flex-wrap: wrap;
            gap: 30px;
        }

        .footer-col-title {
            font-family: var(--mono);
            font-size: 10px;
            font-weight: 600;
            letter-spacing: 0.12em;
            text-transform: uppercase;
            color: var(--text-secondary);
            margin-bottom: 12px;
        }

        .footer-links {
            display: flex;
            gap: 28px;
        }

        .footer-links a {
            color: var(--text-secondary);
            text-decoration: none;
            font-size: 12px;
            letter-spacing: 0.04em;
            transition: var(--transition);
        }

        .footer-links a:hover {
            color: var(--text-primary);
        }

        .footer-bottom {
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-top: 1px solid rgba(255, 255, 255, 0.04);
            padding-top: 24px;
            font-family: var(--mono);
            font-size: 10px;
            letter-spacing: 0.08em;
            text-transform: uppercase;
        }

        /* Shimmer Skeletons */
        .skeleton {
            background: linear-gradient(90deg, rgba(255,255,255,0.02) 25%, rgba(255,255,255,0.06) 50%, rgba(255,255,255,0.02) 75%);
            background-size: 200% 100%;
            animation: shimmer 2s infinite;
            border-radius: 4px;
            color: transparent !important;
        }

        @keyframes shimmer {
            0% { background-position: 200% 0; }
            100% { background-position: -200% 0; }
        }

        /* Responsive Breakpoints */
        @media (max-width: 900px) {
            .hero-section {
                grid-template-columns: 1fr;
            }
            .precision-grid {
                grid-template-columns: 1fr;
            }
            header {
                padding: 16px 20px;
            }
            main {
                padding: 40px 20px 80px;
            }
            footer {
                padding: 60px 20px 30px;
            }
        }
    </style>
</head>
<body>
    <div class="noise-overlay" aria-hidden="true"></div>
    <div class="ambient-spotlight" aria-hidden="true"></div>

    <header>
        <div class="brand-area">
            <a href="/" class="brand-logo" aria-label="ChainTracker Home">
                <div class="brand-symbol"></div>
                <span class="brand-title">ChainTracker</span>
            </a>
            <span class="brand-tag">ELECTRONICS &bull; OBSERVABILITY ENGINE</span>
        </div>
        <div class="header-status">
            <span class="utc-clock" id="utcClock">--:--:-- UTC</span>
            <div class="status-badge" id="statusBadge" aria-label="System Consensus Status">
                <span class="status-dot" id="statusDot"></span>
                <span id="statusText">CONNECTING</span>
            </div>
        </div>
    </header>

    <main>
        <!-- 01: The Ledger Hero -->
        <section aria-label="Hero Metric">
            <div class="section-tag">01 // NETWORK CONSENSUS &bull; BLOCK TIP</div>
            <div class="hero-section">
                <div class="hero-headline">
                    <span class="hero-title-small">the ledger</span>
                    <div class="hero-number" id="valHeight"><span class="skeleton">#968,551</span></div>
                    <div class="hero-sub" id="subHeight">SYNCHRONIZED WITH BITCOIN PEER NETWORK</div>
                </div>
                <div class="hero-metrics">
                    <div class="metric-item">
                        <div class="metric-label">BITCOIN SPOT RATE // USDT</div>
                        <div class="metric-value-row">
                            <span class="metric-val amber" id="valPrice"><span class="skeleton">$84,000.00</span></span>
                            <span class="metric-meta">BINANCE SPOT FEED</span>
                        </div>
                    </div>
                    <div class="metric-item">
                        <div class="metric-label">NETWORK ROUND-TRIP LATENCY</div>
                        <div class="metric-value-row">
                            <span class="metric-val" id="valLatency"><span class="skeleton">480ms</span></span>
                            <span class="metric-meta" id="latencyMeta">PEER RESPONSE TIME</span>
                        </div>
                    </div>
                </div>
            </div>
        </section>

        <!-- Interactive Telemetry Waveform (Opal Canvas Tribute) -->
        <section aria-label="Telemetry Waveform">
            <div class="section-tag">02 // PEER TELEMETRY PULSE &bull; REAL-TIME OSCILLATOR</div>
            <div class="canvas-card">
                <div class="canvas-header">
                    <span class="canvas-title">HEARTBEAT FREQUENCY RESPONSE</span>
                    <span class="canvas-hint">[ INTERACTIVE &bull; HOVER TO MODULATE ]</span>
                </div>
                <canvas id="telemetryCanvas" class="telemetry-canvas"></canvas>
            </div>
        </section>

        <!-- 03: Consensus Block Hash & Engineering Readouts -->
        <section aria-label="Block Hash and Telemetry">
            <div class="section-tag">03 // PROOF OF WORK &bull; TIP SPECIFICATION</div>
            <div class="precision-grid">
                <div class="hardware-card">
                    <div class="card-header-bar">
                        <span class="card-caption">LATEST BLOCK SHA-256 HASH</span>
                        <button id="copyHashBtn" onclick="copyHash()" class="copy-button" title="Copy block hash">
                            <span id="copyText">COPY HASH</span>
                        </button>
                    </div>
                    <div class="hash-engraving" id="valHash"><span class="skeleton">00000000000000000001f3d45b0be8ebe4d8a1f76102419b1630b717d3861d45</span></div>
                </div>
                <div class="hardware-card">
                    <div class="card-header-bar">
                        <span class="card-caption">ENGINE METRICS</span>
                    </div>
                    <div class="stat-list">
                        <div class="stat-row">
                            <span class="stat-key">SERVICE UPTIME</span>
                            <span class="stat-val" id="valUptime"><span class="skeleton">0h 0m</span></span>
                        </div>
                        <div class="stat-row">
                            <span class="stat-key">BLOCKS OBSERVED</span>
                            <span class="stat-val" id="valBlocks"><span class="skeleton">0</span></span>
                        </div>
                        <div class="stat-row">
                            <span class="stat-key">TELEGRAM ALERTS</span>
                            <span class="stat-val" id="valAlerts"><span class="skeleton">0</span></span>
                        </div>
                    </div>
                </div>
            </div>
        </section>

        <!-- 04: Recent Blocks Stream -->
        <section aria-label="Recent Blocks">
            <div class="section-tag">04 // DISCOVERED BLOCKS STREAM</div>
            <div class="table-wrap">
                <table>
                    <thead>
                        <tr>
                            <th>HEIGHT</th>
                            <th>BLOCK HASH</th>
                            <th>TRANSACTIONS</th>
                            <th>PRICE AT TIP</th>
                            <th>TIMESTAMP</th>
                        </tr>
                    </thead>
                    <tbody id="blocksTableBody">
                        <tr><td><span class="skeleton">#968551</span></td><td><span class="skeleton">0000...0000</span></td><td><span class="skeleton">4,047</span></td><td><span class="skeleton">$84,000.00</span></td><td><span class="skeleton">19:28:45</span></td></tr>
                        <tr><td><span class="skeleton">#968550</span></td><td><span class="skeleton">0000...0000</span></td><td><span class="skeleton">3,892</span></td><td><span class="skeleton">$84,000.00</span></td><td><span class="skeleton">19:15:10</span></td></tr>
                    </tbody>
                </table>
            </div>
        </section>

        <!-- 05: Event Log -->
        <section aria-label="Flight Recorder">
            <div class="section-tag">05 // FLIGHT RECORDER &bull; TELEMETRY LOGS</div>
            <div class="event-ledger" id="eventList">
                <div class="event-row"><span class="event-index">01</span><span class="event-time-tag"><span class="skeleton">19:28:45</span></span><span class="event-badge">STARTUP</span><span class="event-desc"><span class="skeleton">Loading telemetry stream...</span></span></div>
            </div>
        </section>
    </main>

    <footer>
        <div class="footer-top">
            <div>
                <div class="footer-col-title">CHAINTRACKER ELECTRONICS INC.</div>
                <p>Original software engine for Bitcoin network observability and consensus verification.</p>
            </div>
            <nav class="footer-links" aria-label="Footer Links">
                <a href="/api/telemetry" target="_blank">/api/telemetry</a>
                <a href="/api/events" target="_blank">/api/events</a>
                <a href="https://blockchain.info" target="_blank" rel="noopener">Upstream Peer</a>
                <a href="https://github.com" target="_blank" rel="noopener">Source Code</a>
            </nav>
        </div>
        <div class="footer-bottom">
            <span>CORE JAVA 26 &bull; COM.SUN.NET.HTTPSERVER &bull; ZERO THIRD-PARTY RUNTIME DEPENDENCIES</span>
            <span>DESIGN INSPIRED BY OPAL ELECTRONICS (OP.AL)</span>
        </div>
    </footer>

    <script>
        let previousHeight = 0;
        let isFirstLoad = true;

        // Clock display
        function updateClock() {
            const now = new Date();
            const h = String(now.getUTCHours()).padStart(2, '0');
            const m = String(now.getUTCMinutes()).padStart(2, '0');
            const s = String(now.getUTCSeconds()).padStart(2, '0');
            const clockEl = document.getElementById('utcClock');
            if (clockEl) clockEl.textContent = `${h}:${m}:${s} UTC`;
        }
        setInterval(updateClock, 1000);
        updateClock();

        // Copy Hash
        function copyHash() {
            const hash = document.getElementById('valHash').textContent.trim();
            if (!hash || hash.includes('00000000000000000000000000000000') || hash.includes('skeleton')) return;
            navigator.clipboard.writeText(hash).then(() => {
                const btnText = document.getElementById('copyText');
                btnText.textContent = 'COPIED';
                setTimeout(() => { btnText.textContent = 'COPY HASH'; }, 1800);
            }).catch(() => {});
        }

        function truncateHash(hash) {
            if (!hash || hash.length < 24) return hash;
            return hash.substring(0, 14) + '...' + hash.substring(hash.length - 10);
        }

        function updateStatusBadge(status) {
            const badge = document.getElementById('statusBadge');
            const dot = document.getElementById('statusDot');
            const text = document.getElementById('statusText');
            if (!badge || !dot || !text) return;

            text.textContent = status;
            if (status === 'HEALTHY') {
                badge.style.background = 'rgba(34, 197, 94, 0.08)';
                badge.style.color = 'var(--accent-green)';
                badge.style.borderColor = 'rgba(34, 197, 94, 0.25)';
                dot.style.backgroundColor = 'var(--accent-green)';
            } else if (status === 'WARNING') {
                badge.style.background = 'rgba(245, 158, 11, 0.08)';
                badge.style.color = 'var(--accent-amber)';
                badge.style.borderColor = 'rgba(245, 158, 11, 0.25)';
                dot.style.backgroundColor = 'var(--accent-amber)';
            } else {
                badge.style.background = 'rgba(239, 68, 68, 0.08)';
                badge.style.color = 'var(--accent-red)';
                badge.style.borderColor = 'rgba(239, 68, 68, 0.25)';
                dot.style.backgroundColor = 'var(--accent-red)';
            }
        }

        // Opal-Style Canvas Waveform
        const canvas = document.getElementById('telemetryCanvas');
        const ctx = canvas.getContext('2d');
        let mouseX = 0.5, mouseY = 0.5, isHovering = false;

        function resizeCanvas() {
            canvas.width = canvas.offsetWidth * window.devicePixelRatio;
            canvas.height = canvas.offsetHeight * window.devicePixelRatio;
            ctx.scale(window.devicePixelRatio, window.devicePixelRatio);
        }
        window.addEventListener('resize', resizeCanvas);
        resizeCanvas();

        canvas.addEventListener('mousemove', (e) => {
            const rect = canvas.getBoundingClientRect();
            mouseX = (e.clientX - rect.left) / rect.width;
            mouseY = (e.clientY - rect.top) / rect.height;
            isHovering = true;
        });
        canvas.addEventListener('mouseleave', () => { isHovering = false; });

        let frame = 0;
        function renderWave() {
            const w = canvas.offsetWidth;
            const h = canvas.offsetHeight;
            ctx.clearRect(0, 0, w, h);

            // Grid lines
            ctx.strokeStyle = 'rgba(255, 255, 255, 0.03)';
            ctx.lineWidth = 1;
            for (let x = 0; x < w; x += 40) {
                ctx.beginPath();
                ctx.moveTo(x, 0);
                ctx.lineTo(x, h);
                ctx.stroke();
            }

            // Draw technical waveform
            ctx.beginPath();
            ctx.strokeStyle = 'rgba(255, 255, 255, 0.7)';
            ctx.lineWidth = 1.5;

            const baseFreq = 0.015 + (isHovering ? (mouseX - 0.5) * 0.01 : 0);
            const amplitude = (h * 0.25) * (isHovering ? 0.7 + (1 - mouseY) * 0.6 : 0.8);
            const centerY = h / 2;

            for (let x = 0; x < w; x++) {
                const y = centerY + Math.sin(x * baseFreq + frame * 0.03) * amplitude * Math.cos(x * 0.003);
                if (x === 0) ctx.moveTo(x, y);
                else ctx.lineTo(x, y);
            }
            ctx.stroke();

            // Glow line underneath
            ctx.beginPath();
            ctx.strokeStyle = 'rgba(245, 158, 11, 0.25)';
            ctx.lineWidth = 3;
            for (let x = 0; x < w; x++) {
                const y = centerY + Math.sin(x * (baseFreq * 0.9) + frame * 0.02) * (amplitude * 0.6);
                if (x === 0) ctx.moveTo(x, y);
                else ctx.lineTo(x, y);
            }
            ctx.stroke();

            frame++;
            requestAnimationFrame(renderWave);
        }
        renderWave();

        // Main telemetry updater
        async function updateDashboard() {
            try {
                const res = await fetch('/api/telemetry');
                if (!res.ok) throw new Error('Network error');
                const data = await res.json();

                if (isFirstLoad) {
                    document.querySelectorAll('.skeleton').forEach(el => el.classList.remove('skeleton'));
                    isFirstLoad = false;
                }

                // Update Height
                const heightEl = document.getElementById('valHeight');
                if (data.height > previousHeight && previousHeight > 0) {
                    heightEl.classList.add('flash');
                    setTimeout(() => heightEl.classList.remove('flash'), 1200);
                    const diff = data.height - previousHeight;
                    document.getElementById('subHeight').textContent = `SYNCHRONIZED // +${diff} NEW BLOCK CONFIRMED`;
                }
                if (data.height > 0) {
                    heightEl.textContent = '#' + data.height.toLocaleString();
                    previousHeight = data.height;
                }

                // Update Price
                if (data.price > 0) {
                    document.getElementById('valPrice').textContent = '$' + data.price.toLocaleString(undefined, {minimumFractionDigits: 2, maximumFractionDigits: 2});
                }

                // Update Latency
                const latEl = document.getElementById('valLatency');
                latEl.textContent = data.latency + 'ms';
                const latMeta = document.getElementById('latencyMeta');
                if (data.latency < 1000) {
                    latEl.style.color = 'var(--text-primary)';
                    latMeta.textContent = 'OPTIMAL PEER SPEED';
                } else if (data.latency < 2000) {
                    latEl.style.color = 'var(--accent-amber)';
                    latMeta.textContent = 'ELEVATED ROUNDTRIP';
                } else {
                    latEl.style.color = 'var(--accent-red)';
                    latMeta.textContent = 'DEGRADED LATENCY';
                }

                // Update Hash
                document.getElementById('valHash').textContent = data.hash || 'WAITING FOR DATA...';

                // Update Stats
                document.getElementById('valUptime').textContent = data.uptime;
                document.getElementById('valBlocks').textContent = data.totalBlocksSeen;
                document.getElementById('valAlerts').textContent = data.totalAlertsSent;

                updateStatusBadge(data.status);

                // Update History Table
                const tbody = document.getElementById('blocksTableBody');
                if (data.history && data.history.length > 0) {
                    tbody.innerHTML = data.history.map(b => `
                        <tr>
                            <td class="highlight">#${b.height.toLocaleString()}</td>
                            <td class="mono">${truncateHash(b.hash)}</td>
                            <td>${b.txCount.toLocaleString()} txs</td>
                            <td class="highlight">$${b.price.toLocaleString(undefined, {minimumFractionDigits: 2})}</td>
                            <td class="mono">${b.time}</td>
                        </tr>
                    `).join('');
                }

                // Update Event Log
                const eventList = document.getElementById('eventList');
                if (data.events && data.events.length > 0) {
                    eventList.innerHTML = data.events.map((e, idx) => {
                        const num = String(idx + 1).padStart(2, '0');
                        let badgeClass = 'event-badge';
                        if (e.type === 'NEW_BLOCK') badgeClass += ' new_block';
                        else if (e.type === 'REORG') badgeClass += ' reorg';
                        else if (e.type === 'PRICE_ALERT') badgeClass += ' price_alert';
                        return `
                            <div class="event-row">
                                <span class="event-index">${num}</span>
                                <span class="event-time-tag">${e.time}</span>
                                <span class="${badgeClass}">${e.type}</span>
                                <span class="event-desc">${e.message}</span>
                            </div>
                        `;
                    }).join('');
                }

            } catch (err) {
                console.error(err);
                if (!isFirstLoad) {
                    updateStatusBadge('DISCONNECTED');
                }
            }
        }

        setInterval(updateDashboard, 3000);
        updateDashboard();
    </script>
</body>
</html>
""";
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    static class TelemetryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String json = generateTelemetryJson();
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    static class EventsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            StringBuilder sb = new StringBuilder("[");
            
            stateLock.readLock().lock();
            try {
                boolean first = true;
                for (EventLog e : eventLogs) {
                    if (!first) sb.append(",");
                    sb.append(e.toJson());
                    first = false;
                }
            } finally {
                stateLock.readLock().unlock();
            }
            sb.append("]");
            
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static String generateTelemetryJson() {
        stateLock.readLock().lock();
        try {
            StringBuilder historyJson = new StringBuilder("[");
            boolean first = true;
            for (BlockInfo b : blockHistory) {
                if (!first) historyJson.append(",");
                historyJson.append(b.toJson());
                first = false;
            }
            historyJson.append("]");

            StringBuilder eventsJson = new StringBuilder("[");
            first = true;
            for (EventLog e : eventLogs) {
                if (!first) eventsJson.append(",");
                eventsJson.append(e.toJson());
                first = false;
            }
            eventsJson.append("]");

            return String.format(Locale.US,
                "{" +
                "\"height\":%d," +
                "\"hash\":\"%s\"," +
                "\"price\":%.2f," +
                "\"latency\":%d," +
                "\"status\":\"%s\"," +
                "\"lastCheck\":\"%s\"," +
                "\"uptime\":\"%s\"," +
                "\"totalBlocksSeen\":%d," +
                "\"totalAlertsSent\":%d," +
                "\"history\":%s," +
                "\"events\":%s" +
                "}",
                currentHeight, currentHash, currentPrice, lastLatencyMs, serviceStatus,
                timeFormatter.format(Instant.now()), getUptimeStr(),
                totalBlocksSeen.get(), totalAlertsSent.get(),
                historyJson.toString(), eventsJson.toString()
            );
        } finally {
            stateLock.readLock().unlock();
        }
    }
}

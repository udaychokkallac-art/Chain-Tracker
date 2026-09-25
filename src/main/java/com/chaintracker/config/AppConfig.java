package com.chaintracker.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Immutable and type-safe application configuration loader.
 * Prioritizes config.properties, with fallback to environment variables and production defaults.
 */
public class AppConfig {

    private final String telegramBotToken;
    private final String telegramChatId;
    private final int serverPort;
    private final int pollIntervalSeconds;
    private final double priceAlertThreshold;
    private final String blockApiUrl;
    private final String priceApiUrl;
    private final String wsUrl;
    private final String dataDir;

    private AppConfig(Builder builder) {
        this.telegramBotToken = builder.telegramBotToken;
        this.telegramChatId = builder.telegramChatId;
        this.serverPort = builder.serverPort;
        this.pollIntervalSeconds = builder.pollIntervalSeconds;
        this.priceAlertThreshold = builder.priceAlertThreshold;
        this.blockApiUrl = builder.blockApiUrl;
        this.priceApiUrl = builder.priceApiUrl;
        this.wsUrl = builder.wsUrl;
        this.dataDir = builder.dataDir;
    }

    public static AppConfig load(String configFilePath) {
        Properties props = new Properties();
        Path path = Paths.get(configFilePath);

        if (Files.exists(path)) {
            try (InputStream is = Files.newInputStream(path)) {
                props.load(is);
            } catch (IOException e) {
                System.err.println("[CONFIG] Error reading " + configFilePath + ": " + e.getMessage());
            }
        }

        return new Builder()
                .telegramBotToken(getVal(props, "telegram.bot.token", "TELEGRAM_BOT_TOKEN", ""))
                .telegramChatId(getVal(props, "telegram.chat.id", "TELEGRAM_CHAT_ID", ""))
                .serverPort(Integer.parseInt(getVal(props, "server.port", "SERVER_PORT", "8080")))
                .pollIntervalSeconds(Integer.parseInt(getVal(props, "monitor.poll.interval.seconds", "POLL_INTERVAL_SECONDS", "15")))
                .priceAlertThreshold(Double.parseDouble(getVal(props, "monitor.price.alert.threshold", "PRICE_ALERT_THRESHOLD", "5.0")))
                .blockApiUrl(getVal(props, "api.bitcoin.latestblock", "BLOCK_API_URL", "https://blockchain.info/latestblock"))
                .priceApiUrl(getVal(props, "api.bitcoin.price", "PRICE_API_URL", "https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT"))
                .wsUrl(getVal(props, "api.bitcoin.websocket", "BITCOIN_WS_URL", "wss://ws.blockchain.info/inv"))
                .dataDir(getVal(props, "storage.data.dir", "DATA_DIR", "data"))
                .build();
    }

    private static String getVal(Properties props, String propKey, String envKey, String defaultVal) {
        String val = props.getProperty(propKey);
        if (val != null && !val.trim().isEmpty()) {
            return val.trim();
        }
        val = System.getenv(envKey);
        if (val != null && !val.trim().isEmpty()) {
            return val.trim();
        }
        return defaultVal;
    }

    public String getTelegramBotToken() { return telegramBotToken; }
    public String getTelegramChatId() { return telegramChatId; }
    public int getServerPort() { return serverPort; }
    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
    public double getPriceAlertThreshold() { return priceAlertThreshold; }
    public String getBlockApiUrl() { return blockApiUrl; }
    public String getPriceApiUrl() { return priceApiUrl; }
    public String getWsUrl() { return wsUrl; }
    public String getDataDir() { return dataDir; }

    public static class Builder {
        private String telegramBotToken = "";
        private String telegramChatId = "";
        private int serverPort = 8080;
        private int pollIntervalSeconds = 15;
        private double priceAlertThreshold = 5.0;
        private String blockApiUrl = "https://blockchain.info/latestblock";
        private String priceApiUrl = "https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT";
        private String wsUrl = "wss://ws.blockchain.info/inv";
        private String dataDir = "data";

        public Builder telegramBotToken(String val) { this.telegramBotToken = val; return this; }
        public Builder telegramChatId(String val) { this.telegramChatId = val; return this; }
        public Builder serverPort(int val) { this.serverPort = val; return this; }
        public Builder pollIntervalSeconds(int val) { this.pollIntervalSeconds = val; return this; }
        public Builder priceAlertThreshold(double val) { this.priceAlertThreshold = val; return this; }
        public Builder blockApiUrl(String val) { this.blockApiUrl = val; return this; }
        public Builder priceApiUrl(String val) { this.priceApiUrl = val; return this; }
        public Builder wsUrl(String val) { this.wsUrl = val; return this; }
        public Builder dataDir(String val) { this.dataDir = val; return this; }

        public AppConfig build() {
            return new AppConfig(this);
        }
    }
}

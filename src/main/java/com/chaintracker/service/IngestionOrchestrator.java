package com.chaintracker.service;

import com.chaintracker.client.BitcoinRestClient;
import com.chaintracker.client.BlockchainWebSocketClient;
import com.chaintracker.client.TelegramAlertClient;
import com.chaintracker.config.AppConfig;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resilient multi-tiered ingestion supervisor.
 * Combines real-time event-driven WebSockets with background REST polling for market pricing,
 * latency profiling, and automated failover.
 */
public class IngestionOrchestrator {

    private final AppConfig config;
    private final BlockchainEngine engine;
    private final TelegramAlertClient telegramClient;
    private final BitcoinRestClient restClient;
    private final BlockchainWebSocketClient wsClient;

    private final ScheduledExecutorService pollerExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private int consecutiveErrors = 0;

    public IngestionOrchestrator(
            AppConfig config,
            BlockchainEngine engine,
            TelegramAlertClient telegramClient
    ) {
        this.config = config;
        this.engine = engine;
        this.telegramClient = telegramClient;
        this.restClient = new BitcoinRestClient(config.getBlockApiUrl(), config.getPriceApiUrl());

        // Configure event-driven WebSocket listener
        this.wsClient = new BlockchainWebSocketClient(
                config.getWsUrl(),
                rawBlock -> {
                    // Instantaneous block discovery (<100ms)
                    engine.onNewBlockDiscovered(
                            rawBlock.height(),
                            rawBlock.hash(),
                            rawBlock.previousHash(),
                            rawBlock.txCount(),
                            "WEBSOCKET_STREAM"
                    );
                },
                engine::setWsConnected
        );
    }

    public synchronized void start() {
        if (isRunning.getAndSet(true)) return;

        // 1. Start real-time WebSocket client
        try {
            wsClient.start();
        } catch (Exception e) {
            System.err.println("[ORCHESTRATOR] WebSocket startup warning: " + e.getMessage());
        }

        // 2. Start heartbeat polling loop (Price updates, latency profiling & failover)
        pollerExecutor.scheduleWithFixedDelay(
                this::pollCycle,
                0,
                config.getPollIntervalSeconds(),
                TimeUnit.SECONDS
        );
    }

    public synchronized void stop() {
        isRunning.set(false);
        wsClient.stop();
        pollerExecutor.shutdownNow();
    }

    private int cycleCount = 0;

    private void pollCycle() {
        cycleCount++;
        boolean wsActive = wsClient.isConnected();

        // 1. Always update spot price from Binance (fast, lightweight, highly reliable)
        double spotPrice = restClient.fetchPriceOnly();
        if (spotPrice > 0) {
            engine.updatePrice(spotPrice);
        }

        // 2. Rate-adaptive polling:
        // When WebSocket is streaming live, check REST block tip once every 4 cycles (60s) to avoid public API rate limits.
        // If WebSocket is disconnected, poll REST block tip every cycle (15s) as immediate emergency failover!
        boolean shouldPollBlock = !wsActive || (cycleCount % 4 == 0);
        if (!shouldPollBlock) {
            return;
        }

        try {
            BitcoinRestClient.PollResult result = restClient.pollLatest();

            consecutiveErrors = 0;
            engine.setLatency(result.latencyMs());

            if (result.price() > 0) {
                engine.updatePrice(result.price());
            }

            // Extract block tip from REST response
            String json = result.blockJson();
            long height = extractLong(json, "height");
            String hash = extractString(json, "hash");

            long txCount = 0;
            int txIndexStart = json.indexOf("\"txIndexes\":[");
            if (txIndexStart != -1) {
                int txIndexEnd = json.indexOf("]", txIndexStart);
                if (txIndexEnd != -1) {
                    String txArray = json.substring(txIndexStart + 13, txIndexEnd).trim();
                    if (!txArray.isEmpty()) {
                        txCount = txArray.split(",").length;
                    }
                }
            }

            if (height > 0 && !hash.isEmpty()) {
                engine.onNewBlockDiscovered(height, hash, "", txCount, "REST_HEARTBEAT");
            }

        } catch (Exception e) {
            // Only trigger critical network error if the WebSocket stream is ALSO down!
            if (!wsActive) {
                consecutiveErrors++;
                System.err.printf("[ORCHESTRATOR] Failover polling error (#%d): %s%n", consecutiveErrors, e.getMessage());

                if (consecutiveErrors >= 3) {
                    engine.setStatus("DEGRADED");
                    if (consecutiveErrors == 3) {
                        telegramClient.sendNetworkError(e.getMessage() != null ? e.getMessage() : "Connection timeout",
                                config.getPollIntervalSeconds());
                        engine.logEvent("NETWORK_ERROR", "Failed to reach Bitcoin endpoints 3 times.");
                    }
                } else {
                    engine.setStatus("WARNING");
                }
            } else {
                // WebSocket is active and streaming blocks; REST sync jitter is non-critical
                System.out.printf("[ORCHESTRATOR] REST sync jitter (%s). WebSocket stream active.%n", e.getMessage());
            }
        }
    }

    private static String extractString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return "";
        int start = idx + pattern.length();
        int end = json.indexOf("\"", start);
        return end != -1 ? json.substring(start, end) : "";
    }

    private static long extractLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx == -1) return 0;
        int start = idx + pattern.length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try {
            return Long.parseLong(json.substring(start, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}

package com.chaintracker.service;

import com.chaintracker.client.TelegramAlertClient;
import com.chaintracker.config.AppConfig;
import com.chaintracker.model.AnalyticsSummary;
import com.chaintracker.model.BlockInfo;
import com.chaintracker.model.EventLog;
import com.chaintracker.repository.BlockRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Core consensus observation and state synchronization engine.
 * Thread-safe state store utilizing ReentrantReadWriteLock to decouple concurrent
 * HTTP dashboard readers from background ingestion writers.
 */
public class BlockchainEngine {

    private final AppConfig config;
    private final BlockRepository repository;
    private final TelegramAlertClient telegramClient;

    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();

    private long currentHeight = 0;
    private String currentHash = "";
    private String currentPrevHash = "";
    private double currentPrice = 0.0;
    private long lastLatencyMs = 0;
    private String serviceStatus = "STARTING";
    private final Instant startTime = Instant.now();

    private final AtomicInteger reorgCounter = new AtomicInteger(0);
    private boolean isWsConnected = false;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public BlockchainEngine(AppConfig config, BlockRepository repository, TelegramAlertClient telegramClient) {
        this.config = config;
        this.repository = repository;
        this.telegramClient = telegramClient;

        // Initialize baseline from persisted repository if available
        List<BlockInfo> saved = repository.getRecentBlocks(1);
        if (!saved.isEmpty()) {
            BlockInfo latest = saved.get(0);
            this.currentHeight = latest.height();
            this.currentHash = latest.hash();
            this.currentPrevHash = latest.previousHash();
            this.currentPrice = latest.price();
        }
    }

    public void setWsConnected(boolean connected) {
        stateLock.writeLock().lock();
        try {
            this.isWsConnected = connected;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public void updatePrice(double newPrice) {
        if (newPrice <= 0) return;

        stateLock.writeLock().lock();
        try {
            if (currentPrice > 0) {
                double pctChange = Math.abs((newPrice - currentPrice) / currentPrice * 100.0);
                if (pctChange >= config.getPriceAlertThreshold()) {
                    telegramClient.sendPriceAlert(pctChange, newPrice, currentPrice);
                    logEvent("PRICE_ALERT", String.format(Locale.US, "BTC moved %.2f%% ($%,.2f -> $%,.2f)",
                            pctChange, currentPrice, newPrice));
                }
            }
            this.currentPrice = newPrice;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public void setLatency(long latencyMs) {
        stateLock.writeLock().lock();
        try {
            this.lastLatencyMs = latencyMs;
            this.serviceStatus = "HEALTHY";
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public void setStatus(String status) {
        stateLock.writeLock().lock();
        try {
            this.serviceStatus = status;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public void onNewBlockDiscovered(long height, String hash, String prevHash, long txCount, String source) {
        stateLock.writeLock().lock();
        try {
            // Case 1: Reorganization at the same height with different hash
            if (currentHeight > 0 && height == currentHeight && !hash.equals(currentHash)) {
                reorgCounter.incrementAndGet();
                logEvent("REORG", String.format("Tip replaced at #%d: %s -> %s", height,
                        truncateHash(currentHash), truncateHash(hash)));
                telegramClient.sendReorg(height, currentHash, hash);
                this.currentHash = hash;
                this.currentPrevHash = prevHash;
                return;
            }

            // Case 2: Same height, same hash, but enriched transaction count arrives
            if (height == currentHeight && hash.equals(currentHash) && txCount > 0) {
                List<BlockInfo> recent = repository.getRecentBlocks(1);
                if (!recent.isEmpty() && recent.get(0).height() == height && recent.get(0).txCount() <= 0) {
                    BlockInfo old = recent.get(0);
                    String parentHash = (prevHash != null && !prevHash.isEmpty()) ? prevHash : (old.previousHash().isEmpty() ? currentPrevHash : old.previousHash());
                    BlockInfo enriched = new BlockInfo(height, hash, parentHash, txCount, currentPrice > 0 ? currentPrice : old.price(), old.time(), "STREAM+ENRICHED");
                    repository.updateHeadBlock(enriched);
                    if (!parentHash.isEmpty()) {
                        this.currentPrevHash = parentHash;
                    }
                }
                return;
            }

            // Case 3: New higher block mined
            if (height > currentHeight) {
                long blocksAhead = currentHeight > 0 ? height - currentHeight : 1;
                String timeStr = TIME_FORMATTER.format(Instant.now());

                // If previousHash was not provided by API, link to previous known tip
                String parentHash = (prevHash != null && !prevHash.isEmpty()) ? prevHash : currentPrevHash;

                BlockInfo block = new BlockInfo(height, hash, parentHash, txCount, currentPrice, timeStr, source);
                repository.saveBlock(block);

                if (currentHeight > 0) {
                    telegramClient.sendNewBlock(block, blocksAhead);
                    logEvent("NEW_BLOCK", String.format(Locale.US, "Block #%,d confirmed via %s (%,d txs)",
                            height, source, txCount));
                }

                this.currentHeight = height;
                this.currentHash = hash;
                this.currentPrevHash = parentHash;
            }
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public void logEvent(String type, String message) {
        String timeStr = TIME_FORMATTER.format(Instant.now());
        EventLog event = new EventLog(type, message, timeStr);
        repository.saveEvent(event);
    }

    public String generateTelemetryJson() {
        stateLock.readLock().lock();
        try {
            List<BlockInfo> history = repository.getRecentBlocks(20);
            List<EventLog> events = repository.getRecentEvents(50);

            StringBuilder historyJson = new StringBuilder("[");
            for (int i = 0; i < history.size(); i++) {
                if (i > 0) historyJson.append(",");
                historyJson.append(history.get(i).toJson());
            }
            historyJson.append("]");

            StringBuilder eventsJson = new StringBuilder("[");
            for (int i = 0; i < events.size(); i++) {
                if (i > 0) eventsJson.append(",");
                eventsJson.append(events.get(i).toJson());
            }
            eventsJson.append("]");

            return String.format(Locale.US,
                    "{" +
                    "\"height\":%d," +
                    "\"hash\":\"%s\"," +
                    "\"previousHash\":\"%s\"," +
                    "\"price\":%.2f," +
                    "\"latency\":%d," +
                    "\"status\":\"%s\"," +
                    "\"lastCheck\":\"%s\"," +
                    "\"uptime\":\"%s\"," +
                    "\"totalBlocksSeen\":%d," +
                    "\"totalAlertsSent\":%d," +
                    "\"reorgCount\":%d," +
                    "\"wsConnected\":%b," +
                    "\"history\":%s," +
                    "\"events\":%s" +
                    "}",
                    currentHeight, currentHash, currentPrevHash, currentPrice, lastLatencyMs, serviceStatus,
                    TIME_FORMATTER.format(Instant.now()), getUptimeStr(),
                    repository.getTotalBlocksRecorded(), telegramClient.getTotalAlertsSent(),
                    reorgCounter.get(), isWsConnected,
                    historyJson.toString(), eventsJson.toString()
            );
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public AnalyticsSummary computeAnalytics() {
        stateLock.readLock().lock();
        try {
            List<BlockInfo> blocks = repository.getRecentBlocks(50);
            long totalBlocks = repository.getTotalBlocksRecorded();

            double high = currentPrice;
            double low = currentPrice > 0 ? currentPrice : 0;
            long totalTx = 0;

            for (BlockInfo b : blocks) {
                if (b.price() > high) high = b.price();
                if (b.price() > 0 && (low == 0 || b.price() < low)) low = b.price();
                totalTx += b.txCount();
            }

            long avgTx = blocks.isEmpty() ? 0 : totalTx / blocks.size();
            double avgInterval = 600.0; // Standard 10 min Bitcoin target default

            return new AnalyticsSummary(
                    totalBlocks, avgInterval, avgTx, high, low,
                    reorgCounter.get(), isWsConnected, serviceStatus
            );
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public String getUptimeStr() {
        Duration d = Duration.between(startTime, Instant.now());
        long h = d.toHours();
        long m = d.toMinutesPart();
        return h + "h " + m + "m";
    }

    private static String truncateHash(String hash) {
        if (hash == null || hash.length() < 16) return hash == null ? "" : hash;
        return hash.substring(0, 10) + "..." + hash.substring(hash.length() - 8);
    }

    public long getCurrentHeight() { return currentHeight; }
    public String getCurrentHash() { return currentHash; }
    public double getCurrentPrice() { return currentPrice; }
}

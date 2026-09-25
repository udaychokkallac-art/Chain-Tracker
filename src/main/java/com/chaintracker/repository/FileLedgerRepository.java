package com.chaintracker.repository;

import com.chaintracker.model.BlockInfo;
import com.chaintracker.model.EventLog;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, append-only Write-Ahead Log (WAL) and JSONL ledger repository.
 * Persists blocks and system events to disk for durable survival across application restarts.
 */
public class FileLedgerRepository implements BlockRepository {

    private final Path blocksFile;
    private final Path eventsFile;
    private final Deque<BlockInfo> memoryBlockCache = new ConcurrentLinkedDeque<>();
    private final Deque<EventLog> memoryEventCache = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalBlocksCounter = new AtomicLong(0);
    private final int maxMemoryCacheSize;

    private final Object blockWriteLock = new Object();
    private final Object eventWriteLock = new Object();

    public FileLedgerRepository(String dataDirPath, int maxMemoryCacheSize) {
        this.maxMemoryCacheSize = maxMemoryCacheSize;
        Path dataDir = Paths.get(dataDirPath);

        try {
            if (!Files.exists(dataDir)) {
                Files.createDirectories(dataDir);
            }
        } catch (IOException e) {
            System.err.println("[REPO] Could not create data directory: " + e.getMessage());
        }

        this.blocksFile = dataDir.resolve("blocks.jsonl");
        this.eventsFile = dataDir.resolve("events.jsonl");

        recoverFromDisk();
    }

    private void recoverFromDisk() {
        if (Files.exists(blocksFile)) {
            try (BufferedReader reader = Files.newBufferedReader(blocksFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        BlockInfo block = parseBlockJson(line);
                        if (block != null) {
                            memoryBlockCache.addFirst(block);
                            totalBlocksCounter.incrementAndGet();
                            if (memoryBlockCache.size() > maxMemoryCacheSize) {
                                memoryBlockCache.removeLast();
                            }
                        }
                    }
                }
                System.out.printf("[REPO] Recovered %,d blocks from ledger file: %s%n",
                        totalBlocksCounter.get(), blocksFile);
            } catch (Exception e) {
                System.err.println("[REPO] Error recovering blocks from ledger: " + e.getMessage());
            }
        }

        if (Files.exists(eventsFile)) {
            try (BufferedReader reader = Files.newBufferedReader(eventsFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        EventLog event = parseEventJson(line);
                        if (event != null) {
                            memoryEventCache.addFirst(event);
                            if (memoryEventCache.size() > maxMemoryCacheSize * 2) {
                                memoryEventCache.removeLast();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[REPO] Error recovering events from ledger: " + e.getMessage());
            }
        }
    }

    @Override
    public void saveBlock(BlockInfo block) {
        memoryBlockCache.addFirst(block);
        if (memoryBlockCache.size() > maxMemoryCacheSize) {
            memoryBlockCache.removeLast();
        }
        totalBlocksCounter.incrementAndGet();

        synchronized (blockWriteLock) {
            try {
                String record = block.toJson() + System.lineSeparator();
                Files.writeString(blocksFile, record, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("[REPO] Failed to persist block to disk: " + e.getMessage());
            }
        }
    }

    @Override
    public void updateHeadBlock(BlockInfo block) {
        if (!memoryBlockCache.isEmpty() && memoryBlockCache.peekFirst().height() == block.height()) {
            memoryBlockCache.pollFirst();
            memoryBlockCache.addFirst(block);
        }
    }

    @Override
    public void saveEvent(EventLog event) {
        memoryEventCache.addFirst(event);
        if (memoryEventCache.size() > maxMemoryCacheSize * 2) {
            memoryEventCache.removeLast();
        }

        synchronized (eventWriteLock) {
            try {
                String record = event.toJson() + System.lineSeparator();
                Files.writeString(eventsFile, record, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("[REPO] Failed to persist event to disk: " + e.getMessage());
            }
        }
    }

    @Override
    public List<BlockInfo> getRecentBlocks(int limit) {
        List<BlockInfo> list = new ArrayList<>(limit);
        for (BlockInfo b : memoryBlockCache) {
            list.add(b);
            if (list.size() >= limit) break;
        }
        return list;
    }

    @Override
    public List<EventLog> getRecentEvents(int limit) {
        List<EventLog> list = new ArrayList<>(limit);
        for (EventLog e : memoryEventCache) {
            list.add(e);
            if (list.size() >= limit) break;
        }
        return list;
    }

    @Override
    public long getTotalBlocksRecorded() {
        return totalBlocksCounter.get();
    }

    private static BlockInfo parseBlockJson(String json) {
        try {
            long height = extractLong(json, "height");
            String hash = extractString(json, "hash");
            String prevHash = extractString(json, "previousHash");
            long txCount = extractLong(json, "txCount");
            double price = extractDouble(json, "price");
            String time = extractString(json, "time");
            String source = extractString(json, "source");
            return new BlockInfo(height, hash, prevHash, txCount, price, time, source);
        } catch (Exception e) {
            return null;
        }
    }

    private static EventLog parseEventJson(String json) {
        try {
            String type = extractString(json, "type");
            String msg = extractString(json, "message");
            String time = extractString(json, "time");
            return new EventLog(type, msg, time);
        } catch (Exception e) {
            return null;
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
        return Long.parseLong(json.substring(start, end).trim());
    }

    private static double extractDouble(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx == -1) return 0.0;
        int start = idx + pattern.length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
        return Double.parseDouble(json.substring(start, end).trim());
    }
}

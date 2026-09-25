package com.chaintracker.model;

import java.util.Locale;

/**
 * Immutable domain model representing a discovered Bitcoin block.
 * Explicitly records the block hash, previous block hash, height, and tip market metrics.
 */
public record BlockInfo(
        long height,
        String hash,
        String previousHash,
        long txCount,
        double price,
        String time,
        String source
) {
    public BlockInfo {
        if (hash == null) hash = "";
        if (previousHash == null) previousHash = "";
        if (time == null) time = "";
        if (source == null) source = "UNKNOWN";
    }

    public String toJson() {
        return String.format(Locale.US,
                "{\"height\":%d, \"hash\":\"%s\", \"previousHash\":\"%s\", \"txCount\":%d, \"price\":%.2f, \"time\":\"%s\", \"source\":\"%s\"}",
                height, escape(hash), escape(previousHash), txCount, price, escape(time), escape(source));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

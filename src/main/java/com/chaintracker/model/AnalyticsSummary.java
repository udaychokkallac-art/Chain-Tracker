package com.chaintracker.model;

import java.util.Locale;

/**
 * Aggregated analytics and network statistics computed over the observed block stream.
 */
public record AnalyticsSummary(
        long totalBlocksTracked,
        double avgBlockIntervalSeconds,
        long avgTxCount,
        double highPrice,
        double lowPrice,
        int reorgCount,
        boolean isWsConnected,
        String engineState
) {
    public String toJson() {
        return String.format(Locale.US,
                "{" +
                "\"totalBlocksTracked\":%d," +
                "\"avgBlockIntervalSeconds\":%.1f," +
                "\"avgTxCount\":%d," +
                "\"highPrice\":%.2f," +
                "\"lowPrice\":%.2f," +
                "\"reorgCount\":%d," +
                "\"wsConnected\":%b," +
                "\"engineState\":\"%s\"" +
                "}",
                totalBlocksTracked, avgBlockIntervalSeconds, avgTxCount,
                highPrice, lowPrice, reorgCount, isWsConnected, engineState
        );
    }
}

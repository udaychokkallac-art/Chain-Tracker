package com.chaintracker.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Resilient HTTP client for querying Bitcoin network tip data and spot market pricing.
 */
public class BitcoinRestClient {

    private final HttpClient httpClient;
    private final String blockApiUrl;
    private final String priceApiUrl;

    public BitcoinRestClient(String blockApiUrl, String priceApiUrl) {
        this.blockApiUrl = blockApiUrl;
        this.priceApiUrl = priceApiUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record PollResult(
            String blockJson,
            double price,
            long latencyMs
    ) {}

    public PollResult pollLatest() throws IOException, InterruptedException {
        long start = System.currentTimeMillis();

        HttpRequest blockReq = HttpRequest.newBuilder()
                .uri(URI.create(blockApiUrl))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "ChainTracker/2.0")
                .GET()
                .build();

        HttpResponse<String> blockResp = httpClient.send(blockReq, HttpResponse.BodyHandlers.ofString());
        if (blockResp.statusCode() != 200) {
            throw new IOException("Block API returned HTTP " + blockResp.statusCode());
        }

        double price = 0.0;
        try {
            HttpRequest priceReq = HttpRequest.newBuilder()
                    .uri(URI.create(priceApiUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "ChainTracker/2.0")
                    .GET()
                    .build();

            HttpResponse<String> priceResp = httpClient.send(priceReq, HttpResponse.BodyHandlers.ofString());
            if (priceResp.statusCode() == 200) {
                price = extractDouble(priceResp.body(), "price");
            }
        } catch (Exception e) {
            // Price fetch failure does not invalidate block fetch
        }

        long latency = System.currentTimeMillis() - start;
        return new PollResult(blockResp.body(), price, latency);
    }

    public double fetchPriceOnly() {
        try {
            HttpRequest priceReq = HttpRequest.newBuilder()
                    .uri(URI.create(priceApiUrl))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "ChainTracker/2.0")
                    .GET()
                    .build();

            HttpResponse<String> priceResp = httpClient.send(priceReq, HttpResponse.BodyHandlers.ofString());
            if (priceResp.statusCode() == 200) {
                return extractDouble(priceResp.body(), "price");
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    private static double extractDouble(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return 0.0;
        int start = idx + pattern.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return 0.0;
        try {
            return Double.parseDouble(json.substring(start, end).trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}

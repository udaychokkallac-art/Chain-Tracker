package com.chaintracker.web;

import com.chaintracker.service.BlockchainEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * REST API handlers serving real-time telemetry, aggregated analytics, and consensus event streams.
 */
public class TelemetryController {

    private final BlockchainEngine engine;

    public TelemetryController(BlockchainEngine engine) {
        this.engine = engine;
    }

    public HttpHandler getTelemetryHandler() {
        return exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            sendJsonResponse(exchange, engine.generateTelemetryJson());
        };
    }

    public HttpHandler getAnalyticsHandler() {
        return exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            sendJsonResponse(exchange, engine.computeAnalytics().toJson());
        };
    }

    private static void sendJsonResponse(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}

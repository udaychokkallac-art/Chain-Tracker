package com.chaintracker.web;

import com.chaintracker.service.BlockchainEngine;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server manager configuring routing and thread pool execution.
 */
public class HttpServerManager {

    private final int port;
    private final BlockchainEngine engine;
    private HttpServer server;

    public HttpServerManager(int port, BlockchainEngine engine) {
        this.port = port;
        this.engine = engine;
    }

    public synchronized void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        TelemetryController telemetryController = new TelemetryController(engine);
        AssetController assetController = new AssetController("/web");

        // Static Web Dashboard
        server.createContext("/", assetController);

        // REST API Endpoints
        server.createContext("/api/telemetry", telemetryController.getTelemetryHandler());
        server.createContext("/api/analytics", telemetryController.getAnalyticsHandler());

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println(">>> 🌐 Live Web Dashboard:  http://localhost:" + port);
        System.out.println(">>> 📡 Telemetry REST API:   http://localhost:" + port + "/api/telemetry");
        System.out.println(">>> 📊 Analytics REST API:   http://localhost:" + port + "/api/analytics");
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(1);
        }
    }
}

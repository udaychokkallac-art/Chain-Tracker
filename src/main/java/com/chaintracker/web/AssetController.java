package com.chaintracker.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Controller serving static front-end assets (HTML, CSS, JS) with appropriate MIME types and caching headers.
 */
public class AssetController implements HttpHandler {

    private final String resourceBasePath;

    public AssetController(String resourceBasePath) {
        this.resourceBasePath = resourceBasePath;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path == null || path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }

        byte[] content = loadAsset(path);
        if (content == null) {
            String notFound = "404 Not Found: " + path;
            exchange.sendResponseHeaders(404, notFound.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(notFound.getBytes());
            }
            return;
        }

        String mimeType = detectMimeType(path);
        exchange.getResponseHeaders().set("Content-Type", mimeType);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, must-revalidate");
        exchange.sendResponseHeaders(200, content.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }

    private byte[] loadAsset(String relativePath) {
        String fullResourcePath = resourceBasePath + (relativePath.startsWith("/") ? relativePath : "/" + relativePath);

        // 1. Try classpath resource
        try (InputStream is = getClass().getResourceAsStream(fullResourcePath)) {
            if (is != null) {
                return is.readAllBytes();
            }
        } catch (IOException ignored) {}

        // 2. Try direct filesystem path fallback
        Path fsPath = Paths.get("src/main/resources" + fullResourcePath);
        if (Files.exists(fsPath)) {
            try {
                return Files.readAllBytes(fsPath);
            } catch (IOException ignored) {}
        }

        return null;
    }

    private static String detectMimeType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css")) return "text/css; charset=UTF-8";
        if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (path.endsWith(".json")) return "application/json; charset=UTF-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }
}

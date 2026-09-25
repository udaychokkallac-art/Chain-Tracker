package com.chaintracker;

import com.chaintracker.client.TelegramAlertClient;
import com.chaintracker.config.AppConfig;
import com.chaintracker.repository.BlockRepository;
import com.chaintracker.repository.FileLedgerRepository;
import com.chaintracker.service.BlockchainEngine;
import com.chaintracker.service.IngestionOrchestrator;
import com.chaintracker.web.HttpServerManager;

import java.io.IOException;

/**
 * Main application entrypoint for the ChainTracker Distributed Ledger Observability Engine.
 * Boots the modular event-driven ingestion pipeline, persistence ledger, web dashboard, and alert dispatcher.
 */
public class ChainTrackerApp {

    public static void main(String[] args) throws IOException {
        printBanner();

        // 1. Load type-safe configuration
        String configPath = args.length > 0 ? args[0] : "config.properties";
        AppConfig config = AppConfig.load(configPath);

        // 2. Initialize persistence repository (WAL & disk-backed cache)
        BlockRepository repository = new FileLedgerRepository(config.getDataDir(), 50);

        // 3. Initialize Telegram alert client
        TelegramAlertClient telegramClient = new TelegramAlertClient(
                config.getTelegramBotToken(),
                config.getTelegramChatId()
        );

        // 4. Initialize core consensus state engine
        BlockchainEngine engine = new BlockchainEngine(config, repository, telegramClient);
        engine.logEvent("STARTUP", "ChainTracker 2.0 modular engine booting...");

        // 5. Start embedded web server
        HttpServerManager serverManager = new HttpServerManager(config.getServerPort(), engine);
        serverManager.start();

        // 6. Start ingestion orchestrator (WebSocket stream + REST heartbeat fallback)
        IngestionOrchestrator orchestrator = new IngestionOrchestrator(config, engine, telegramClient);
        orchestrator.start();

        // 7. Dispatch startup notification
        telegramClient.sendStartup(config.getServerPort(), config.getPollIntervalSeconds(), true);

        // 8. Register graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[SHUTDOWN] Terminating ChainTracker engine gracefully...");
            orchestrator.stop();
            serverManager.stop();
            engine.logEvent("SHUTDOWN", "ChainTracker stopped cleanly.");
            System.out.println("[SHUTDOWN] All services stopped.");
        }));

        System.out.println("\n>>> ⛓  Engine running. Press Ctrl+C in console to stop.\n");
    }

    private static void printBanner() {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║          ⛓   CHAINTRACKER 2.0 OBSERVABILITY ENGINE         ║");
        System.out.println("║      Real-Time Distributed Ledger Telemetry & Consensus    ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}

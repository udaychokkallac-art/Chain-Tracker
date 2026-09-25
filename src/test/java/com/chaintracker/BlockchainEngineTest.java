package com.chaintracker;

import com.chaintracker.client.TelegramAlertClient;
import com.chaintracker.config.AppConfig;
import com.chaintracker.model.BlockInfo;
import com.chaintracker.model.EventLog;
import com.chaintracker.repository.BlockRepository;
import com.chaintracker.service.BlockchainEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests verifying consensus state transitions, reorganization detection,
 * and price alert thresholds.
 */
public class BlockchainEngineTest {

    static class MockBlockRepository implements BlockRepository {
        List<BlockInfo> savedBlocks = new ArrayList<>();
        List<EventLog> savedEvents = new ArrayList<>();

        @Override
        public void saveBlock(BlockInfo block) {
            savedBlocks.add(block);
        }

        @Override
        public void saveEvent(EventLog event) {
            savedEvents.add(event);
        }

        @Override
        public List<BlockInfo> getRecentBlocks(int limit) {
            return savedBlocks.subList(0, Math.min(limit, savedBlocks.size()));
        }

        @Override
        public List<EventLog> getRecentEvents(int limit) {
            return savedEvents.subList(0, Math.min(limit, savedEvents.size()));
        }

        @Override
        public long getTotalBlocksRecorded() {
            return savedBlocks.size();
        }
    }

    public static void main(String[] args) {
        System.out.println("Running ChainTracker Engine Unit Tests...");
        testNewBlockIngestion();
        testChainReorganizationDetection();
        testPriceAlertThreshold();
        System.out.println("✅ All 3 test suites passed successfully!");
    }

    private static void testNewBlockIngestion() {
        System.out.print("• Testing new block discovery & height progression... ");
        AppConfig config = new AppConfig.Builder().build();
        MockBlockRepository repo = new MockBlockRepository();
        TelegramAlertClient telegram = new TelegramAlertClient("", "");
        BlockchainEngine engine = new BlockchainEngine(config, repo, telegram);

        engine.onNewBlockDiscovered(900000, "hash_0", "prev_hash", 2500, "TEST");
        assert engine.getCurrentHeight() == 900000 : "Height should be 900000";
        assert "hash_0".equals(engine.getCurrentHash()) : "Hash should match hash_0";

        engine.onNewBlockDiscovered(900001, "hash_1", "hash_0", 3100, "TEST");
        assert engine.getCurrentHeight() == 900001 : "Height should advance to 900001";
        assert repo.savedBlocks.size() == 2 : "Repository should have 2 blocks";
        System.out.println("PASSED");
    }

    private static void testChainReorganizationDetection() {
        System.out.print("• Testing consensus reorganization (reorg) detection... ");
        AppConfig config = new AppConfig.Builder().build();
        MockBlockRepository repo = new MockBlockRepository();
        TelegramAlertClient telegram = new TelegramAlertClient("", "");
        BlockchainEngine engine = new BlockchainEngine(config, repo, telegram);

        // Mine block at 900000
        engine.onNewBlockDiscovered(900000, "hash_A", "prev", 2000, "TEST");

        // Competing block arrives at same height 900000 with different hash!
        engine.onNewBlockDiscovered(900000, "hash_B_WINNER", "prev", 2100, "TEST");

        assert "hash_B_WINNER".equals(engine.getCurrentHash()) : "Tip should rollover to winning branch";
        assert repo.savedEvents.stream().anyMatch(e -> "REORG".equals(e.type())) : "REORG event must be logged";
        System.out.println("PASSED");
    }

    private static void testPriceAlertThreshold() {
        System.out.print("• Testing market price volatility trigger... ");
        AppConfig config = new AppConfig.Builder().priceAlertThreshold(5.0).build();
        MockBlockRepository repo = new MockBlockRepository();
        TelegramAlertClient telegram = new TelegramAlertClient("", "");
        BlockchainEngine engine = new BlockchainEngine(config, repo, telegram);

        engine.updatePrice(80000.0);
        assert engine.getCurrentPrice() == 80000.0 : "Base price set";

        // Small 2% movement -> No alert
        engine.updatePrice(81600.0);
        assert repo.savedEvents.stream().noneMatch(e -> "PRICE_ALERT".equals(e.type())) : "Should not alert on 2%";

        // Large 10% movement -> Alert triggered
        engine.updatePrice(90000.0);
        assert repo.savedEvents.stream().anyMatch(e -> "PRICE_ALERT".equals(e.type())) : "Should alert on 10% swing";
        System.out.println("PASSED");
    }
}

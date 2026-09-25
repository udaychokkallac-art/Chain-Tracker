package com.chaintracker.repository;

import com.chaintracker.model.BlockInfo;
import com.chaintracker.model.EventLog;

import java.util.List;

/**
 * Persistence abstraction for storing and retrieving discovered blockchain blocks and system events.
 */
public interface BlockRepository {

    void saveBlock(BlockInfo block);

    void updateHeadBlock(BlockInfo block);

    void saveEvent(EventLog event);

    List<BlockInfo> getRecentBlocks(int limit);

    List<EventLog> getRecentEvents(int limit);

    long getTotalBlocksRecorded();
}

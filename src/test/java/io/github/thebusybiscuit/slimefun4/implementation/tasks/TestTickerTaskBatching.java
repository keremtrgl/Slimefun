package io.github.thebusybiscuit.slimefun4.implementation.tasks;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;

class TestTickerTaskBatching {

    private static ServerMock server;
    private static Slimefun plugin;

    @BeforeAll
    public static void load() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Slimefun.class);
    }

    @AfterAll
    public static void unload() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Test queueSyncTask runs all queued tasks when drained")
    void testQueueSyncTaskRunsAllTasks() {
        AtomicInteger counter = new AtomicInteger(0);
        TickerTask ticker = Slimefun.getTickerTask();

        ticker.queueSyncTask(counter::incrementAndGet);
        ticker.queueSyncTask(counter::incrementAndGet);
        ticker.queueSyncTask(counter::incrementAndGet);

        ticker.drainSyncTaskQueueForTesting();

        Assertions.assertEquals(3, counter.get());
    }

    @Test
    @DisplayName("Test the queue is empty again after draining")
    void testQueueEmptiedAfterDrain() {
        AtomicInteger counter = new AtomicInteger(0);
        TickerTask ticker = Slimefun.getTickerTask();

        ticker.queueSyncTask(counter::incrementAndGet);
        ticker.drainSyncTaskQueueForTesting();
        ticker.drainSyncTaskQueueForTesting();

        Assertions.assertEquals(1, counter.get());
    }
}

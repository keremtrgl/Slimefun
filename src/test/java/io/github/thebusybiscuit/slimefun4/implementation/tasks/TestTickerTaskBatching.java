package io.github.thebusybiscuit.slimefun4.implementation.tasks;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Test
    @DisplayName("Test run() drains the sync task queue through the real batched Slimefun.runSync path")
    void testRunDrainsQueueThroughBatchedSync() {
        AtomicInteger counter = new AtomicInteger(0);
        TickerTask ticker = Slimefun.getTickerTask();

        ticker.queueSyncTask(counter::incrementAndGet);
        ticker.queueSyncTask(counter::incrementAndGet);

        // TickerTask#run() is public and safe to invoke directly in a MockBukkit
        // context (see TestTickerTaskSleep for precedent - it does not depend on the
        // Bukkit scheduler actually firing). Slimefun.runSync executes synchronously
        // under MockBukkit's unit-test MinecraftVersion, so calling run() here exercises
        // the real production drain path (queueSyncTask -> run() -> single batched
        // Slimefun.runSync call) end-to-end, instead of the test-only drain helper.
        ticker.run();

        Assertions.assertEquals(2, counter.get());
    }

    @Test
    @DisplayName("Test a queued task that throws does not prevent other queued tasks in the same batch from running")
    void testFailingTaskDoesNotBlockOtherQueuedTasks() {
        AtomicInteger counter = new AtomicInteger(0);
        TickerTask ticker = Slimefun.getTickerTask();

        ticker.queueSyncTask(() -> {
            throw new RuntimeException("Simulated failure in a queued sync task");
        });
        ticker.queueSyncTask(counter::incrementAndGet);
        ticker.queueSyncTask(counter::incrementAndGet);

        // Exercised through the real run() -> batched Slimefun.runSync path, not just
        // the test-only helper, so this also confirms fault isolation holds in production.
        ticker.run();

        Assertions.assertEquals(2, counter.get());
    }

    @Test
    @DisplayName("Test concurrent producers never lose or corrupt queued sync tasks")
    void testConcurrentQueueingIsSafe() throws InterruptedException {
        int threads = 8;
        int tasksPerThread = 500;

        AtomicInteger counter = new AtomicInteger(0);
        TickerTask ticker = Slimefun.getTickerTask();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            Thread producer = new Thread(() -> {
                try {
                    startGate.await();

                    for (int j = 0; j < tasksPerThread; j++) {
                        ticker.queueSyncTask(counter::incrementAndGet);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                } catch (RuntimeException e) {
                    // e.g. the ArrayIndexOutOfBoundsException a plain ArrayList throws
                    // when several threads grow it at once
                    failures.incrementAndGet();
                } finally {
                    finished.countDown();
                }
            });

            producer.setDaemon(true);
            producer.start();
        }

        startGate.countDown();
        Assertions.assertTrue(finished.await(30, TimeUnit.SECONDS), "Producer threads did not finish in time");
        Assertions.assertEquals(0, failures.get(), "queueSyncTask threw when called concurrently - it is public API and must be safe from any thread");

        ticker.drainSyncTaskQueueForTesting();

        Assertions.assertEquals(threads * tasksPerThread, counter.get(), "Sync tasks were lost when queued concurrently - the queue is not thread-safe");
    }
}

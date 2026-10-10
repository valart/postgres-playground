package dev.valart.postgreslab.service;

import dev.valart.postgreslab.model.lab.DemoResult;
import dev.valart.postgreslab.model.lab.WorkerResult;
import dev.valart.postgreslab.model.offer.ImportResult;
import dev.valart.postgreslab.model.offer.OfferInput;
import dev.valart.postgreslab.repository.OfferRepository;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeadlockDemoService {
    private final LabService lab;
    private final OfferService offers;
    private final OfferRepository repository;

    public synchronized DemoResult demonstrate(String mode, boolean retry) {
        Checks.mode(mode, "unordered", "ordered");
        long shop = lab.importShop();
        List<Long> products = lab.importProducts(2);
        OfferInput x = new OfferInput(shop, products.get(0), 100, true);
        OfferInput y = new OfferInput(shop, products.get(1), 200, true);
        var firstInserts = new CountDownLatch(2);
        var releaseA = new CountDownLatch(1);
        var releaseB = new CountDownLatch(1);
        var start = new CountDownLatch(1);
        var pidA = new AtomicInteger();
        boolean observedWait = false;
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<WorkerResult> a = workers.submit(() -> {
                await(start);
                return run("A", List.of(x, y), mode, retry, () -> {
                    pidA.set(repository.backendPid());
                    firstInserts.countDown();
                    await(releaseA);
                });
            });
            Future<WorkerResult> b = workers.submit(() -> {
                await(start);
                return run("B", List.of(y, x), mode, retry, () -> {
                    firstInserts.countDown();
                    await(releaseB);
                });
            });
            start.countDown();
            try {
                if (mode.equals("unordered")) {
                    await(firstInserts);
                    releaseA.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!repository.waitingOnLock(pidA.get()) && System.nanoTime() < deadline) {
                        OfferService.pause(10);
                    }
                    observedWait = repository.waitingOnLock(pidA.get());
                    if (!observedWait) throw new IllegalStateException("A did not reach its expected lock wait");
                    releaseB.countDown();
                }
                return new DemoResult(mode, retry, observedWait, List.of(result(a), result(b)));
            } finally {
                releaseA.countDown();
                releaseB.countDown();
            }
        }
    }

    private WorkerResult run(String name, List<OfferInput> batch, String mode, boolean retry, Runnable coordination) {
        try {
            // Ordered transactions may block on the first key; only unordered mode uses the insert barrier.
            ImportResult result = offers.register(batch, mode, retry, mode.equals("unordered") ? coordination : () -> {});
            return new WorkerResult(name, "committed", result.inserted(), result.attempts());
        } catch (RuntimeException exception) {
            if (OfferService.isDeadlock(exception)) return new WorkerResult(name, "40P01", 0, retry ? 3 : 1);
            throw exception;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Demo coordination timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo interrupted", exception);
        }
    }

    private static WorkerResult result(Future<WorkerResult> future) {
        try { return future.get(25, TimeUnit.SECONDS); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Demo failed", exception);
        }
    }
}

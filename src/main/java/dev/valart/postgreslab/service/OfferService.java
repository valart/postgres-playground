package dev.valart.postgreslab.service;

import dev.valart.postgreslab.model.offer.ImportResult;
import dev.valart.postgreslab.model.offer.Offer;
import dev.valart.postgreslab.model.offer.OfferInput;
import dev.valart.postgreslab.model.offer.OfferPatch;
import dev.valart.postgreslab.repository.OfferRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfferService {
    private final OfferRepository repository;
    private final MeterRegistry metrics;
    private final TransactionTemplate transaction;

    public OfferService(OfferRepository repository, MeterRegistry metrics, PlatformTransactionManager manager) {
        this.repository = repository;
        this.metrics = metrics;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(20);
        for (String mode : List.of("unordered", "ordered")) {
            for (String name : List.of("submitted.batches", "submitted.rows", "transaction.attempts",
                    "successful.batches", "committed.rows", "deadlocks", "retries")) {
                metrics.counter("priceradar.import." + name, "mode", mode);
            }
        }
    }

    public ImportResult register(List<OfferInput> offers, String mode, boolean retry) {
        return register(offers, mode, retry, () -> {});
    }

    public ImportResult register(List<OfferInput> offers, String mode, boolean retry, Runnable afterFirstInsert) {
        Checks.mode(mode, "unordered", "ordered");
        if (offers == null) throw new IllegalArgumentException("offers must be an array of items");
        Checks.range("batch size", offers.size(), 1, 500);
        for (OfferInput offer : offers) {
            if (offer == null) throw new IllegalArgumentException("offers cannot contain null items");
        }
        var items = new ArrayList<>(offers);
        if (mode.equals("ordered")) {
            items.sort(Comparator.comparingLong(OfferInput::shopId).thenComparingLong(OfferInput::productId));
        }
        metrics.counter("priceradar.import.submitted.batches", "mode", mode).increment();
        metrics.counter("priceradar.import.submitted.rows", "mode", mode).increment(items.size());
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "failure";
        try {
            for (int attempt = 1; ; attempt++) {
                metrics.counter("priceradar.import.transaction.attempts", "mode", mode).increment();
                int currentAttempt = attempt;
                try {
                    int inserted = transaction.execute(status -> {
                        repository.boundTransaction();
                        int rows = 0;
                        for (int i = 0; i < items.size(); i++) {
                            rows += repository.insert(items.get(i));
                            if (i == 0 && currentAttempt == 1) afterFirstInsert.run();
                        }
                        return rows;
                    });
                    metrics.counter("priceradar.import.successful.batches", "mode", mode).increment();
                    metrics.counter("priceradar.import.committed.rows", "mode", mode).increment(inserted);
                    outcome = "success";
                    return new ImportResult(items.size(), inserted, attempt);
                } catch (RuntimeException exception) {
                    if (!isDeadlock(exception)) throw exception;
                    metrics.counter("priceradar.import.deadlocks", "mode", mode).increment();
                    if (!retry || attempt >= 3) throw exception;
                    metrics.counter("priceradar.import.retries", "mode", mode).increment();
                    pause(30L * attempt);
                }
            }
        } finally {
            sample.stop(metrics.timer("priceradar.import.batch", "mode", mode, "outcome", outcome));
        }
    }

    @Transactional
    public Offer update(long id, OfferPatch patch) { return repository.update(id, patch); }

    public List<Offer> cheapest(long productId, int limit) {
        Checks.range("limit", limit, 1, 100);
        return metrics.timer("priceradar.offer.query").record(() -> repository.cheapest(productId, limit));
    }

    public static boolean isDeadlock(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "40P01".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    public static void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", exception);
        }
    }
}

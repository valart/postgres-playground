package dev.valart.postgreslab.service;

import dev.valart.postgreslab.model.minimum.Minimum;
import dev.valart.postgreslab.model.observation.Observation;
import dev.valart.postgreslab.model.observation.ObservationResult;
import dev.valart.postgreslab.repository.MinimumRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MinimumService {
    private final MinimumRepository repository;
    private final MeterRegistry metrics;
    private final TransactionTemplate transaction;

    public MinimumService(MinimumRepository repository, MeterRegistry metrics, PlatformTransactionManager manager) {
        this.repository = repository;
        this.metrics = metrics;
        transaction = new TransactionTemplate(manager);
        for (String mode : List.of("naive", "conditional")) {
            metrics.counter("priceradar.observations.submitted", "mode", mode);
            metrics.counter("priceradar.observations.processed", "mode", mode);
            metrics.counter("priceradar.minimum.initialized.rows", "mode", mode);
            metrics.counter("priceradar.minimum.updated.rows", "mode", mode);
        }
    }

    public Minimum minimum(long offerId) { return repository.minimum(offerId); }

    public ObservationResult observe(List<Observation> observations, String mode) {
        Checks.mode(mode, "naive", "conditional");
        if (observations == null) throw new IllegalArgumentException("observations must be an array of items");
        Checks.range("batch size", observations.size(), 1, 500);
        for (Observation observation : observations) {
            if (observation == null) throw new IllegalArgumentException("observations cannot contain null items");
        }
        var sorted = new ArrayList<>(observations);
        sorted.sort(Comparator.comparingLong(Observation::offerId));
        metrics.counter("priceradar.observations.submitted", "mode", mode).increment(sorted.size());
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "failure";
        try {
            ObservationResult result = transaction.execute(status -> {
                int initialized = 0;
                int updated = 0;
                for (Observation observation : sorted) {
                    int inserted = repository.initialize(observation);
                    initialized += inserted;
                    if (inserted == 0) updated += repository.update(observation, mode);
                }
                return new ObservationResult(sorted.size(), initialized, updated);
            });
            metrics.counter("priceradar.observations.processed", "mode", mode).increment(result.processed());
            metrics.counter("priceradar.minimum.initialized.rows", "mode", mode).increment(result.initialized());
            metrics.counter("priceradar.minimum.updated.rows", "mode", mode).increment(result.updated());
            outcome = "success";
            return result;
        } finally {
            sample.stop(metrics.timer("priceradar.observation.batch", "mode", mode, "outcome", outcome));
        }
    }
}

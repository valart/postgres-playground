package dev.valart.postgreslab.service;

import dev.valart.postgreslab.repository.LabRepository;
import dev.valart.postgreslab.repository.OfferRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LabService {
    private final LabRepository repository;
    private final OfferRepository offers;
    private final TransactionTemplate transaction;
    private final TransactionTemplate snapshot;

    public LabService(LabRepository repository, OfferRepository offers, PlatformTransactionManager manager) {
        this.repository = repository;
        this.offers = offers;
        transaction = new TransactionTemplate(manager);
        snapshot = new TransactionTemplate(manager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        snapshot.setTimeout(190);
    }

    public synchronized String seed(String size) {
        Checks.mode(size, "small", "large");
        transaction.executeWithoutResult(status -> {
            repository.index("none");
            repository.seed(size.equals("small") ? 400 : 1000, size.equals("small") ? 1000 : 3000);
        });
        repository.analyze();
        return size.equals("small") ? "Seeded 400,000 offers; seed 42; target product 777\n"
                : "Seeded 3,000,000 offers; seed 42; target product 777\n";
    }

    public synchronized String index(String variant) {
        if (!List.of("none", "poor", "good").contains(variant)) throw new IllegalArgumentException("index must be none, poor, or good");
        transaction.executeWithoutResult(status -> repository.index(variant));
        return "Index variant: " + variant + "\n";
    }

    public synchronized String resetMinima() {
        transaction.executeWithoutResult(status -> repository.resetMinima());
        return "Minimum table recreated from current prices. Stop loads before resetting.\n";
    }

    public synchronized String resetImports() {
        transaction.executeWithoutResult(status -> repository.resetImports());
        return "Reserved lab imports removed.\n";
    }

    public long importShop() { return repository.importShop(); }
    public List<Long> importProducts(int count) {
        Checks.range("key count", count, 1, 8000);
        return repository.importProducts(count);
    }
    public String updateData(int limit) {
        Checks.range("offer count", limit, 1, 20000);
        return repository.updateData(limit);
    }
    public String explain(long productId) { return String.join("\n", offers.explain(productId)) + "\n"; }
    public String vacuum() { repository.vacuum(); return "VACUUM (ANALYZE) completed\n"; }
    public String holdSnapshot(int seconds) {
        Checks.range("snapshot seconds", seconds, 1, 180);
        snapshot.executeWithoutResult(status -> repository.holdSnapshot(seconds));
        return "Old snapshot released after " + seconds + " seconds\n";
    }
}

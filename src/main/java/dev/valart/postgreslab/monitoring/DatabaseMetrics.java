package dev.valart.postgreslab.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.FunctionCounter;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class DatabaseMetrics {
    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final Map<String, AtomicLong> values = new HashMap<>();
    private static final Set<String> COUNTERS = Set.of("wal.bytes", "database.deadlocks", "database.blocks.read",
            "database.blocks.hit", "minimum.updates", "minimum.hot.updates", "minimum.vacuums", "minimum.autovacuums");

    public DatabaseMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    @Scheduled(fixedDelay = 5000)
    public void collect() {
        try {
            collectWal();
            collectDatabase();
            collectMinima();
            set("collector.up", 1);
        } catch (RuntimeException exception) {
            set("collector.up", 0);
        }
    }

    private void collectWal() {
        jdbc.query("SELECT wal_bytes::bigint FROM pg_stat_wal", rs -> {
            set("wal.bytes", rs.getLong(1));
        });
    }

    private void collectDatabase() {
        jdbc.query("SELECT deadlocks, blks_read, blks_hit FROM pg_stat_database WHERE datname = current_database()", rs -> {
            set("database.deadlocks", rs.getLong(1));
            set("database.blocks.read", rs.getLong(2));
            set("database.blocks.hit", rs.getLong(3));
        });
        Long waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'", Long.class);
        set("database.lock.waiters", waiting);
    }

    private void collectMinima() {
        jdbc.query("""
                SELECT n_live_tup, n_dead_tup, n_tup_upd, n_tup_hot_upd,
                       vacuum_count, autovacuum_count,
                       pg_table_size(relid), pg_indexes_size(relid)
                FROM pg_stat_user_tables WHERE relname = 'offer_minimum_prices'
                """, rs -> {
            String[] names = {"minimum.live.tuples", "minimum.dead.tuples", "minimum.updates",
                    "minimum.hot.updates", "minimum.vacuums", "minimum.autovacuums",
                    "minimum.table.bytes", "minimum.index.bytes"};
            for (int i = 0; i < names.length; i++) set(names[i], rs.getLong(i + 1));
        });
    }

    private void set(String name, long value) {
        AtomicLong holder = values.computeIfAbsent(name, key -> {
            var number = new AtomicLong();
            if (COUNTERS.contains(key)) {
                FunctionCounter.builder("priceradar.db." + key, number, AtomicLong::doubleValue).register(registry);
            } else {
                registry.gauge("priceradar.db." + key, number);
            }
            return number;
        });
        holder.set(value);
    }
}

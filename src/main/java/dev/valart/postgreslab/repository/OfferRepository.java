package dev.valart.postgreslab.repository;

import dev.valart.postgreslab.model.offer.Offer;
import dev.valart.postgreslab.model.offer.OfferInput;
import dev.valart.postgreslab.model.offer.OfferPatch;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class OfferRepository {
    public static final String CHEAPEST_SQL = """
            SELECT id, shop_id, product_id, current_price, available, last_updated_at
            FROM offers
            WHERE product_id = ? AND available = true
            ORDER BY current_price, id
            LIMIT ?
            """;

    private final JdbcClient jdbc;

    public OfferRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public int insert(OfferInput offer) {
        return jdbc.sql("""
                INSERT INTO offers(shop_id, product_id, current_price, available)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (shop_id, product_id) DO NOTHING
                """).params(offer.shopId(), offer.productId(), offer.currentPrice(), offer.available()).update();
    }

    public Offer update(long id, OfferPatch patch) {
        return jdbc.sql("""
                UPDATE offers SET current_price = ?, available = ?, last_updated_at = now()
                WHERE id = ?
                RETURNING id, shop_id, product_id, current_price, available, last_updated_at
                """).params(patch.currentPrice(), patch.available(), id).query(Offer.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Offer not found"));
    }

    public List<Offer> cheapest(long productId, int limit) {
        return jdbc.sql(CHEAPEST_SQL).params(productId, limit).query(Offer.class).list();
    }

    public List<String> explain(long productId) {
        return jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + CHEAPEST_SQL)
                .params(productId, 20).query(String.class).list();
    }

    public void boundTransaction() {
        jdbc.sql("SET LOCAL statement_timeout = '15s'").update();
        jdbc.sql("SET LOCAL idle_in_transaction_session_timeout = '20s'").update();
    }

    public int backendPid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    public boolean waitingOnLock(int pid) {
        return jdbc.sql("SELECT count(*) > 0 FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock'")
                .param(pid).query(Boolean.class).single();
    }
}

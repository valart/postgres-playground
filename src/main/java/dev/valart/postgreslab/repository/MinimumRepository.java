package dev.valart.postgreslab.repository;

import dev.valart.postgreslab.model.minimum.Minimum;
import dev.valart.postgreslab.model.observation.Observation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class MinimumRepository {
    private final JdbcClient jdbc;

    public MinimumRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public int initialize(Observation observation) {
        return jdbc.sql("""
                INSERT INTO offer_minimum_prices(offer_id, min_price) VALUES (?, ?)
                ON CONFLICT (offer_id) DO NOTHING
                """).params(observation.offerId(), observation.observedPrice()).update();
    }

    public int update(Observation observation, String mode) {
        String sql = mode.equals("naive") ? """
                UPDATE offer_minimum_prices SET min_price = LEAST(min_price, ?)
                WHERE offer_id = ?
                """ : """
                UPDATE offer_minimum_prices SET min_price = ?
                WHERE offer_id = ? AND ? < min_price
                """;
        var statement = jdbc.sql(sql).params(observation.observedPrice(), observation.offerId());
        if (mode.equals("conditional")) statement.param(observation.observedPrice());
        return statement.update();
    }

    public Minimum minimum(long offerId) {
        return jdbc.sql("SELECT offer_id, min_price FROM offer_minimum_prices WHERE offer_id = ?")
                .param(offerId).query(Minimum.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No observed minimum"));
    }
}

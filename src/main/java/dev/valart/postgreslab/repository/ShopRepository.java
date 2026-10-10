package dev.valart.postgreslab.repository;

import dev.valart.postgreslab.model.shop.Shop;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class ShopRepository {
    private final JdbcClient jdbc;

    public ShopRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Shop create(String name) {
        return jdbc.sql("INSERT INTO shops(name) VALUES (?) RETURNING id, name")
                .param(name).query(Shop.class).single();
    }

    public Shop get(long id) {
        return jdbc.sql("SELECT id, name FROM shops WHERE id = ?").param(id)
                .query(Shop.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop not found"));
    }
}

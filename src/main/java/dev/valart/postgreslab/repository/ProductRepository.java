package dev.valart.postgreslab.repository;

import dev.valart.postgreslab.model.product.Product;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class ProductRepository {
    private final JdbcClient jdbc;

    public ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Product create(String name) {
        return jdbc.sql("INSERT INTO products(name) VALUES (?) RETURNING id, name")
                .param(name).query(Product.class).single();
    }

    public Product get(long id) {
        return jdbc.sql("SELECT id, name FROM products WHERE id = ?").param(id)
                .query(Product.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }
}

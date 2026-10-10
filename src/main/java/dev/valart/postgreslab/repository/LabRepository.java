package dev.valart.postgreslab.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LabRepository {
    private final JdbcTemplate jdbc;
    private final JdbcClient client;

    public LabRepository(JdbcTemplate jdbc, JdbcClient client) {
        this.jdbc = jdbc;
        this.client = client;
    }

    public void seed(int shops, int products) {
        jdbc.execute("TRUNCATE offer_minimum_prices, offers, shops, products RESTART IDENTITY");
        jdbc.update("INSERT INTO shops(name) SELECT 'Shop ' || n FROM generate_series(1, ?) n", shops);
        jdbc.update("INSERT INTO products(name) SELECT 'Product ' || n FROM generate_series(1, ?) n", products);
        jdbc.update("""
                INSERT INTO offers(shop_id, product_id, current_price, available)
                SELECT s.id, p.id,
                       CASE WHEN p.id = 777 THEN 9000000 ELSE 10000 END
                           + mod(s.id * 7919 + p.id * 104729 + 42, 500000),
                       mod(s.id * 31 + p.id * 17 + 42, 10) <> 0
                FROM shops s CROSS JOIN products p
                ORDER BY s.id, p.id
                """);
        jdbc.execute("INSERT INTO offer_minimum_prices SELECT id, current_price FROM offers");
    }

    public void analyze() {
        jdbc.execute("ANALYZE offers");
        jdbc.execute("ANALYZE offer_minimum_prices");
    }

    public void index(String variant) {
        jdbc.execute("DROP INDEX IF EXISTS offers_poor_idx");
        jdbc.execute("DROP INDEX IF EXISTS offers_good_idx");
        switch (variant) {
            case "poor" -> jdbc.execute("CREATE INDEX offers_poor_idx ON offers(available, current_price, product_id)");
            case "good" -> jdbc.execute("CREATE INDEX offers_good_idx ON offers(product_id, available, current_price, id)");
            case "none" -> { }
            default -> throw new IllegalArgumentException("index must be none, poor, or good");
        }
        jdbc.execute("ANALYZE offers");
    }

    public void resetMinima() {
        jdbc.execute("TRUNCATE offer_minimum_prices");
        jdbc.execute("INSERT INTO offer_minimum_prices SELECT id, current_price FROM offers");
        jdbc.execute("ANALYZE offer_minimum_prices");
    }

    public long importShop() {
        return client.sql("INSERT INTO shops(name) VALUES ('__lab-import-shop') RETURNING id")
                .query(Long.class).single();
    }

    public List<Long> importProducts(int count) {
        return client.sql("""
                INSERT INTO products(name)
                SELECT '__lab-import-product' FROM generate_series(1, ?)
                RETURNING id
                """).param(count).query(Long.class).list();
    }

    public void resetImports() {
        jdbc.execute("DELETE FROM offer_minimum_prices WHERE offer_id IN (SELECT id FROM offers WHERE shop_id IN (SELECT id FROM shops WHERE name = '__lab-import-shop'))");
        jdbc.execute("DELETE FROM offers WHERE shop_id IN (SELECT id FROM shops WHERE name = '__lab-import-shop')");
        jdbc.execute("DELETE FROM shops WHERE name = '__lab-import-shop'");
        jdbc.execute("DELETE FROM products WHERE name = '__lab-import-product'");
    }

    public String updateData(int limit) {
        List<String> lines = client.sql("SELECT offer_id::text || ',' || min_price::text FROM offer_minimum_prices ORDER BY offer_id LIMIT ?")
                .param(limit).query(String.class).list();
        return String.join("\n", lines);
    }

    public void vacuum() {
        jdbc.execute("VACUUM (ANALYZE) offer_minimum_prices");
    }

    public void holdSnapshot(int seconds) {
        jdbc.queryForObject("SELECT count(*) FROM offer_minimum_prices", Long.class);
        jdbc.execute("SELECT pg_sleep(" + seconds + ")");
    }
}

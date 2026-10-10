package dev.valart.postgreslab.model.offer;

import java.time.OffsetDateTime;

public record Offer(long id, long shopId, long productId, long currentPrice,
                    boolean available, OffsetDateTime lastUpdatedAt) {}

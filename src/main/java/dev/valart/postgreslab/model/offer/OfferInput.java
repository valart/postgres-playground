package dev.valart.postgreslab.model.offer;

public record OfferInput(long shopId, long productId, long currentPrice, boolean available) {}

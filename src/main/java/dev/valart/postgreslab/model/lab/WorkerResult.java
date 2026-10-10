package dev.valart.postgreslab.model.lab;

public record WorkerResult(String worker, String outcome, int inserted, int attempts) {}

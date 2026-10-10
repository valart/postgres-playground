package dev.valart.postgreslab.model.lab;

import java.util.List;

public record DemoResult(String mode, boolean retry, boolean observedWait, List<WorkerResult> workers) {}

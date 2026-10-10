package dev.valart.postgreslab.service;

public final class Checks {
    private Checks() {}

    public static void range(String setting, int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException(setting + " must be " + min + ".." + max);
    }

    public static void mode(String mode, String first, String second) {
        if (!first.equals(mode) && !second.equals(mode)) {
            throw new IllegalArgumentException("mode must be " + first + " or " + second);
        }
    }
}

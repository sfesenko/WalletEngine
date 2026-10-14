package com.github.walletengine.util;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Generic argument guards shared across layers.
 */
public final class Utils {

    private Utils() {
    }

    /**
     * @return the parsed UUID
     * @throws IllegalArgumentException if {@code value} is not a valid UUID
     */
    public static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            var message = field + " is not a valid UUID: " + value;

            throw new IllegalArgumentException(message, e);
        }
    }

    /**
     * @return {@code value}, if positive
     * @throws IllegalArgumentException with the message supplied by {@code message} when {@code value} is zero or negative
     */
    public static long requirePositive(long value, Supplier<String> message) {
        if (value <= 0) {
            throw new IllegalArgumentException(message.get());
        }
        return value;
    }
}

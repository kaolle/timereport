package com.timereport.timeentry.application.service;

final class Versions {
    private Versions() {
    }

    static void validate(long expectedVersion) {
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Expected version must be between 0 and Long.MAX_VALUE - 1");
        }
    }
}

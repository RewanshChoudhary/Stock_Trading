package edu.oslab.threadpool.web.dto;

public record RecoveryCatalogEntry(
        String id,
        String type,
        String description,
        long costMillis,
        int quality,
        int maxAttempts) {
}

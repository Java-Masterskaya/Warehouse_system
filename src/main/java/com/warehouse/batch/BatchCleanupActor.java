package com.warehouse.batch;

/**
 * Stable audit identity used by the automatic expired batch cleanup.
 */
public final class BatchCleanupActor {

    public static final String USERNAME = "system-batch-cleanup";

    private BatchCleanupActor() {
    }
}

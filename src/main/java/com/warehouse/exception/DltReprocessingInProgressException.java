package com.warehouse.exception;

public class DltReprocessingInProgressException extends RuntimeException {

    public DltReprocessingInProgressException() {
        super("DLT reprocessing is already running");
    }
}

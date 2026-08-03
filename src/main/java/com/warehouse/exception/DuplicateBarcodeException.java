package com.warehouse.exception;

public class DuplicateBarcodeException extends RuntimeException {
    public DuplicateBarcodeException(String message) {
        super(message);
    }

    public static DuplicateBarcodeException forBarcode(String barcode) {
        return new DuplicateBarcodeException("Item with barcode '" + barcode + "' already exists");
    }
}

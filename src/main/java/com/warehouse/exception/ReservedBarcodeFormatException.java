package com.warehouse.exception;

public class ReservedBarcodeFormatException extends RuntimeException {
    public ReservedBarcodeFormatException(String message) {
        super(message);
    }

    public static ReservedBarcodeFormatException forBarcode(String barcode) {
        return new ReservedBarcodeFormatException(
                "Barcode '" + barcode + "' uses the format reserved for auto-generation (ITEM-<10 digits>). "
                        + "Leave barcode blank to get one generated automatically, or use a different format.");
    }
}

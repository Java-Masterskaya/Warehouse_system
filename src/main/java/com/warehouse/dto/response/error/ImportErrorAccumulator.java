package com.warehouse.dto.response.error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ImportErrorAccumulator {
    private static final int MAX_ERRORS_TO_KEEP = 100;

    private int totalErrors = 0;
    private final List<ItemImportErrorDto> details = new ArrayList<>();

    public void add(ItemImportErrorDto error) {
        totalErrors++;
        if (details.size() < MAX_ERRORS_TO_KEEP) {
            details.add(error);
        }
    }

    public void addNote(ItemImportErrorDto note) {
        details.add(note);
    }

    public int getTotalErrors() {
        return totalErrors;
    }

    public List<ItemImportErrorDto> getDetails() {
        return Collections.unmodifiableList(details);
    }
}
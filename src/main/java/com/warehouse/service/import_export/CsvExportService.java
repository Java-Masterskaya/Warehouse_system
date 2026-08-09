package com.warehouse.service.import_export;

import java.io.Writer;

public interface CsvExportService {
    void exportItems(Writer writer, Boolean isActive);

    void exportMovement(Writer writer);
}

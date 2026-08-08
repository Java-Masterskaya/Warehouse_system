package com.warehouse.service.import_export;

import com.warehouse.dto.response.item.ItemImportResultDto;
import org.springframework.web.multipart.MultipartFile;

public interface CsvImportService {
    ItemImportResultDto importItems(MultipartFile file);
}

package com.warehouse.service.batch;

import com.warehouse.entity.Batch;
import com.warehouse.entity.Item;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.repository.BatchRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class BatchServiceImpl implements BatchService {

    BatchRepository batchRepository;

    @Override
    @Transactional
    public Batch createBatch(Item item, int quantity, LocalDateTime expiryDate) {
        log.debug("Creating batch for itemId={}, quantity={}, expiryDate={}", item.getId(), quantity, expiryDate);

        Batch batch = Batch.builder()
                .item(item)
                .quantity(quantity)
                .expiryDate(expiryDate)
                .build();

        Batch saved = batchRepository.save(batch);
        log.info("Batch created: id={}, itemId={}, quantity={}, expiryDate={}",
                saved.getId(), saved.getItem().getId(), saved.getQuantity(), saved.getExpiryDate());

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Batch> findByItemIdOrderByExpiryDate(Long itemId) {
        log.debug("Finding batches for itemId={}, ordered by expiryDate ASC", itemId);
        return batchRepository.findByItemIdOrderByExpiryDateAsc(itemId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Batch> findById(Long id) {
        return batchRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Batch> findAllWithItemByItemId(Long itemId) {
        log.debug("Finding all batches with item for itemId={}", itemId);
        return batchRepository.findAllWithItemByItemId(itemId);
    }
}

package com.warehouse.service;

import com.warehouse.dto.ItemDetails;
import com.warehouse.dto.ItemUpdateRequest;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.mapper.ItemMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;
    private final StockRepository stockRepository;
    private final ItemMapper itemMapper;

    @Transactional
    public ItemDetails updateItem(Long itemId, ItemUpdateRequest request) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Товар с itemId " + itemId + " не найден"
                ));
        if (!item.isActive()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Товар неактивен и не подлежит редактированию"
            );
        }

        item.setName(request.getName());
        item.setCategory(request.getCategory());
        item.setMinStock(request.getMinStock());

        Item savedItem = itemRepository.save(item);
        ItemDetails details = itemMapper.toItemDetails(savedItem);

        Stock stock = stockRepository.findByItemId(savedItem.getId()).orElse(null);
        if (stock != null) {
            details.setCurrentStock(stock.getQuantity());
        } else {
            details.setCurrentStock(0);
        }
        return details;
    }
}
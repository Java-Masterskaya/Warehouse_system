package com.warehouse.mapper;

import com.warehouse.dto.response.reservation.ReservationResponse;
import com.warehouse.entity.Reservation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface StockReservationMapper {

    @Mapping(target = "itemId", source = "stock.item.id")
    @Mapping(target = "userId", source = "user.id")
    ReservationResponse mapReservationToResponse(Reservation reservation);
}

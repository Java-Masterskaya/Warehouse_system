package com.warehouse.repository;

import com.warehouse.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ReservationExpirationRepositoryTest {

    @Autowired
    private StockReserveRepository reserveRepository;
    @Autowired
    private StockRepository stockRepository;
    @Autowired
    private ItemRepository itemRepository;
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private CategoryRepository categoryRepository;

    private User user;

    private Item item1;
    private Item item2;
    private Item item3;

    private Stock stock1;
    private Stock stock2;
    private Stock stock3;

    private Category category;

    @BeforeEach
    void setUp() {
        user = userRepository.save(
                User.builder().username("name").password("passW@23d").role(Role.ROLE_ADMIN).active(true)
                        .createdAt(LocalDateTime.now()).build());

        category = categoryRepository.save(
                Category.builder()
                        .name("category")
                        .build()
        );

        item1 = addItem(LocalDateTime.now());
        item2 = addItem(LocalDateTime.now());
        item3 = addItem(LocalDateTime.now());

        stock1 = addStock(item1);
        stock2 = addStock(item2);
        stock3 = addStock(item3);
    }

    @Test
    void expireReservationsShouldChangeExpiredActiveReservations() {

        Reservation activeExpired = Reservation.builder().stock(stock1).user(user)
                .expiredAt(LocalDateTime.now().minusDays(1)).quantity(10).status(ReservationStatus.ACTIVE).build();

        Reservation activeNotExpired = Reservation.builder().stock(stock1).user(user).status(ReservationStatus.ACTIVE)
                .expiredAt(LocalDateTime.now().plusDays(1)).quantity(5).build();

        Reservation consumedExpired = Reservation.builder().stock(stock3).user(user).status(ReservationStatus.CONSUMED)
                .expiredAt(LocalDateTime.now().minusDays(1)).quantity(7).build();

        entityManager.persist(activeExpired);
        entityManager.persist(activeNotExpired);
        entityManager.persist(consumedExpired);

        entityManager.flush();

        int updated = reserveRepository.expireReservations(LocalDateTime.now());

        entityManager.clear();

        Reservation expired = entityManager.find(Reservation.class, activeExpired.getId());
        Reservation notExpired = entityManager.find(Reservation.class, activeNotExpired.getId());
        Reservation consumed = entityManager.find(Reservation.class, consumedExpired.getId());

        assertThat(updated).isEqualTo(1);
        assertThat(expired.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(notExpired.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(consumed.getStatus()).isEqualTo(ReservationStatus.CONSUMED);
    }

    private Item addItem(LocalDateTime timestamp) {
        return itemRepository.save(
                Item.builder().sku("someArt123" + timestamp).name("name" + timestamp).category(category).minStock(0)
                        .active(true).createdAt(LocalDateTime.now().minusMonths(10)).build());
    }

    private Stock addStock(Item item) {
        return stockRepository.save(Stock.builder().item(item).quantity(10).build());
    }
}

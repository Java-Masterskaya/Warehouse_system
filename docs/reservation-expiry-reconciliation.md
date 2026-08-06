# Reservation reconciliation during batch cleanup

Expired batch cleanup treats a reservation as an indivisible commitment. After
the expired physical quantity is removed, active reservations must fit into the
remaining stock for the same item and warehouse scope.

The cleanup policy is:

1. Lock the scope `Stock` row, then the expired batches and logically active
   reservations in the same transaction.
2. Preserve older reservations first.
3. If the active reserved total exceeds the remaining stock, cancel whole
   reservations from newest to oldest, ordered by `createdAt DESC, id DESC`,
   until the invariant is restored.
4. Never reduce a reservation quantity. Whole cancellation can therefore free
   more units than the exact shortage.

A reservation is logically active for this operation when its status is
`ACTIVE` and its `expiredAt` value is later than the cleanup timestamp. The
selected reservations transition to `CANCELED`.

The batch quantities, physical `Stock` quantity, reservation statuses, and the
aggregate `MovementType.EXPIRED` audit record are committed atomically for one
`(itemId, warehouseId)` scope. The audit movement contains only the physical
quantity removed from expired batches. Reservation cancellation does not change
that quantity.

Reservation creation, release, reserved write-off, and cleanup serialize on the
same pessimistically locked `Stock` row. This prevents a concurrent operation
from observing or committing an intermediate scope state.

## Cancellation trace

Automatic cancellation does not create another `StockMovement` and does not
publish a Kafka event because it does not move physical stock. After the cleanup
transaction commits successfully, a structured warning is emitted with the
item, warehouse, canceled reservation identifiers, canceled quantity, and
remaining physical stock. A rolled-back cleanup does not emit this record. This
provides an operational trace without changing the physical movement audit
quantity.

## Default warehouse invariant

The current reservation API does not accept a warehouse identifier and creates
reservations only against the default warehouse stock resolved by
`StockRepository.findByItemIdForUpdate`. Release and reserved write-off rely on
this creation invariant and lock the same default stock before resolving the
reservation. A reservation attached to another warehouse is outside the current
API contract. Cleanup of the default scope locks the same `Stock` row before
locking reservations, which is the common serialization point for supported
reservation mutations.

Any future multi-warehouse reservation API must accept an explicit warehouse
identifier and lock that exact stock through
`findByItemIdAndWarehouseIdForUpdate` before reading or changing reservations.
It must also add concurrency coverage proving that reserve, release, reserved
write-off, and cleanup serialize on the selected warehouse stock.

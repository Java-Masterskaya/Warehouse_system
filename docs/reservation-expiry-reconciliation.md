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

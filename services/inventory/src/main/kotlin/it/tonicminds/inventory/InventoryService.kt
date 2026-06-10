package it.tonicminds.inventory

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/** Outcome of a reserve call; `replayed = true` means the idempotent replay path fired. */
data class ReserveOutcome(val reservation: Reservation, val replayed: Boolean)

@Service
class InventoryService(
    private val items: InventoryItemRepository,
    private val reservations: ReservationRepository,
    private val meterRegistry: MeterRegistry,
    @Value("\${inventory.reservation.ttl}") private val reservationTtl: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Business metric for the money-path SLIs (Pillar D): outcome-tagged counter.
    private fun outcomes(outcome: String) =
        meterRegistry.counter("inventory_reservations_total", "outcome", outcome)

    /**
     * Idempotent atomic reservation — dedup and decrement in the SAME transaction.
     *
     * Why this is duplicate-proof (chaos #2/#4, see docs/design.md):
     * - Fast path: a previous attempt for (orderId, productId) exists → return its stored
     *   outcome (replay). A duplicated `order-placed` delivery is a no-op.
     * - Race path: two duplicates run concurrently, both miss the pre-check and both
     *   decrement. The UNIQUE(order_id, product_id) insert then fails for exactly one of
     *   them, rolling back THAT transaction including its decrement: only one commit, one
     *   decrement. The thrown duplicate-key error is self-healing — the at-least-once
     *   redelivery replays and hits the fast path.
     * - Concurrency between DIFFERENT orders is decided by the conditional UPDATE
     *   (`available >= qty`): the row lock serialises them; the loser gets 0 rows = sold out.
     */
    @Transactional
    fun reserve(orderId: String, productId: String, qty: Int): ReserveOutcome {
        require(qty > 0) { "qty must be positive" }

        reservations.findByOrderIdAndProductId(orderId, productId)?.let {
            outcomes("replayed").increment()
            log.info("reserve replay for order={} product={} -> {}", orderId, productId, it.status)
            return ReserveOutcome(it, replayed = true)
        }

        if (!items.existsById(productId)) {
            throw NoSuchElementException("unknown product: $productId")
        }

        val reserved = items.tryDecrement(productId, qty) == 1
        val status = if (reserved) ReservationStatus.RESERVED else ReservationStatus.REJECTED
        val reservation = reservations.save(
            Reservation(
                orderId = orderId,
                productId = productId,
                qty = qty,
                status = status,
                createdAt = Instant.now(),
                expiresAt = Instant.now().plus(reservationTtl),
            ),
        )
        outcomes(if (reserved) "reserved" else "rejected").increment()
        log.info("reserve order={} product={} qty={} -> {}", orderId, productId, qty, status)
        return ReserveOutcome(reservation, replayed = false)
    }

    /**
     * RESERVED → CONFIRMED. Guards the confirm-vs-expiry race (design refinement #3): if
     * the TTL elapsed before the (slow) payment came back, the hold is no longer valid —
     * we expire it here (restocking the seats) and return EXPIRED so the caller (Orders)
     * runs its compensation instead of confirming a seat we no longer hold.
     * Idempotent: confirming a CONFIRMED reservation replays the stored outcome.
     */
    @Transactional
    fun confirm(orderId: String, productId: String): Reservation {
        val r = reservations.findByOrderIdAndProductId(orderId, productId)
            ?: throw NoSuchElementException("no reservation for order=$orderId product=$productId")
        if (r.status == ReservationStatus.RESERVED) {
            if (r.expiresAt.isBefore(Instant.now())) {
                r.status = ReservationStatus.EXPIRED
                reservations.save(r) // explicit save: restock() flushes+clears the context
                items.restock(r.productId, r.qty)
                log.warn("confirm arrived after TTL for order={} -> EXPIRED (restocked)", orderId)
            } else {
                r.status = ReservationStatus.CONFIRMED
                reservations.save(r)
            }
        }
        return r
    }

    /**
     * Saga compensation (payment failed/aborted): RESERVED → RELEASED + restock.
     * Idempotent: releasing an already-terminal reservation replays the stored outcome.
     */
    @Transactional
    fun release(orderId: String, productId: String): Reservation {
        val r = reservations.findByOrderIdAndProductId(orderId, productId)
            ?: throw NoSuchElementException("no reservation for order=$orderId product=$productId")
        if (r.status == ReservationStatus.RESERVED) {
            r.status = ReservationStatus.RELEASED
            reservations.save(r) // explicit save: restock() flushes+clears the context
            items.restock(r.productId, r.qty)
            log.info("released reservation for order={} product={} (restocked {})", orderId, productId, r.qty)
        }
        return r
    }

    /**
     * Reconciler sweep (invoked by [ReservationReconciler]): expire overdue holds and give
     * the seats back, so a crashed/never-finished saga cannot strand inventory forever.
     */
    @Transactional
    fun expireOverdue(now: Instant): Int {
        val overdue = reservations.findByStatusAndExpiresAtBefore(ReservationStatus.RESERVED, now)
        // Persist ALL status transitions before the first restock: restock() clears the
        // persistence context, which would detach the remaining entities mid-loop.
        overdue.forEach { it.status = ReservationStatus.EXPIRED }
        reservations.saveAll(overdue)
        overdue.forEach { items.restock(it.productId, it.qty) }
        if (overdue.isNotEmpty()) {
            outcomes("expired").increment()
        }
        return overdue.size
    }
}

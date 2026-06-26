package it.g03.orders

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * THE ORCHESTRATED SAGA (design decision #3): Orders is the coordinator; the stages are driven by Kafka (order-placed triggers this handler) but each decision is taken via a SYNCHRONOUS call ("a decision must be made now")!
 *
 * Crash/redelivery semantics (at-least-once, chaos #2/#4):
 * - this method deliberately holds NO database transaction across remote calls; each transition commits separately via [OrderStateMachine]
 * - every downstream operation is idempotent (Inventory/Payments replay stored outcomes), so re-running the saga after a crash resumes from where it left off: PENDING → re-runs everything; RESERVED/PAYMENT_PENDING → skip reserve; terminal → no-op
 *
 * Resilience (Pillar C, Phase 2): the Payments call is wrapped in a circuit breaker (see [HttpPaymentsGateway]). A transport fault no longer THROWS out of the saga — it returns "UNAVAILABLE", which PARKS the order in PAYMENT_PENDING for [OrderRecoverySweeper] to retry. A lost optimistic-lock race on a concurrent terminal transition (two sweeper pods, or sweep + redelivery) surfaces as [ObjectOptimisticLockingFailureException] and is absorbed here as a benign no-op (the other driver already advanced the order)
 */

@Component // registers this class as a Spring-managed bean
class OrderSaga(
    private val orders: OrderRepository,
    private val stateMachine: OrderStateMachine,
    private val inventory: InventoryGateway,
    private val payments: PaymentsGateway,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Drives one order through the saga; resumable on redelivery/recovery and a no-op once terminal. A concurrent terminal transition that loses the @Version race rolls back (no duplicate events) and is swallowed here as a clean no-op
    fun handle(orderId: UUID) {
        try {
            handleInternal(orderId)
        } catch (e: ObjectOptimisticLockingFailureException) {
            log.info("order {} advanced concurrently (optimistic-lock race lost) — no-op", orderId)
        }
    }

    private fun handleInternal(orderId: UUID) {
        val order = orders.findById(orderId).orElse(null)
            ?: run { log.warn("order-placed for unknown order {} — skipping", orderId); return }

        if (order.status == OrderStatus.CONFIRMED || order.status == OrderStatus.REJECTED) {
            log.info("saga replay for terminal order {} ({}) — no-op", orderId, order.status)
            return
        }
        val id = orderId.toString()

        // Stage 1 -> reserve the contended resource (skipped when resuming from RESERVED)
        if (order.status == OrderStatus.PENDING) {
            val reservation = inventory.reserve(id, order.productId, order.qty)
            if (reservation.status == "REJECTED") {
                stateMachine.reject(orderId, RejectionReason.SOLD_OUT)
                return
            }
            stateMachine.markReserved(orderId)
        }

        // Stage 2 -> authorize the payment (strictly idempotent on the Payments side). The call is wrapped in a circuit breaker (see HttpPaymentsGateway): three outcomes now, not two
        val authorization = payments.authorize(id, order.amountCents)
        when (authorization.status) {
            "DECLINED" -> {
                // Business decline: compensation — give the seats back, then reject (the customer is told why)
                inventory.release(id, order.productId)
                stateMachine.reject(orderId, RejectionReason.PAYMENT_DECLINED)
                return
            }
            "UNAVAILABLE" -> {
                // Breaker open / transport fault: do NOT reject (the card may be fine) and do NOT propagate the fault. PARK the order — the seats stay held within their TTL — and let OrderRecoverySweeper retry once the breaker closes (Pillar C: contain, don't propagate)
                stateMachine.markPaymentPending(orderId)
                return
            }
            "AUTHORIZED" -> { /* fall through to the confirm stage below */ }
            else -> {
                // Defensive: never CONFIRM on an unexpected status — park and retry rather than confirm a seat we may not have paid for
                log.warn("unexpected authorization status '{}' for order {} — parking PAYMENT_PENDING", authorization.status, id)
                stateMachine.markPaymentPending(orderId)
                return
            }
        }

        // Stage 3 -> confirm the hold (guards the slow-payment-vs-TTL race, refinement #3): Inventory re-checks the hold is still valid; if it expired meanwhile we must NOT confirm a seat we no longer hold
        val confirmation = inventory.confirm(id, order.productId)
        if (confirmation.status != "CONFIRMED") {
            // The hold lapsed before confirm. On a recovery re-run payments.authorize already CHARGED on this pass, so until #4 (refund/void) lands this is a money leak — make it OBSERVABLE (deck 10). [OrderRecoverySweeper] additionally bounds re-authorization by park age, rejecting a clearly-expired hold BEFORE charging, so this branch stays rare (only the sub-second authorize→confirm window)
            // TODO #4: compensate the charge (refund/void on Payments) — tracked in docs/design.md
            meterRegistry.counter("orders_charge_without_refund_total").increment()
            log.warn("order {} confirm returned {} after authorize — REJECTED(RESERVATION_EXPIRED); refund pending #4", id, confirmation.status)
            stateMachine.reject(orderId, RejectionReason.RESERVATION_EXPIRED)
            return
        }

        stateMachine.confirm(orderId)
    }
}

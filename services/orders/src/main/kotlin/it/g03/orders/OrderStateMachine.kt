package it.g03.orders

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Transactional order-state transitions + their outbox events.
 *
 * Why a SEPARATE bean from [OrderSaga]: @Transactional works through Spring's proxy, so a
 * method calling its own @Transactional sibling (self-invocation) would BYPASS the proxy
 * and run without a transaction — a classic pitfall. The saga (which makes remote calls
 * and must NOT hold a DB transaction across them) calls into this bean, so each
 * transition+outbox pair commits atomically.
 *
 * Every transition is idempotent (terminal-state guards): an at-least-once redelivery
 * that re-runs the saga cannot append duplicate events or flip a terminal order.
 */

@Component // registers this class as a Spring-managed bean
class OrderStateMachine(
    private val orders: OrderRepository,
    private val outbox: Outbox,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // PENDING -> RESERVED transition (idempotent: only fires from PENDING)
    @Transactional // transition + any outbox writes commit atomically in one transaction
    fun markReserved(orderId: UUID) {
        val order = load(orderId)
        if (order.status == OrderStatus.PENDING) {
            order.status = OrderStatus.RESERVED
            order.updatedAt = Instant.now()
            orders.save(order)
        }
    }

    /** Terminal happy end: emits order-confirmed + the confirmation notification. */
    @Transactional // transition + both outbox events commit atomically in one transaction
    fun confirm(orderId: UUID) {
        val order = load(orderId)
        if (order.status == OrderStatus.CONFIRMED) return // idempotent replay
        order.status = OrderStatus.CONFIRMED
        order.updatedAt = Instant.now()
        orders.save(order)
        val id = orderId.toString()
        outbox.append(Topics.ORDER_CONFIRMED, id, OrderConfirmedEvent(orderId = id))
        outbox.append(
            Topics.NOTIFICATION_REQUESTED, id,
            NotificationRequestedEvent(
                orderId = id, recipient = "customer@example.org",
                message = "Your order $id is confirmed. Enjoy your trip!",
            ),
        )
        log.info("order {} CONFIRMED", id)
    }

    /** Terminal rejection: emits order-rejected + a notification (the user is told why). */
    @Transactional // transition + both outbox events commit atomically in one transaction
    fun reject(orderId: UUID, reason: RejectionReason) {
        val order = load(orderId)
        if (order.status == OrderStatus.REJECTED) return // idempotent replay
        order.status = OrderStatus.REJECTED
        order.reason = reason
        order.updatedAt = Instant.now()
        orders.save(order)
        val id = orderId.toString()
        outbox.append(Topics.ORDER_REJECTED, id, OrderRejectedEvent(orderId = id, reason = reason.name))
        outbox.append(
            Topics.NOTIFICATION_REQUESTED, id,
            NotificationRequestedEvent(
                orderId = id, recipient = "customer@example.org",
                message = "Your order $id was rejected: $reason.",
            ),
        )
        log.info("order {} REJECTED ({})", id, reason)
    }

    // Loads the order or throws a domain not-found (turned into a 404 at the controller edge)
    private fun load(orderId: UUID): Order =
        orders.findById(orderId).orElseThrow { NoSuchElementException("unknown order: $orderId") }
}

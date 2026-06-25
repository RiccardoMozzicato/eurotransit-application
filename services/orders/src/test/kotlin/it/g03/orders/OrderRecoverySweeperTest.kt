package it.g03.orders

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import kotlin.test.assertEquals

/**
 * Proves OrderRecoverySweeper's money-safety: an order parked beyond app.recovery.max-park is rejected
 * (RESERVATION_EXPIRED) WITHOUT re-authorizing — so a card is never charged for a hold the inventory reconciler has
 * already restocked. max-park is set to PT0S so any parked order is deterministically "beyond" (no wall-clock wait)
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.outbox.enabled=false",
        "app.recovery.enabled=false",
        "app.recovery.max-park=PT0S",
    ],
)
class OrderRecoverySweeperTest {

    @TestConfiguration // swap the HTTP gateways for the steerable fakes (reused from OrderSagaTest)
    class Fakes {
        @Bean @Primary fun fakeInventory() = OrderSagaTest.FakeInventory()
        @Bean @Primary fun fakePayments() = OrderSagaTest.FakePayments()
    }

    @Autowired lateinit var sweeper: OrderRecoverySweeper
    @Autowired lateinit var stateMachine: OrderStateMachine
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var orders: OrderRepository
    @Autowired lateinit var payments: OrderSagaTest.FakePayments

    @Test
    fun `an order parked beyond max-park is REJECTED without charging`() {
        val order = orderService.placeOrder("TRENO-TEST", 1)
        stateMachine.markReserved(order.id)
        stateMachine.markPaymentPending(order.id)
        payments.authorizeCalls = 0

        sweeper.sweepOnce()

        val swept = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.REJECTED, swept.status, "a hold parked past its TTL must be rejected, not retried")
        assertEquals(RejectionReason.RESERVATION_EXPIRED, swept.reason)
        assertEquals(0, payments.authorizeCalls, "money-safe: the card is NOT charged for an expired hold")
    }
}

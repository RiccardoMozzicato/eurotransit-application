package it.g03.inventory

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Outbound eventing port. inventory-reserved / inventory-released are INFORMATIONAL
 * events (observability / eventing requirement): the saga decision travels in the
 * synchronous response to Orders, so losing one of these can never corrupt the money
 * path. That is why a best-effort after-commit publish is enough here, while Orders —
 * whose events ARE load-bearing — uses the transactional outbox instead.
 */

// The port: the service depends on this abstraction, not on Kafka directly (swappable in tests)
interface EventPublisher {
    fun publish(topic: String, key: String, payload: Any)
}

/** inventory-reserved event payload. */
data class InventoryReservedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val orderId: String,
    val productId: String,
    val qty: Int,
    val occurredAt: Instant = Instant.now(),
)

/** inventory-released event payload (`reason`: released | expired). */
data class InventoryReleasedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val orderId: String,
    val productId: String,
    val qty: Int,
    val reason: String,
    val occurredAt: Instant = Instant.now(),
)

@Configuration  // declares Spring beans (the two publisher implementations below)
class EventPublishingConfig {

    /**
     * Real Kafka publisher. Disabled with `events.publish.enabled=false` (used by tests,
     * which have no broker: KafkaTemplate.send would block on metadata otherwise).
     */
    // Registered only when events.publish.enabled is true (the default), i.e. in real runs
    @Bean
    @ConditionalOnProperty(name = ["events.publish.enabled"], havingValue = "true", matchIfMissing = true)
    fun kafkaEventPublisher(kafka: KafkaTemplate<String, String>, om: ObjectMapper): EventPublisher =
        object : EventPublisher {
            private val log = LoggerFactory.getLogger("it.g03.inventory.KafkaEventPublisher")
            override fun publish(topic: String, key: String, payload: Any) {
                // Bounded wait (no unbounded hang, Pillar C); failures are logged, not
                // propagated — informational events must never fail the business call.
                runCatching { kafka.send(topic, key, om.writeValueAsString(payload)).get(5, TimeUnit.SECONDS) }
                    .onFailure { log.warn("failed to publish {} for key {}: {}", topic, key, it.message) }
            }
        }

    /** No-op fallback when publishing is disabled (tests / broker-less local runs). */
    // Registered only if no other EventPublisher exists (i.e. when the Kafka one is disabled)
    @Bean
    @ConditionalOnMissingBean(EventPublisher::class)
    fun noopEventPublisher(): EventPublisher = object : EventPublisher {
        private val log = LoggerFactory.getLogger("it.g03.inventory.NoopEventPublisher")
        override fun publish(topic: String, key: String, payload: Any) {
            log.debug("event publishing disabled, dropping {} for key {}", topic, key)
        }
    }
}

package it.tonicminds.orders

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import kotlin.test.assertTrue

@SpringBootTest(properties = ["spring.kafka.bootstrap-servers=localhost:9092"])
class OrdersApplicationTests {

	@Autowired
	private lateinit var context: ApplicationContext

	@Test
	fun contextLoads() {
	}

	@Test
	fun `resilience4j auto-configuration actually binds on Spring Boot 4`() {
		// The starter is published for the Boot 3 line (see docs/agent-log.md #2): a context
		// that starts is NOT proof the auto-configuration engaged — it could have backed off
		// silently, leaving @CircuitBreaker annotations as inert no-ops. This assertion turns
		// "probably works" into a build-breaking check.
		assertTrue(
			context.getBeanNamesForType(CircuitBreakerRegistry::class.java).isNotEmpty(),
			"CircuitBreakerRegistry bean missing: Resilience4j auto-configuration did not bind",
		)
	}
}

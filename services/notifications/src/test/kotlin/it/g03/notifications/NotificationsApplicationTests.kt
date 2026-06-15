package it.g03.notifications

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

// Minimal smoke test to verify if the Spring context fails to start
@SpringBootTest(
	properties = [
		"spring.kafka.bootstrap-servers=localhost:9092",
		"app.kafka-listeners-enabled=false",
	],
)
class NotificationsApplicationTests {

	@Test
	// no assertions needed (successful startup is the assertion)
	fun contextLoads() {
	}
}

package it.g03.catalog

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

// Minimal smoke test to verify if the Spring context fails to start
@SpringBootTest
class CatalogApplicationTests {

	@Test
	// no assertions needed (successful startup is the assertion)
	fun contextLoads() {
	}
}

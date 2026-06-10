package it.tonicminds.orders

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = ["spring.kafka.bootstrap-servers=localhost:9092"])
class OrdersApplicationTests {

	@Test
	fun contextLoads() {
	}
}

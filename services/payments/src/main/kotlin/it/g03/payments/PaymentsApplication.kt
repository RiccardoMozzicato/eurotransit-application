package it.g03.payments

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// Spring Boot entry point for the Payments service
// @SpringBootApplication enables component scanning and auto-configuration starting from this package
@SpringBootApplication
class PaymentsApplication

// main: launches the Spring application context and embedded server
fun main(args: Array<String>) {
	runApplication<PaymentsApplication>(*args)
}

package it.g03.notifications

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// Spring Boot entry point for the Notifications service
// @SpringBootApplication enables component scanning and auto-configuration starting from this package
@SpringBootApplication
class NotificationsApplication

// main: launches the Spring application context and embedded server
fun main(args: Array<String>) {
	runApplication<NotificationsApplication>(*args)
}

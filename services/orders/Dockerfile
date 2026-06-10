# Multi-stage build (same in all five services).
# Stage 1 "builder": full JDK + Gradle wrapper compile the fat jar. Tests are skipped
# here because CI runs them in a dedicated step BEFORE this image is ever built.
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN ./gradlew build -x test

# Stage 2 "runtime": only the JRE + the jar. The JDK, Gradle caches and sources from
# stage 1 are discarded → smaller image, smaller attack surface (immutable artifact).
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Container hygiene (Lab01): non-root runtime user; port 8080 needs no privileges.
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /app/build/libs/*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

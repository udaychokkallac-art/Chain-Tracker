# Multi-stage Docker build for ChainTracker Observability Engine
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app
COPY src ./src
COPY pom.xml ./

# Compile all source files into classes
RUN mkdir -p target/classes && \
    javac -d target/classes $(find src/main/java -name "*.java") && \
    cp -r src/main/resources/* target/classes/

# Runtime container
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=builder /app/target/classes /app/classes
COPY config.properties.example /app/config.properties.example

# Expose HTTP Dashboard & REST API
EXPOSE 8080

# Persist ledger WAL & blocks
VOLUME ["/app/data"]

ENTRYPOINT ["java", "-cp", "/app/classes", "com.chaintracker.ChainTrackerApp"]

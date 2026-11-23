# Multi-stage build for BOX Cinema Spring Boot Application
# Stage 1: Build the JAR with Maven
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Copy source and build package
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Minimal, secure, and memory-optimized runtime
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copy built JAR from builder stage
COPY --from=builder /app/target/box-cinema-1.0.0-SNAPSHOT.jar app.jar

# Render dynamic port binding (default 10000 or from $PORT)
ENV PORT=10000
EXPOSE 8080 10000

# Memory-optimized flags for Render Free Tier (512MB RAM)
ENTRYPOINT ["sh", "-c", "java -Xmx350m -Xms200m -XX:+UseSerialGC -Dserver.port=${PORT:-10000} -jar app.jar"]


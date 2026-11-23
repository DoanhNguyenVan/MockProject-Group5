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

# Set non-root user for security
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring

# Copy built JAR from builder stage
COPY --from=builder /app/target/box-cinema-1.0.0-SNAPSHOT.jar app.jar

# Render dynamic port binding
ENV PORT=8080
EXPOSE 8080

# Memory-optimized flags for Render Free Tier (512MB RAM)
ENTRYPOINT ["sh", "-c", "java -Xmx350m -Xms200m -XX:+UseSerialGC -Dserver.port=${PORT} -jar app.jar"]

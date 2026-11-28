# Multi-stage build for BOX Cinema Spring Boot Application
# Stage 1: Build the JAR with Maven
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy Maven settings with Google Cloud mirror to bypass Maven Central 429 rate limit
COPY .mvn ./.mvn
COPY pom.xml .

# Pre-fetch dependencies using mirror
RUN mvn dependency:go-offline -B -s .mvn/settings.xml || true

# Copy source and package
COPY src ./src
RUN mvn package -DskipTests -B -s .mvn/settings.xml

# Stage 2: Minimal, secure, and memory-optimized runtime
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copy built JAR from builder stage
COPY --from=builder /app/target/box-cinema-1.0.0-SNAPSHOT.jar app.jar

# Render dynamic port binding (default 10000 or from $PORT)
ENV TZ=Asia/Ho_Chi_Minh
ENV PORT=10000
EXPOSE 8080 10000

# Memory-optimized flags for Render Free Tier (512MB RAM)
ENTRYPOINT ["sh", "-c", "java -Duser.timezone=Asia/Ho_Chi_Minh -Xmx350m -Xms200m -XX:+UseSerialGC -Dserver.port=${PORT:-10000} -jar app.jar"]

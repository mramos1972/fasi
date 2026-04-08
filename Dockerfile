# ── Stage 1: build ────────────────────────────────────────────────────────────
FROM localhost:8082/jenkins-agent-maven:3.9.9-jdk17 AS builder

# Crear el directorio con permisos correctos
RUN mkdir -p /build
WORKDIR /build

COPY pom.xml .

# Crear target/classes antes de que Maven lo intente
RUN mkdir -p target/classes

RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn clean package -DskipTests -q

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S fasi && adduser -S fasi -G fasi
COPY --from=builder /build/target/app.jar app.jar
RUN mkdir -p /uploads && chown fasi:fasi /uploads
USER fasi
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

# --- ETAPA 1: Compilación ---
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# Copiar el wrapper y dependencias primero (aprovecha la caché de capas de Docker)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw && ./mvnw dependency:go-offline -B

# Copiar código fuente y compilar
COPY src ./src
RUN ./mvnw clean package -DskipTests

# --- ETAPA 2: Runtime de Producción (Ligero y Seguro) ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Buenas prácticas de seguridad: ejecutar como usuario sin privilegios
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Traer únicamente el ejecutable generado
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# Forzar perfil prod de Spring Boot
ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["java", "-jar", "app.jar"]

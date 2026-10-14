# --- ETAPA 1: Compilación ---
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app

# Copiar wrapper y dependencias primero para cachear capas
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copiar el código fuente y compilar
COPY src ./src
RUN ./mvnw clean package -DskipTests

# --- ETAPA 2: Runtime ligero ---
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Crear usuario sin privilegios por seguridad
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

COPY --from=build /app/target/*.jar app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

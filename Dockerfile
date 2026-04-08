FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S fasi && adduser -S fasi -G fasi
COPY target/app.jar app.jar
RUN mkdir -p /uploads && chown fasi:fasi /uploads
USER fasi
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

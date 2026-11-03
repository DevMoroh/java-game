# syntax=docker/dockerfile:1
# Один образ: зібраний клієнт (React/Three.js) лежить у static Spring Boot — гра й WebSocket з одного домену.

# --- 1. Клієнт ---
FROM node:22-alpine AS client
WORKDIR /client
COPY client/package.json client/package-lock.json ./
RUN npm ci
COPY client/ ./
RUN npm run build

# --- 2. Сервер ---
FROM eclipse-temurin:25-jdk AS server
WORKDIR /server
# Спершу лише збірка Gradle — залежності кешуються окремим шаром.
COPY server/gradlew server/settings.gradle.kts server/build.gradle.kts ./
COPY server/gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY server/src src
COPY --from=client /client/dist src/main/resources/static
# Тести ганяє CI (їм потрібен Docker для Testcontainers), тут лише збірка.
RUN ./gradlew --no-daemon bootJar -x test

# --- 3. Запуск ---
FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app
COPY --from=server /server/build/libs/*.jar app.jar
USER app
EXPOSE 8080
# Пам'ять JVM — від ліміту контейнера, а не від усієї RAM сервера.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

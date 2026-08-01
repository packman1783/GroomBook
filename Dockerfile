
# Стадия 1: сборка — компилируем и собираем jar

FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Копируем файлы Gradle-обёртки отдельно — Docker кэширует этот слой.
# Зависимости не перекачиваются при каждом изменении исходного кода.
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle .
COPY settings.gradle .

# Скачиваем зависимости (кэшируется пока build.gradle не изменился)
RUN ./gradlew dependencies --no-daemon -q

# Копируем исходный код и собираем jar
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# Стадия 2: финальный образ — только JRE + jar, без исходников и Gradle

FROM eclipse-temurin:21-jre-alpine AS runtime

# Нужен для корректного отображения кириллицы в логах и TIMESTAMP в PostgreSQL
ENV LANG=ru_RU.UTF-8
ENV TZ=Europe/Moscow

# Не запускаем приложение от root — хорошая практика безопасности
RUN addgroup -S groombook && adduser -S groombook -G groombook

WORKDIR /app

# Копируем только jar из стадии сборки
COPY --from=builder /app/build/libs/*.jar app.jar

# Папка для credentials.json Google Calendar — монтируется через volume
RUN mkdir -p /app/credentials && chown groombook:groombook /app/credentials

USER groombook

EXPOSE 8080

# Настройки JVM для контейнера:
# -XX:+UseContainerSupport     — JVM видит лимиты памяти контейнера, а не хоста
# -XX:MaxRAMPercentage=75.0    — использует 75% выделенной контейнеру памяти
# -Djava.security.egd=...      — ускоряет старт (не ждёт медленный /dev/random)
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

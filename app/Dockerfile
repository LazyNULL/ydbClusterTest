# Сборка: Maven + JDK 21
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package

# Запуск: только JRE + собранные классы и зависимости
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/classes /app/classes
COPY --from=build /build/target/lib /app/lib
ENTRYPOINT ["java", "-cp", "/app/classes:/app/lib/*", "ru.test.ydbcluster.App"]

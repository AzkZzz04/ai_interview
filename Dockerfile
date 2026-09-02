FROM gradle:8.14.3-jdk21 AS build

WORKDIR /workspace

COPY --chown=gradle:gradle gradle gradle
COPY --chown=gradle:gradle gradlew build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY --chown=gradle:gradle src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN useradd --system --create-home --uid 10001 appuser

WORKDIR /app
COPY --from=build --chown=appuser:appuser /workspace/build/libs/*.jar app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM eclipse-temurin:25.0.2_10-jdk-noble AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --max-workers=2 dependencies --configuration runtimeClasspath
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --max-workers=2 bootJar

FROM eclipse-temurin:25.0.2_10-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/build/libs/postgres-playground-1.0.0.jar app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

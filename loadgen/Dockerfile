FROM eclipse-temurin:25.0.2_10-jdk-noble AS build
WORKDIR /load
COPY LoadGenerator.java .
RUN javac --release 25 -d classes LoadGenerator.java

FROM eclipse-temurin:25.0.2_10-jre-noble
WORKDIR /load
COPY --from=build /load/classes .
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65"
ENTRYPOINT ["java", "--add-modules", "jdk.httpserver", "LoadGenerator"]

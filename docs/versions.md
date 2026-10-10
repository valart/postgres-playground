# Pinned versions and compatibility

This repository retains the existing starter's Spring Boot **4.1.1** and Java **25**. Version sources were checked on 2026-10-09. Reproducibility does not require the newest release.

| Component | Pin | Official reference |
| --- | --- | --- |
| Spring Boot | 4.1.1 | [System requirements](https://docs.spring.io/spring-boot/system-requirements.html): Java 17–26, Gradle 8.14+ or 9.x |
| Java images | Eclipse Temurin 25.0.2_10, Ubuntu Noble, JDK/JRE | [Official image](https://hub.docker.com/_/eclipse-temurin) |
| Gradle and complete wrapper | 9.2.1 | [Gradle compatibility](https://docs.gradle.org/current/userguide/compatibility.html): Java 25 supported for running Gradle from 9.1.0; [wrapper](https://docs.gradle.org/9.2.1/userguide/gradle_wrapper.html) |
| Dependency management Gradle plugin | 1.1.7 | [Official plugin](https://docs.spring.io/dependency-management-plugin/docs/current/reference/html/) |
| Lombok (compile time only) | 1.18.46 | [Official Gradle setup](https://projectlombok.org/setup/gradle); [changelog](https://projectlombok.org/changelog), Java 25 supported since 1.18.40 |
| PostgreSQL | 18.6-bookworm | [Official image manifest](https://github.com/docker-library/official-images/blob/master/library/postgres); [PostgreSQL 18 docs](https://www.postgresql.org/docs/18/) |
| Prometheus | v3.5.0 | [Official release](https://github.com/prometheus/prometheus/releases/tag/v3.5.0) |
| Grafana | 12.1.1 | [Official release](https://github.com/grafana/grafana/releases/tag/v12.1.1) |

Java libraries use the **fixed Boot 4.1.1 dependency BOM** imported automatically by the Boot and dependency-management Gradle plugins. This pins transitive dependencies too. The [official managed dependency coordinates](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html) explain this mechanism; that page follows the current release, so the 4.1.1 BOM is the immutable authority for this build. `./gradlew dependencies --configuration runtimeClasspath` shows resolved versions. The [Boot Gradle plugin requirements](https://docs.spring.io/spring-boot/gradle-plugin/index.html) support Gradle 9.x.

The resolved BOM pins Flyway **12.4.0**, PostgreSQL JDBC **42.7.13**, and Micrometer **1.17.1**. These were also checked against the downloaded 4.1.1 BOM during implementation.

The wrapper includes the official distribution SHA-256 checksum for Gradle 9.2.1. Gradle verifies downloaded distributions against it. Both Unix and Windows launcher scripts and the wrapper JAR are included. Docker supplies Java 25 to the Gradle build stage and runs only the packaged JAR in the runtime stage.

Boot 4 has modular starters: `spring-boot-starter-webmvc`, `spring-boot-starter-jdbc`, `spring-boot-starter-flyway`, and `spring-boot-starter-actuator`. Flyway needs both its core (through the starter) and **flyway-database-postgresql**; see the [Flyway PostgreSQL reference](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database). The PostgreSQL JDBC driver is a runtime dependency; the Prometheus registry supplies Micrometer's Prometheus endpoint. Lombok is declared as `compileOnly` and `annotationProcessor` for `@RequiredArgsConstructor` and is absent from the runtime classpath. No JPA, repositories from Spring Data, validation, security, reactive stack, or test dependencies are declared.

Docker tags contain concrete releases; none uses `latest`. Tags can still be republished by image maintainers. For byte-for-byte environment reproduction, record the pulled image digests alongside query plans and measurement results.

PostgreSQL 18's official image uses versioned data directories below `/var/lib/postgresql`, so Compose mounts that parent directory rather than the older `/var/lib/postgresql/data` layout.

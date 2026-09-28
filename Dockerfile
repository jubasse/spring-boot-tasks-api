# syntax=docker/dockerfile:1

# The production image of the API; run it with the prod profile and its variables (README, Deploy the API).
# Base images are pinned by digest, and Dependabot proposes their updates.

FROM eclipse-temurin:25-jdk-noble@sha256:5b14970485a676b41faa08f4a7bc8716cc20915daa1d581d7a75f37a8ebaf9a8 AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
# A layer of its own, rebuilt only when the pom changes
RUN ./mvnw --batch-mode --quiet dependency:go-offline
COPY src/main/ src/main/
RUN ./mvnw --batch-mode --quiet -Dmaven.test.skip=true package \
    && cp target/tasks-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-noble@sha256:30772b161c319f9a10c82e30fd77b7b6702c6b051e44e0e9f3d7ab5dd389a5ab
RUN groupadd --system --gid 10001 tasks \
    && useradd --system --uid 10001 --gid tasks --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin tasks
WORKDIR /application
# One layer per rate of change: a new release usually changes only the last one
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

# Heap at 75% of the container's memory instead of 25%. G1 explicitly: below 2 CPUs or 1792 MB, the JVM would pick
# the Serial collector, which stops the whole application for every collection.
ENV JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# AOT cache training run: the context starts up to its refresh, and the classes it loaded and linked are stored in
# app.aot, which the real start maps instead of loading them again. It reaches no service: Liquibase and Hibernate's
# database access are off, and the keys are throwaways.
RUN JWT_SECRET="$(head -c 32 /dev/urandom | base64)" \
    WEBHOOK_ENCRYPTION_KEY="$(head -c 32 /dev/urandom | base64)" \
    java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh \
        -Dspring.datasource.url=jdbc:postgresql://localhost/training \
        -Dspring.liquibase.enabled=false \
        -Dspring.jpa.hibernate.ddl-auto=none \
        -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
        -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
        -Dstorage.driver=aws-s3 \
        -jar application.jar

# After the training run, which must not ask for the production settings. An image started without it would take the
# development defaults, localhost services included.
ENV SPRING_PROFILES_ACTIVE=prod
USER tasks
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "application.jar"]

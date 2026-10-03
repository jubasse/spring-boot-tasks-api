# syntax=docker/dockerfile:1

# The production image of the API; run it with the prod profile and its variables (README, Deploy the API).
# Base images are pinned by digest, and Dependabot proposes their updates.

FROM eclipse-temurin:25-jdk-noble@sha256:0d623ea18d7b0fe1e12a2c0a920f7e950cad433387e5a49d3611e1507fa07602 AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
# A layer of its own, rebuilt only when the pom changes
RUN ./mvnw --batch-mode --quiet dependency:go-offline
COPY src/main/ src/main/
RUN ./mvnw --batch-mode --quiet -Dmaven.test.skip=true package \
    && cp target/tasks-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-noble@sha256:398f810215757dc1926390014272579fb0e57c41ef1c8aa4f64ae761613a168b
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
# database access are off, the keys are throwaways, and Spring Batch keeps its job repository in memory (its JDBC
# repository reads the database's metadata while the context refreshes).
RUN JWT_SECRET="$(head -c 32 /dev/urandom | base64)" \
    WEBHOOK_ENCRYPTION_KEY="$(head -c 32 /dev/urandom | base64)" \
    java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh \
        -Dspring.datasource.url=jdbc:postgresql://localhost/training \
        -Dspring.liquibase.enabled=false \
        -Dspring.jpa.hibernate.ddl-auto=none \
        -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
        -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
        -Dstorage.driver=aws-s3 \
        -Dspring.autoconfigure.exclude=org.springframework.boot.batch.jdbc.autoconfigure.BatchJdbcAutoConfiguration \
        -jar application.jar

# After the training run, which must not ask for the production settings. An image started without it would take the
# development defaults, localhost services included.
ENV SPRING_PROFILES_ACTIVE=prod
USER tasks
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "application.jar"]

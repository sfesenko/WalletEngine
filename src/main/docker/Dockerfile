# syntax=docker/dockerfile:1

# Smallest Liberica JRE image (BellSoft Alpaquita, musl based).
# Build via Maven (skipped by default):
#   mvn package -Ddocker.skip=false
FROM bellsoft/liberica-runtime-container:jre-21-slim-musl

WORKDIR /app
RUN adduser -D -u 1001 spring
USER spring

# The application jar is built locally (`mvn package`); the Maven docker plugin
# passes its exact path as a build arg (`target/<finalName>.jar`). The wildcard
# default keeps a plain `docker build` usable as a fallback.
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

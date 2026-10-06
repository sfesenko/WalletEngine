# syntax=docker/dockerfile:1

# Smallest Liberica JRE image (BellSoft Alpaquita, musl based).
# Build from the project root:
#   mvn package
#   docker build -f src/main/docker/Dockerfile -t walletengine:local .
FROM bellsoft/liberica-runtime-container:jre-21-slim-musl

WORKDIR /app
RUN adduser -D -u 1001 spring
USER spring

# The application jar is built locally (`mvn package`) and copied from target/.
COPY target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

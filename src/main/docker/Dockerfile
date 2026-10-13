# syntax=docker/dockerfile:1

# Smallest Liberica JRE image (BellSoft Alpaquita, musl based).
# Build via Maven (skipped by default):
#   mvn package -Ddocker.skip=false
FROM bellsoft/liberica-runtime-container:jre-21-slim-musl

WORKDIR /app
RUN adduser -D -u 1001 spring
USER spring

# The application jar is built locally (`mvn package`) and copied from target/.
COPY target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

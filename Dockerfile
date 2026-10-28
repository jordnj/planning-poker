# Multi-stage build: the JDK and the Gradle cache stay out of the final image.
#
# Sized for a 512Mi Cloud Run instance. Measured on this image's flags:
# 232 MB heap cap, ~299 MB RSS idle, ~328 MB after a full estimation session.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Dependency layer first so edits to src/ do not re-download the world.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# Do not trust the source's permission bits. `gcloud run deploy --source .`
# zips the working directory, and a Windows filesystem cannot represent the
# Unix executable bit, so gradlew arrives mode 644 and every ./gradlew call
# fails with "Permission denied" (exit 126) even though git records 100755.
RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon dependencies --quiet || true

COPY src ./src
RUN ./gradlew --no-daemon bootJar --quiet \
 && mv build/libs/*.jar /src/app.jar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Unprivileged: nothing here needs root, and Cloud Run does not grant it anyway.
RUN addgroup -S appgroup && adduser -S -u 10001 -G appgroup appuser
COPY --from=build --chown=appuser:appgroup /src/app.jar /app/app.jar
USER appuser

# PORT is injected by Cloud Run; application.yml reads it.
ENV PORT=8080
EXPOSE 8080

# MaxRAMPercentage reads the container's cgroup limit, so this adapts if the
# instance is resized. SerialGC beats G1 for a single small instance: lower
# footprint and no concurrent GC threads to pay for.
# Extra tuning can be injected at runtime via JAVA_TOOL_OPTIONS without
# breaking the exec form (which keeps the JVM as PID 1 for clean SIGTERM).
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=45", \
  "-XX:+UseSerialGC", \
  "-Xss512k", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "/app/app.jar"]

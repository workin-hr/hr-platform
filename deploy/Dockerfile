# The Workin backend, as one image for every environment.
#
# The SAME image runs local, integration and production -- only the profile and
# the injected configuration differ. That is the point: an image that is built
# differently for production is an image production is the first to run.
#
# Build context is the repository root, narrowed by /.dockerignore to the
# backend sources alone. That file is an allowlist, and it is a security
# control: the repository root holds a production database dump, and without it
# the dump would be in every build context.
#
#   docker build -f deploy/Dockerfile -t workin-backend .

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# Wrapper and build files first. These change rarely, so Docker reuses the
# dependency download across every code-only change -- the difference between a
# 20-second rebuild and a 4-minute one.
COPY backend/gradlew backend/settings.gradle backend/build.gradle ./
COPY backend/gradle ./gradle
RUN chmod +x gradlew \
 && ./gradlew --no-daemon dependencies --quiet || true

COPY backend/src ./src

# Tests do not run here. They need Testcontainers, which needs a Docker socket
# this build does not have and should not be given -- CI runs the suite against
# the same sources before anything is built. A build that silently skipped a
# failing test would be worse than one that never claimed to run them.
RUN ./gradlew --no-daemon bootJar --quiet -x test \
 && cp build/libs/*.jar /src/backend.jar

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app

# curl for the container healthcheck. Installed explicitly rather than assumed:
# the JRE image does not carry it, and a HEALTHCHECK whose command is missing
# reports unhealthy forever, which looks exactly like a broken application.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Not root. The application writes only to the uploads volume, mounted with
# this ownership.
RUN useradd --system --uid 10001 --create-home workin

COPY --from=build /src/backend.jar /app/backend.jar
RUN mkdir -p /app/uploads && chown -R workin:workin /app
USER workin

EXPOSE 8080

# Reads the health endpoint rather than the port, so "started listening" and
# "connected to its database" are not confused for one another. start-period
# covers JVM boot and the Phase 1 schema check.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

# MaxRAMPercentage so a cgroup limit actually bounds the heap -- without it the
# JVM sizes against the host's memory and a container limit becomes an OOM kill
# rather than a garbage collection.
#
# ExitOnOutOfMemoryError so a heap exhaustion stops the container instead of
# leaving it up, unhealthy and serving errors, which is the state an
# orchestrator cannot distinguish from a slow dependency.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "/app/backend.jar"]

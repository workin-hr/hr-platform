# Two stages so the shipped image carries a JRE and a jar, not a JDK, Gradle
# and a build cache. The build stage is cached on the dependency layer, so a
# code-only change does not re-download the world.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# Wrapper and build files first: these change rarely, so Docker reuses the
# dependency download across code changes.
COPY backend/gradlew backend/settings.gradle backend/build.gradle ./
COPY backend/gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY backend/src ./src
RUN ./gradlew --no-daemon bootJar --quiet -x test

FROM eclipse-temurin:25-jre
WORKDIR /app

# Not root. The application writes only to the uploads volume, which is
# mounted with this ownership.
RUN useradd --system --uid 10001 --create-home workin
COPY --from=build /src/build/libs/*.jar /app/backend.jar
RUN mkdir -p /app/uploads && chown -R workin:workin /app
USER workin

EXPOSE 8080

# Honours container memory limits rather than the host's, so a cgroup cap
# actually bounds the heap.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/backend.jar"]

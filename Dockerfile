# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# warm the dependency cache so code changes don't re-download the world
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---------- runtime stage ----------
FROM eclipse-temurin:17-jre
# ffmpeg enables video thumbnails + duration probing; curl serves the healthcheck
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/uploads
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

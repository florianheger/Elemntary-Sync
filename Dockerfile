FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml FitCSVTool.jar ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B package

FROM eclipse-temurin:25-jre
RUN useradd --system --create-home --uid 10001 app \
    && mkdir -p /data && chown app /data
WORKDIR /app
COPY --from=build /build/target/elemntary-sync.jar /app/elemntary-sync.jar
COPY FitCSVTool.jar /app/FitCSVTool.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/elemntary-sync.jar"]

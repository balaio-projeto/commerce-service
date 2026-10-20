FROM maven:3.10.0-eclipse-temurin-25-alpine AS build

WORKDIR /workspace

COPY pom.xml ./
COPY src ./src

RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

RUN addgroup -S app && adduser -S -G app app

COPY --from=build --chown=app:app /workspace/target/*.jar /app/app.jar

USER app

EXPOSE 8083

HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=6 \
  CMD wget -q -O /dev/null http://127.0.0.1:8083/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

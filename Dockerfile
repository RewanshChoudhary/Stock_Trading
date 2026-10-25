FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /workspace
COPY . .
RUN mvn --batch-mode --no-transfer-progress -pl threadpool-web -am -DskipTests package

FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=build /workspace/threadpool-web/target/threadpool-web-1.0.0.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

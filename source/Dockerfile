FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./gradlew :console:buildFatJar :worker:buildFatJar --no-daemon

FROM eclipse-temurin:21-jre-alpine AS console
WORKDIR /app
COPY --from=build /app/console/build/libs/console-fat.jar app.jar
EXPOSE 8080
ENV PORT=8080
CMD ["java", "-jar", "app.jar"]

FROM eclipse-temurin:21-jre-alpine AS worker
WORKDIR /app
COPY --from=build /app/worker/build/libs/worker-fat.jar app.jar
CMD ["java", "-jar", "app.jar"]

FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd -r -u 10001 skyshelf && chown skyshelf /app
COPY --from=build /build/target/skyshelf.jar app.jar
USER skyshelf
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]

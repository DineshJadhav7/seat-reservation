# ---- build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- run stage
FROM eclipse-temurin:21-jre
WORKDIR /srv
COPY --from=build /build/target/app.jar app.jar
COPY scripts ./scripts
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"
EXPOSE 8000
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

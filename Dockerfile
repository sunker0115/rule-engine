FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --chown=10001:10001 rule-app/target/rule-app-*.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]

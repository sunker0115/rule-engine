FROM eclipse-temurin:25-jre
WORKDIR /app
COPY rule-app/target/rule-app-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]

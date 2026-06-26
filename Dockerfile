FROM eclipse-temurin:17-jre

WORKDIR /app

COPY app/target/ops-monitor.jar /app/ops-monitor.jar
COPY app/docker /app/docker

RUN mkdir -p /app/data

ENV SERVER_ADDRESS=0.0.0.0

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/ops-monitor.jar"]

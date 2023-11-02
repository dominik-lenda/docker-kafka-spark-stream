# syntax=docker/dockerfile:1

FROM spark:3.4.1-scala2.12-java11-ubuntu

WORKDIR /app

USER root

RUN set -ex; \
    apt-get update; \
    rm -rf /var/lib/apt/lists/*

USER spark

COPY . .

CMD ["/opt/spark/bin/spark-submit", "--class", "upwork.test.docker.TopicHandler", "--master", "local[*]", "/app/target/TopicHandler-1.0-SNAPSHOT-jar-with-dependencies.jar", "cassandra" ,"kafka0", "29092"]


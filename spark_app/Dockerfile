# syntax=docker/dockerfile:1

FROM spark:3.4.1-scala2.12-java11-ubuntu

WORKDIR /app

# COPY --chmod=777 spark-submit.sh /app

USER root

RUN set -ex; \
    apt-get update; \
    rm -rf /var/lib/apt/lists/*

USER spark

COPY . .

# ENTRYPOINT ["./app/spark-submit.sh"]
CMD ["/opt/spark/bin/spark-submit", "--class", "upwork.test.docker.TopicHandler", "--master", "local[*]", "/app/target/TopicHandler-1.0-SNAPSHOT-jar-with-dependencies.jar", "kafka0", "29092"]


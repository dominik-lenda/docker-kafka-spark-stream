#!/bin/bash
 
docker compose down

docker volume prune -f

docker rmi docker-kafka-spark-stream-spark docker-kafka-spark-stream-python_app

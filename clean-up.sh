#!/bin/bash
 
docker compose down

docker volume prune -f

docker rmi docker-kafka-spark-stream-spark docker-kafka-spark-stream-python_app

docker volume rm cassandra-data kafka-data smtp4dev-data zookeeper-data zookeeper-logs

rm -rf /tmp/ch /tmp/check_point
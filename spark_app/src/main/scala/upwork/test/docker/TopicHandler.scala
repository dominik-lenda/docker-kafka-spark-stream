package upwork.test.docker

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.cassandra.DataStreamWriterWrapper

object TopicHandler {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder.appName("Handle data from Kafka topic").getOrCreate()
    spark.conf.set("spark.cassandra.connection.host", "127.0.0.1")
    spark.conf.set("spark.cassandra.auth.username", "cassandra")
    spark.conf.set("spark.cassandra.auth.password", "cassandra")
    spark.conf.set("spark.sql.catalog.mycatalog", "com.datastax.spark.connector.datasource.CassandraCatalog")

    spark.sql("CREATE DATABASE IF NOT EXISTS mycatalog.testks WITH DBPROPERTIES (class='SimpleStrategy',replication_factor='1')")
    spark.sql("CREATE TABLE IF NOT EXISTS mycatalog.testks.user (key STRING, value STRING) USING cassandra PARTITIONED BY (key)")

//    //List their contents
//    spark.sql("SHOW NAMESPACES FROM mycatalog").show()
//    spark.sql("SHOW TABLES FROM mycatalog.testks").show()


    import spark.implicits._
    val host = args(0)
    val port = args(1)
    val df = spark
      .readStream
      .format("kafka")
      .option("startingOffsets", "latest")
      .option("kafka.bootstrap.servers", s"$host:$port")
      .option("subscribe", "registration")
      .load()
    val rawDF = df.selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)").as[(String, String)]

    val dfc = spark.read.table("mycatalog.testks.user")
    dfc.show()

//    val query = rawDF.writeStream
//      .outputMode("append")
//      .format("console")
//      .start()
//    query.awaitTermination()

//    rawDF.writeStream
//      .option("checkpointLocation", "/tmp/check_point/")
//      .format("org.apache.spark.sql.cassandra")
//      .cassandraFormat("user", "testks")
//      .outputMode("append")
//      .start().awaitTermination()


  }

}
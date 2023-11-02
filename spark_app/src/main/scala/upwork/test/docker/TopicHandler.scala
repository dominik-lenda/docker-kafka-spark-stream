package upwork.test.docker

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.cassandra.DataStreamWriterWrapper
import com.bastiaanjansen.otp.{HMACAlgorithm, HOTPGenerator, SecretGenerator}
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets

object TopicHandler {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder.appName("Handle data from Kafka topic").getOrCreate()
    val cassandraConnHost = args(0)
    spark.conf.set("spark.cassandra.connection.host", cassandraConnHost)
    spark.conf.set("spark.cassandra.auth.username", "cassandra")
    spark.conf.set("spark.cassandra.auth.password", "cassandra")
    spark.conf.set("spark.sql.catalog.mycatalog", "com.datastax.spark.connector.datasource.CassandraCatalog")
    spark.conf.set("spark.sql.catalog.mycatalog", "com.datastax.spark.connector.datasource.CassandraCatalog")

    spark.sql("CREATE DATABASE IF NOT EXISTS mycatalog.testks WITH DBPROPERTIES (class='SimpleStrategy',replication_factor='1')")
    spark.sql("CREATE TABLE IF NOT EXISTS mycatalog.testks.user (key STRING, value STRING) USING cassandra PARTITIONED BY (key)")

    import spark.implicits._
    val kafkaHost = args(1)
    val kafkaPort = args(2)
    val df = spark
      .readStream
      .format("kafka")
      .option("startingOffsets", "latest")
      .option("kafka.bootstrap.servers", s"$kafkaHost:$kafkaPort")
      .option("subscribe", "registration")
      .option("failOnDataLoss", "false")
      .load()
    val rawDF = df.selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)").as[(String, String)]

//    val dfc = spark.read.table("mycatalog.testks.user")
//    dfc.show()

     val query = rawDF.writeStream
       .outputMode("append")
       .format("console")
       .start()

//    val checkpointLocation = "/tmp/check_point"
//    val query = rawDF.writeStream
//      .option("checkpointLocation", checkpointLocation)
//      .format("org.apache.spark.sql.cassandra")
//      .cassandraFormat("user", "testks")
//      .outputMode("append")
//      .start()

    val otp = createOTP(8)
    val emailAdress = "dlenda1729@gmail.com"
    mockEmailSending(emailAdress, otp)

    query.awaitTermination()

  }

  def createOTP(passwordLength: Int): String = {
    val secret = SecretGenerator.generate()
    val hotp = new HOTPGenerator.Builder(secret)
      .withPasswordLength(8)
      .withAlgorithm(HMACAlgorithm.SHA256)
      .build()
    val counter = 5
    hotp.generate(counter)
  }

  def mockEmailSending(emailAddress: String, otp: String): Unit = {
    val content = s"OTP for $emailAddress is $otp"
    val path = Paths.get("emailOTP.txt")
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))
  }




}
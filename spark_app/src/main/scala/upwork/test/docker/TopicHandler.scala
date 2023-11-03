package upwork.test.docker

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.cassandra.DataStreamWriterWrapper
import com.bastiaanjansen.otp.{HMACAlgorithm, HOTPGenerator, SecretGenerator}

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

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

    //    val dfc = spark.read.table("mycatalog.testks.user")
    //    dfc.show()

    // Send Email with OTP
    val query = df.writeStream
      .format("memory")
      .queryName("emailQuery")
      .start()

    implicit val ec = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

    val future = Future {
      while (query.isActive) {
        Thread.sleep(5000) // wait for query to start and populate the in-memory table
        val lastEmailsDf = spark.sql("SELECT CAST(value as STRING) FROM emailQuery ORDER BY timestamp DESC LIMIT 2")
        val lastEmails = lastEmailsDf.collect().map(_.mkString(",")).toSeq
        val numLastEmails = lastEmails.length

        numLastEmails match {
          case n if n == 1 => val otp = createOTP(8)
            val emailAdress = lastEmails(0) // send email when only one email is available
            mockEmailSending(emailAdress, otp)
          case n if n == 2 => if (lastEmails(0) != lastEmails(1)) { // send email when new email is pulled among two last emails
            val otp = createOTP(8)
            val emailAdress = lastEmails(0)
            mockEmailSending(emailAdress, otp)
          }
          case _ => {}
        }
        Thread.sleep(2000)
      }
    }

    future.onComplete {
      case Success(_) => println("Email sent successfully.")
      case Failure(e) => println(s"Email sending failed: ${e.getMessage}")
    }

    // Save output in Cassandra
    val sinkDF = df.selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)").as[(String, String)]

    val checkpointLocation = "/tmp/check_point"
    val query1 = sinkDF.writeStream
      .option("checkpointLocation", checkpointLocation)
      .format("org.apache.spark.sql.cassandra")
      .cassandraFormat("user", "testks")
      .outputMode("append")
      .start()

    query.awaitTermination()
    query1.awaitTermination()

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
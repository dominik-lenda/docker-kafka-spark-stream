package docker.spark.streaming

import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import com.bastiaanjansen.otp.{HMACAlgorithm, HOTPGenerator, SecretGenerator}
import org.apache.spark.sql.cassandra.DataStreamWriterWrapper
import org.apache.spark.sql.functions.lit

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import java.util.Properties
import javax.mail._
import javax.mail.internet._

object TopicHandler {
  def main(args: Array[String]): Unit = {

    val spark = SparkSession.builder.appName("Handle data from Kafka topic").getOrCreate()
    val cassandraConnHost = args(0)
    spark.conf.set("spark.cassandra.connection.host", cassandraConnHost)
    spark.conf.set("spark.cassandra.auth.username", "cassandra")
    spark.conf.set("spark.cassandra.auth.password", "cassandra")
    spark.conf.set("spark.sql.catalog.mycatalog", "com.datastax.spark.connector.datasource.CassandraCatalog")

    spark.sql("CREATE DATABASE IF NOT EXISTS mycatalog.testks WITH DBPROPERTIES (class='SimpleStrategy',replication_factor='1')")
    spark.sql("CREATE TABLE IF NOT EXISTS mycatalog.testks.user (id STRING, email STRING) USING cassandra PARTITIONED BY (id)")
    spark.sql("CREATE TABLE IF NOT EXISTS mycatalog.testks.otp (id STRING, email STRING, serverOtp STRING) USING cassandra PARTITIONED BY (id)")

    import spark.implicits._
    val kafkaHost = args(1)
    val kafkaPort = args(2)

    val sdf = spark
      .readStream
      .format("kafka")
      .option("startingOffsets", "latest")
      .option("kafka.bootstrap.servers", s"$kafkaHost:$kafkaPort")
      .option("subscribe", "email,otp")
      .option("failOnDataLoss", "false")
      .option("maxOffsetsPerTrigger", "1")
      .load()

    // Write user data: id and email to cassandra
    val emailDF = sdf.selectExpr("cast(key as string) as id", "cast(value as string) as email").where("topic == 'email'")
    val checkpointLocation = "/tmp/check_point"
    emailDF.writeStream
      .option("checkpointLocation", checkpointLocation)
      .format("org.apache.spark.sql.cassandra")
      .cassandraFormat("user", "testks")
      .outputMode("append")
      .start()

    // send email with OTP and write OTP table
    val smtpHost = args(3)
    val smtpPort = args(4)
    emailDF.writeStream.foreachBatch { (batchDF: DataFrame, batchId: Long) =>
      val emailOtp = batchDF.select("email").collect()
      if (!emailOtp.isEmpty) {
        val email = emailOtp(0).getString(0)
        val serverOtp = createOTP()
        sendEmail(email, serverOtp, smtpHost, smtpPort)

        val otpTable = batchDF.withColumn("serverOtp", lit(serverOtp))
        otpTable.write
          .format("org.apache.spark.sql.cassandra")
          .option("table", "otp")
          .option("keyspace", "testks")
          .mode(SaveMode.Append)
          .save()
      }
    }.start()

    // Confirm if the client OTP is the same as backend generated OTP
    val serverOtpTable = spark.read.table("mycatalog.testks.otp")
    val clientOtpDF = sdf.selectExpr("cast(key as string) as idOtpClient", "cast(value as string) as clientOtp").where("topic == 'otp'")
    val joinCondition = clientOtpDF.col("idOtpClient") === serverOtpTable.col("id")
    val joinedDF = clientOtpDF.join(serverOtpTable, joinCondition).drop("idOtpClient")
    val correctOtpDF = joinedDF.withColumn("isOtpCorrect", joinedDF("serverOtp") === joinedDF("clientOtp"))

    showStream(correctOtpDF)
    correctOtpDF
      .selectExpr("CAST(id AS STRING) AS key", "CAST(isOtpCorrect AS STRING) AS value")
      .writeStream
      .format("kafka")
      .option("kafka.bootstrap.servers", s"$kafkaHost:$kafkaPort")
      .option("subscribe", "confirmation")
      .option("topic", "confirmation")
      .option("checkpointLocation", "/tmp/ch")
      .start()

    spark.streams.awaitAnyTermination()

  }

  def createOTP(): String = {
    val secret = SecretGenerator.generate()
    val hotp = new HOTPGenerator.Builder(secret)
      .withPasswordLength(8)
      .withAlgorithm(HMACAlgorithm.SHA256)
      .build()
    hotp.generate(5)
  }

  def sendEmail(emailAddress: String, otp: String, smtpHost: String, smtpPort: String): Unit = {
    val props = new Properties()
    props.put("mail.smtp.host", smtpHost)
    props.put("mail.smtp.port", smtpPort)

    val session = Session.getInstance(props, null)
    val message = new MimeMessage(session)

    message.setFrom(new InternetAddress("serveotp@mail.com"))
    message.setRecipients(Message.RecipientType.TO, emailAddress)
    message.setSubject("One Time Password")
    message.setText(s"Your OTP is $otp")

    Transport.send(message)
  }

  val showTable = (spark: SparkSession, tableName: String) => spark.read.table(s"mycatalog.testks.$tableName").show()

  val showStream = (df: DataFrame) => df.writeStream.format("console").outputMode("append").start()

}
from flask import Flask, render_template, request, redirect, url_for, flash
from flask_wtf import FlaskForm
from wtforms import StringField, SubmitField, PasswordField
from wtforms.validators import DataRequired, Email
from confluent_kafka import Producer
import uuid
from confluent_kafka import Consumer, KafkaException
from confluent_kafka.admin import AdminClient, NewTopic
from confluent_kafka import TopicPartition
import sys
import json


def topic_exists(admin, topic):
    metadata = admin.list_topics()
    for t in iter(metadata.topics.values()):
        if t.topic == topic:
            return True
    return False


# create new topic and return results dictionary
def create_topic(admin, topic):
    new_topic = NewTopic(topic, num_partitions=1, replication_factor=1)
    result_dict = admin.create_topics([new_topic])
    for topic, future in result_dict.items():
        try:
            future.result()  # The result itself is None
            print("Topic {} created".format(topic))
        except Exception as e:
            print("Failed to create topic {}: {}".format(topic, e))


# Create topics if they don't exist
# admin = AdminClient({"bootstrap.servers": "localhost:9092"})
admin = AdminClient({"bootstrap.servers": "kafka0:29092"})
topic_names = ["user", "otp", "confirmation"]
for topic in topic_names:
    if not topic_exists(admin, topic):
        create_topic(admin, topic)


producer_conf = {
    "bootstrap.servers": "kafka0:29092",
    # "bootstrap.servers": "localhost:9092",
}
producer = Producer(producer_conf)
unique_id = str(uuid.uuid4())
consumer_conf = {
    "bootstrap.servers": "kafka0:29092",
    # "bootstrap.servers": "localhost:9092",
    "group.id": "otp",
    "session.timeout.ms": 60000,
    "auto.offset.reset": "latest",
    "enable.auto.commit": False,
}


consumer = Consumer(consumer_conf)
partitions = [
    TopicPartition("confirmation", 0),
]


def delivery_callback(err, msg):
    if err:
        sys.stderr.write("%% Message failed delivery: %s\n" % err)
    else:
        sys.stderr.write(
            "%% Message delivered to %s [%d] @ %d\n"
            % (msg.topic(), msg.partition(), msg.offset())
        )


def consume_loop():
    # Use static membership to avoid slow rebalances on topic changes
    consumer.assign(partitions)
    while True:
        event = consumer.poll(1.0)
        if event is None:
            continue
        if event.error():
            raise KafkaException(event.error())
        else:
            val = event.value().decode("utf8")
            key = event.key().decode("utf8")
            consumer.commit()
            break
    return key, val


def verifyOTP(id, client_otp):
    producer.produce("otp", key=id, value=client_otp, callback=delivery_callback)
    producer.poll()
    id, verification = consume_loop()
    return id, verification


app = Flask(__name__)
app.config["SECRET_KEY"] = "secret_key12381"


class RegistrationForm(FlaskForm):
    email = StringField("Email", validators=[DataRequired(), Email()])
    name = StringField("Name", validators=[DataRequired()])
    submit = SubmitField("Register")


class OtpForm(FlaskForm):
    otp = PasswordField("Password", validators=[DataRequired()])
    submit = SubmitField("Submit")


session = {}
user = {}


@app.route("/", methods=["GET", "POST"])
def register():
    form = RegistrationForm()
    if form.validate_on_submit():
        user["id"] = str(uuid.uuid4())
        user["email"] = request.form["email"]
        user["name"] = request.form["name"]
        json_val = json.dumps(user).encode("utf8")
        producer.produce(
            "user", key=user["id"], value=json_val, callback=delivery_callback
        )
        producer.poll()

        return redirect(url_for("otp"))
    return render_template("register.html", form=form)


@app.route("/otp", methods=["GET", "POST"])
def otp():
    form = OtpForm()
    if form.validate_on_submit():
        session["id"] = user["id"]
        otp = request.form["otp"]
        id, is_authenticated = verifyOTP(session["id"], otp)
        print(id, is_authenticated)
        if id == session["id"] and is_authenticated == "true":
            return redirect(url_for("home"))
        else:
            flash("Wrong OTP")

    return render_template("otp.html", form=form)


@app.route("/home")
def home():
    return render_template("home.html")


if __name__ == "__main__":
    app.run()

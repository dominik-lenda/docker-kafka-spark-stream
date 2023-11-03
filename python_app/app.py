from flask import Flask, render_template, request, redirect, url_for, flash
from flask_wtf import FlaskForm
from wtforms import StringField, SubmitField, PasswordField
from wtforms.validators import DataRequired, Email
from confluent_kafka import Producer
from confluent_kafka.admin import AdminClient, NewTopic
import uuid

# create topic so that spark can see it when a topic does not exist (kafka volume is mounted for the first time)
admin_client = AdminClient({
    "bootstrap.servers": "kafka0:29092"
})
email_topic = NewTopic("email", num_partitions=1, replication_factor=1)
otp_topic = NewTopic("otp", num_partitions=1, replication_factor=1)
admin_client.create_topics([email_topic, otp_topic])

conf = {'bootstrap.servers': 'kafka0:29092'}
producer = Producer(conf)

app = Flask(__name__)
app.config['SECRET_KEY'] = 'secret_key12381'

class RegistrationForm(FlaskForm):
    email = StringField('Email', validators=[DataRequired(), Email()])
    submit = SubmitField('Register')

class OtpForm(FlaskForm):
    otp = PasswordField('Password', validators=[DataRequired()])
    submit = SubmitField('Submit')

session = {}
@app.route('/', methods=['GET', 'POST'])
def register():
    form = RegistrationForm()
    if form.validate_on_submit():
        session['id'] = str(uuid.uuid4())
        email = request.form['email']
        producer.produce('email_topic', key=session['id'], value=email)
        producer.flush()

        return redirect(url_for('otp'))
    return render_template('register.html', form=form)

@app.route('/otp', methods=['GET', 'POST'])
def otp():
    form = OtpForm()
    if form.validate_on_submit():
        otp = request.form['otp']
        producer.produce("otp_topic", key=session['id'], value=otp)
        producer.flush()

        if otp == '123':
            return redirect(url_for('home'))
        else:
            flash('Wrong OTP')

    return render_template('otp.html', form=form)

@app.route('/home')
def home():
    return render_template('home.html')


if __name__ == '__main__':
    app.run()
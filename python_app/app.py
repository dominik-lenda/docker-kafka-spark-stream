from flask import Flask, render_template, request, redirect, url_for
from flask_wtf import FlaskForm
from wtforms import StringField, SubmitField
from wtforms.validators import DataRequired, Email
from confluent_kafka import Producer


app = Flask(__name__)
app.config['SECRET_KEY'] = 'secret_key12381'

conf = {'bootstrap.servers': 'kafka0:29092'}
producer = Producer(conf)

class RegistrationForm(FlaskForm):
    email = StringField('Email', validators=[DataRequired(), Email()])
    submit = SubmitField('Register')

@app.route('/', methods=['GET', 'POST'])
def register():
    form = RegistrationForm()
    if form.validate_on_submit():
        email = request.form['email']
        producer.produce("registration", key="email", value=email)
        producer.flush()
        return redirect(url_for('home'))
    return render_template('register.html', form=form)

@app.route('/home')
def home():
    return render_template('home.html')


if __name__ == '__main__':
    app.run()
# DROID is a ROBOT Ontology Development Interface

[![Build Status](https://travis-ci.org/ontodev/droid.svg?branch=master)](https://travis-ci.org/ontodev/droid)

DROID is a web-based interface for ontology development. Given an ontology project that uses ROBOT or ODK and GitHub, DROID presents authorized users with a subset of that lower-level functionality, including common ontology browsing, editing, quality control, and version control tasks.

The goal is just to present existing functionality, not to replace it. So DROID reads the files in the GitHub repository, runs tasks from the Makefile, helps users edit existing templates, and pushes changes back to GitHub.

DROID is in early development and is designed to work on Unix (Linux, macOS) systems. It is build on [Flask](https://palletsprojects.com/p/flask/). You need Python 3 and Java installed, and we recommend using `venv`. Clone this repository into a fresh directory, set up `venv`, install requirements with `pip`, and then run `./droid`:

```
virtualenv _venv
source _venv/bin/activate
pip install -r requirements.txt
export FLASK_APP=droid
export FLASK_ENV=development
flask run
```

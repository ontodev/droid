# DROID Reveals that Ordinary Individuals are Developers

[![Build Status](https://travis-ci.org/ontodev/droid.svg?branch=master)](https://travis-ci.org/ontodev/droid)

DROID is a web-based interface for working with (1) a build system, managed by (2) a version control system. The current version of DROID is designed to work with (1) GNU Make and (2) GitHub. Our goal is to make these systems accessible to a wider community of project contributors, by exposing a curated set of functionality that is customized for each project.

DROID differs from Continuous Testing/Integration solutions such as Jenkins or Travis CI because DROID allows users to modify a working copy of a branch and run tasks on an ad-hoc basis before committing changes. DROID differs from Web/Cloud IDEs because users are limited to a specified set of files and tasks.

DROID is in early development and is designed to work on Unix (Linux, macOS) systems. It is build on [Flask](https://palletsprojects.com/p/flask/). You need Python 3 installed, and we recommend using `venv`. Clone this repository into a fresh directory, set up `venv`, install requirements with `pip`, and then run `./droid`:

```
virtualenv _venv
source _venv/bin/activate
pip install -r requirements.txt
export FLASK_APP=droid
export FLASK_ENV=development
flask run
```

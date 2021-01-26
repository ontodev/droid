# DROID Reminds us that Ordinary Individuals can be Developers

[![Build Status](https://travis-ci.org/ontodev/droid.svg?branch=master)](https://travis-ci.org/ontodev/droid)

DROID is a web-based interface for working with (1) a build system, managed by (2) a version control system. The current version of DROID is designed to work with (1) GNU Make and (2) GitHub. Our goal is to make these systems accessible to a wider community of project contributors, by exposing a curated set of functionality that is customized for each project.

DROID differs from Continuous Testing/Integration solutions such as Jenkins or Travis CI because DROID allows users to modify a working copy of a branch and run tasks on an ad-hoc basis before committing changes. DROID differs from Web/Cloud IDEs because users are limited to a specified set of files and tasks.

DROID is in early development and is designed to work on Unix (Linux, macOS) systems.

## DROID and Docker

DROID can be configured to run processes in Docker containers that are dedicated to particular project pranches (see below for configuration options). Note that if you are enabling Docker you should run DROID with root privileges by using the `sudo` command when starting it, otherwise you may run into filesystem permissions-related issues since DROID needs to share volumes between the containers and the host.

## GitHub App Authentication

DROID uses a GitHub App to authenticate on behalf of a logged in github user (see: [Identifying and authorizing users for GitHub Apps](https://docs.github.com/en/free-pro-team@latest/developers/apps/identifying-and-authorizing-users-for-github-apps)).

For this to work properly, DROID assumes that the following environment variables have been set (it may be convenient to add them to your python virtual environment activation script (`_venv/bin/activate`)):
- `GITHUB_CLIENT_ID`
- `GITHUB_CLIENT_SECRET`
- `GITHUB_APP_STATE`

To obtain the values for the first two settings, send an email to james@overton.ca. For the value of `GITHUB_APP_STATE`, a randomly generated string may be used.

## Configuration file

DROID assumes that a file called `config.edn` exists in DROID's root directory. You can find an example in: [example-config.edn](example-config.edn). Before running DROID for the first time, you must create `config.edn`. To do so using the example file as a template, run the following command in DROID's root directory:

    cp example-config.edn config.edn

then edit the newly created `config.edn` file as necessary.

Attempting to start the server with an invalid configuration (required parameters are missing or given in the wrong format) file will result in an error and the server will fail to start.

To dump the currently configured parameters to STDOUT, call the server executable using the command-line switch: `--dump-config`. Note that doing so will output the configuration parameters for the configured operating environment only (dev, test, or prod). See the documentation in [example-config.edn](example-config.edn) for more on operating environments.

To check a configuration file's validity, call the server executable using the command-line switch: `--check-config`.

## The `projects/` directory

DROID assumes that there exists a directory called `projects/` within its root directory. Within `projects/` there should be a subdirectory corresponding to each project defined in `config.edn` (see above). Within each individual project directory there should be a `workspace/` directory. Within each project's `workspace/` directory there should be a subdirectory corresponding to each branch managed by the project. Finally, each branch directory should contain, at a minimum, a Makefile. For example:

```
projects/
├── project1/
│   └── workspace/
│       ├── branch1/
│       │   └── Makefile
│       │   └── ...
│       └── branch2/
│           └── Makefile
│           └── ...
└── project2/
    └── workspace/
        ├── branch1/
        │   └── Makefile
        │   └── ...
        └── branch2/
            └── Makefile
            └── ...
```

The `project1/`, `project2/`, etc. directories (and their subdirectories) should be writable by `DROID`.

## aha - Ansi HTML Adapter

In order to colourize the console output, DROID requires the command-line program `aha` to be installed, which is available on Debian and Ubuntu via `apt` and on Mac via `homebrew`. For more info, see: https://github.com/theZiz/aha.

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

DROID assumes that a file called 'config.edn' exists in DROID's root directory, which should look like the following:

```
{:projects
 {"project1" {:project-title "PROJECT1"
              :project-welcome "welcome message" 
              :project-description "description"
              :github-coordinates "github-org/repository-1"
              :makefile-path "src/ontology/Snakefile"
              :env {"ENV_VAR" "env_var_val"}
              :docker-config {:disabled? false
                              :image "debian"
                              :workspace-dir "/workspace/"
                              :temp-dir "/tmp/droid/"
                              :default-working-dir "/workspace/"
                              :shell-command "bash"
                              :env {"ENV_VAR" "env_var_value"}}}
  "project2" {:project-title "PROJECT2"
              :project-welcome "welcome message"
              :project-description "description"
              :github-coordinates "github-org/repository-2"}
              :env {"ENV_VAR" "env_var_val"}}
 :docker-config {:disabled? false
                 :image "debian"
                 :workspace-dir "/workspace/"
                 :temp-dir "/tmp/droid/"
                 :default-working-dir "/workspace/"
                 :shell-command "bash"
                 :env {"ENV_VAR" "env_var_value"}}
 :op-env :dev
 :server-port {:dev 8000 :test 8001 :prod 8002}
 :log-level {:dev :info :test :info :prod :warn}
 :secure-site {:dev true :test true :prod true}
 :local-mode false
 :site-admin-github-ids {:dev #{"user1" "user2"}
                         :test #{"user1" "user2"}
                         :prod #{"user1" "user2"}}
 :github-app-id {:dev 55555, :test 66666, :prod 77777}
 :pem-file "FILE.pem"
 :push-with-installation-token false
 :cgi-timeout {:dev 60000, :test 60000, :prod 60000}
 :log-file {:dev nil, :test "droid.log", :prod "droid.log"}
 :remove-containers-on-shutdown {:dev true, :test false, :prod false}
 :html-body-colors "bg-white"}
```

where:

- `:cgi-timeout` is the maximum number of milliseconds that a CGI script is allowed to run.
- `:docker-config` is the docker configuration. A default docker configuration for DROID needs to be specified, and optionally a docker configuration can be defined for specific projects. If one is not defined for a project, then the default configuration is uesd. Whether at the default or project-level, the docker configuration should specify: (1) `:disabled?`: whether or not to actually use docker's container service when running commands in the project's branches (note that setting this to true in the default configuration only disables docker for projects that have no docker configuration of their own); (2) `:image`: the name of the docker image to use; (3) `:workspace-dir`: the directory to map the server's local workspace directory for a branch to in the container; (4) `:temp-dir`: the directory to map the server's local temp directory for branch to in the container; (5) `:default-working-dir`: the directory relative to which DROID commands should be run by default within the container, if not otherwise specified; (6) `:shell-command`: the program name of the shell to be used when running commands; (7) `:env`: extra environment variables to pass to a container when invoking it.
- `:env` specifies the environment variables to use with all commands that run in the given project.
- `:github-app-id` is the ID of the GitHub App to use for authentication
- `:github-coordinates` is used to lookup the location of the project in github, i.e., https://github.com/GITHUB_COORDINATES.
- `:html-body-colors` is a valid [bootstrap background colour](https://getbootstrap.com/docs/4.1/utilities/colors/#background-color) to use for DROID's pages.
- `:log-file` is the file (relative to DROID's root directory) where the log will be written to. If it is nil then log is written to `STDERR`.
- `:log-level` should be one of `:debug`, `:info`, `:warn`, `:error`, `:fatal`. The higher the specified level, the fewer messages will be written to the log.
- `:local-mode`, if set to true, will instruct DROID to authenticate to GitHub using a personal access token read from the environment variable PERSONAL_ACCESS_TOKEN. Note that if this environment variable is not set, then setting `:local-mode` to true will have no effect.
- `:makefile-path` is the complete path of the makefile for which all workflow actions, views, and scripts will be assumed by DROID to be relative to.
- `:op-env` should be one of `:dev`, `:test`, `:prod`
  - If `:op-env` is defined as (for example) `:dev`, then the `:dev` key will be used when looking up other configuration parameters that provide alternate configurations for `:dev`, `:test`, and `:prod`.
- `:pem-file` is the file, relative to DROID's root directory, containing the private key to use for authenticating with the GitHub App
- `:project-description` is displayed below the banner on the main project page.
- `:project-title` is displayed on the browser's window frame and in the banner whenever the browser is on one of the project's pages.
- `:project-welcome` is currently unused.
- `:push-with-installation-token` If set to true, then pushes to GitHub will use an installation token provided through the GitHub App for the repo. Otherwise pushes will use the user's user access token (which is also authenticated via the GitHub App). Note that if `:local-mode` is set to true, the personal access token will be used in lieu of the installation token.
- `:remove-containers-on-shutdown` set this to true if you would like to clean up docker containers whenever the server shuts down. Note that even without this flag set, docker containers will be *paused* on server shutdown.
- `:secure-site` is either true or false and indicates whether the server will use the `https://` or `http://` protocol.
- `:server-port` is the port that the server will listen on.
- `:site-admin-github-ids` is a hash-set of github userids who are considered site administrators.

## `projects/` directory

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

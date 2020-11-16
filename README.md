# DROID Reminds us that Ordinary Individuals can be Developers

[![Build Status](https://travis-ci.org/ontodev/droid.svg?branch=master)](https://travis-ci.org/ontodev/droid)

DROID is a web-based interface for working with (1) a build system, managed by (2) a version control system. The current version of DROID is designed to work with (1) GNU Make and (2) GitHub. Our goal is to make these systems accessible to a wider community of project contributors, by exposing a curated set of functionality that is customized for each project.

DROID differs from Continuous Testing/Integration solutions such as Jenkins or Travis CI because DROID allows users to modify a working copy of a branch and run tasks on an ad-hoc basis before committing changes. DROID differs from Web/Cloud IDEs because users are limited to a specified set of files and tasks.

DROID is in early development and is designed to work on Unix (Linux, macOS) systems.

## Python virtual environment and `ansi2html`

DROID is implemented using [Clojure](https://clojure.org/), but uses the Python library [ansi2html](https://pypi.org/project/ansi2html/) to colourize console output.<sup>*</sup> For this to work you must set up a python virtual environment in DROID's root directory and install the dependencies specified in `requirements.txt` in the same directory. Once this is done, DROID should be run only after activating the virtual environment.

<i><sup>*Note: as of 20-10-2020 we have investigated a few native java/clojure alternatives to using `ansi2html` but have not found anything nearly as performant. One possibility that still needs to be tried is `jansi` (see: https://github.com/ontodev/droid/issues/39)</sup></i>

1. Create the virtual environment:

   ```
   cd <droid-root>
   python3 -m venv _venv
   ```

2. Activate the virtual environment:

   ```
   source _venv/bin/activate
   ```

You should now see the string "`(_venv)`" prefixed to your command-line prompt, indicating that you have successfully activated the virtual environment.

3. Install the dependencies in `requirements.txt`:

   ```
   pip install -r requirements.txt
   ```

Steps 1 and 3 only need to be run once at installation time. Step 2, activating the Python virtual environment, is required in order for colourization to work. You must make sure that the virtual environment is active (as indicated by the string "`(_venv)`" at the beginning of your command prompt) before starting DROID.

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
              :env {"ENV_VAR" "env_var_val"}
              :docker-config {:active? true
                              :image "debian"
                              :workspace-dir "/workspace/"
                              :temp-dir "/tmp/droid/"
                              :default-working-dir "/workspace/"
                              :shell-command "bash"
                              :env {"ENV_VAR" "env_var_value"}}}
  "project2" {:project-title "PROJECT2"
              :project-welcome "welcome message"
              :project-description "description"
              :github-coordinates "github-org/repository-2"}}
 :op-env :dev
 :server-port {:dev 8000 :test 8001 :prod 8002}
 :log-level {:dev :info :test :info :prod :warn}
 :secure-site {:dev true :test true :prod true}
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

- `:docker-config` is optional. If included it must specify: (1) `:active?`: whether or not to actually use docker's container service when running commands in the project's branches; (2) `:image`: the name of the docker image to use; (3) `:workspace-dir`: the directory to map the server's local workspace directory for a branch to in the container; (4) `:temp-dir`: the directory to map the server's local temp directory for branch to in the container; (5) `:default-working-dir`: the directory relative to which DROID commands should be run by default within the container, if not otherwise specified; (6) `:shell-command`: the program name of the shell to be used when running commands; (7) `:env`: extra environment variables to pass to a container when invoking it.
- `:op-env` should be one of `:dev`, `:test`, `:prod`
  - If `:op-env` is defined as (for example) `:dev`, then the `:dev` key will be used when looking up other configuration parameters that provide alternate configurations for `:dev`, `:test`, and `:prod`.
- `:log-level` should be one of `:debug`, `:info`, `:warn`, `:error`, `:fatal`. The higher the specified level, the fewer messages will be written to the log.
- `:env` specifies the environment variables to use with all commands that run in the given project.
- `:server-port` is the port that the server will listen on.
- `:secure-site` is either true or false and indicates whether the server will use the `https://` or `http://` protocol.
- `:site-admin-github-ids` is a hash-set of github userids who are considered site administrators.
- `:github-app-id` is the ID of the GitHub App to use for authentication
- `:pem-file` is the file, relative to DROID's root directory, containing the private key to use for authenticating with the GitHub App
- `:push-with-installation-token` If set to true, then pushes to GitHub will use an installation token provided through the GitHub App for the repo. Otherwise pushes will use the user's user access token (which is also authenticated via the GitHub App).
- `:cgi-timeout` is the maximum number of milliseconds that a CGI script is allowed to run.
- `:log-file` is the file (relative to DROID's root directory) where the log will be written to. If it is nil then log is written to `STDERR`.
- `:remove-containers-on-shutdown` set this to true if you would like to clean up docker containers whenever the server shuts down. Note that even without this flag set, docker containers will be *paused* on server shutdown.
- `:html-body-colors` is a valid [bootstrap background colour](https://getbootstrap.com/docs/4.1/utilities/colors/#background-color) to use for DROID's pages.

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

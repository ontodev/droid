# DROID Reminds us that Ordinary Individuals can be Developers

[![Build Status](https://travis-ci.org/ontodev/droid.svg?branch=master)](https://travis-ci.org/ontodev/droid)

DROID is a web-based interface for working with (1) a build system, managed by (2) a version control system. The current version of DROID is designed to work with (1) GNU Make and (2) GitHub. Our goal is to make these systems accessible to a wider community of project contributors, by exposing a curated set of functionality that is customized for each project.

DROID differs from Continuous Testing/Integration solutions such as Jenkins or Travis CI because DROID allows users to modify a working copy of a branch and run tasks on an ad-hoc basis before committing changes. DROID differs from Web/Cloud IDEs because users are limited to a specified set of files and tasks.

DROID is in early development and is designed to work on Unix (Linux, macOS) systems.

## Python virtual environment and `ansi2html`

DROID is implemented using [Clojure](https://clojure.org/), but uses the Python library [ansi2html](https://pypi.org/project/ansi2html/) to colourize console output. For this to work you must set up a python virtual environment in DROID's root directory and install the dependencies specified in `requirements.txt` in the same directory. Once this is done, DROID should be run only after activating the virtual environment.

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

## GitHub environment variables

DROID assumes that the following environment variables have been set. It may be convenient to add them to your python virtual environment activation script (`_venv/bin/activate`).

### OAuth2

For OAuth2 integration to work properly, DROID assumes that the following environment variables have been set:
- GITHUB_CLIENT_ID
- GITHUB_CLIENT_SECRET

These should match the client id and secret of your GitHub OAuth2 app.

### Default committer

DROID modifies the "author" of a commit to match the info for the authenticated user who requested it. The "committer" info should be changed as well, but in this case we do it globally in environment variables:

export GIT_COMMITTER_NAME="DROID"
export GIT_COMMITTER_EMAIL=""


## Configuration file

DROID assumes that a file called 'config.edn' exists in DROID's root directory with the following contents:

```
{:projects
 {"project1" {:project-title "PROJECT1"
              :project-welcome "welcome message" 
              :project-description "description"
              :github-coordinates "github-org/repository-1"}
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
 :cgi-timeout 60000
 :log-file "droid.log"
 :html-body-colors "bg-white"}
```

where:

- `:op-env` should be one of `:dev`, `:test`, `:prod`
  - If `:op-env` is defined as (for example) `:dev`, then the `:dev` key will be used when looking up all of the other configuration parameters.
- `:log-level` should be one of `:debug`, `:info`, `:warn`, `:error`, `:fatal`
- `:server-port` is the port that the server will listen on.
- `:secure-site` is either true or false and indicates whether the server will use https or http.
- `:site-admin-github-ids` is a hash-set of github userids who are considered site administrators.
- `:cgi-timeout` is the maximum number of milliseconds that a CGI script is allowed to run.
- `:log-file` is the file (relative to DROID's root directory) where the log will be written to. If it is nil then log is written to STDERR.
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

The `project1/`, `project2/`, etc. directories should be writable by `DROID`.
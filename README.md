# DROID Reminds us that Ordinary Individuals can be Developers

[![Build Status](https://travis-ci.org/ontodev/droid.svg?branch=master)](https://travis-ci.org/ontodev/droid)

DROID is a web-based interface for working with (1) a build system, managed by (2) a version control system. The current version of DROID is designed to work with (1) GNU Make and (2) GitHub. Our goal is to make these systems accessible to a wider community of project contributors, by exposing a curated set of functionality that is customized for each project.

DROID differs from Continuous Testing/Integration solutions such as Jenkins or Travis CI because DROID allows users to modify a working copy of a branch and run tasks on an ad-hoc basis before committing changes. DROID differs from Web/Cloud IDEs because users are limited to a specified set of files and tasks.

DROID is in early development and is designed to work on Unix (Linux, macOS) systems.

## GitHub environment variables

For OAuth2 integration to work properly, DROID assumes that the following environment variables have been set:
- GITHUB_CLIENT_ID
- GITHUB_CLIENT_SECRET

These should match the client id and secret of your GitHub OAuth2 app.


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
 :authorized-github-ids
 {:dev #{"user1" "user2"}
  :test #{"user1" "user2"}
  :prod #{"user1" "user2"}}}
```

where:

- `:op-env` should be one of `:dev`, `:test`, `:prod`
- `:log-level` should be one of `:debug`, `:info`, `:warn`, `:error`, `:fatal`
- `:server-port` is the port that the server will listen on.

If `:op-env` is defined as (for example) `:dev`, then the `:dev` key will be used when looking up all of the other configuration parameters.

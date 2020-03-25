# DROID Reminds us that Ordinary Individuals can be Developers

[![Build Status](https://travis-ci.org/ontodev/droid.svg?branch=master)](https://travis-ci.org/ontodev/droid)

DROID is a web-based interface for working with (1) a build system, managed by (2) a version control system. The current version of DROID is designed to work with (1) GNU Make and (2) GitHub. Our goal is to make these systems accessible to a wider community of project contributors, by exposing a curated set of functionality that is customized for each project.

DROID differs from Continuous Testing/Integration solutions such as Jenkins or Travis CI because DROID allows users to modify a working copy of a branch and run tasks on an ad-hoc basis before committing changes. DROID differs from Web/Cloud IDEs because users are limited to a specified set of files and tasks.

DROID is in early development and is designed to work on Unix (Linux, macOS) systems.

## Configuration file

DROID assumes that a file called 'config.edn' exists in DROID's root directory with the following contents:

```
  {:op-env <server environment>

  :server-port {:dev <port>
                :test <port>
                :prod <port>}
 
  :log-level {:dev <debug mode>
              :test <debug mode>
              :prod <debug mode>}

  :secure-site {:dev <false|true>
                :test <false|true>
                :prod <false|true>}

  :authorized-github-ids {:dev #{<list of quoted github user ids that are authorized>}
                         :test #{<list of quoted github user ids that are authorized>}
                         :prod #{<list of quoted github user ids that are authorized>}}

 :project-name <quoted name of the project>
 :project-description <quoted description of the project>}

```

where:
- `<server environment>` should be one of `:dev`, `:test`, `:prod`
- `<debug mode>` should be one of `:debug`, `:info`, `:warn`, `:error`, `:fatal`
- `<port>` is the port that the server will listen on.

The way that `:op-env` works is that, if `:op-env` is defined as (for example) `:dev`, then the `:dev` key will be used when looking up all of the other configuration parameters.
(ns droid.cli-handler
  (:require [droid.config-helper :refer [init-config dump-config]]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]))

(def usage "Usage: droid [OPTIONS]
  Options:
  -c          --check-config
  -d          --dump-config
  -i          --init-config [--port=PORT] \\
                            [--site-admins=userid1,userid2,userid3] \\
                            [--enable-fallback-docker=(true|false)] \\
                            [--fallback-docker-image=IMAGE] \\
                            [--local-mode=(true|false)] \\
                            [--github-app-id=APP_ID] \\
                            [--pem-file=FILENAME] \\
                            [--project-github-coords=COORDS] \\
                            [--enable-project-docker=(true|false)] \\
                            [--project-docker-image=IMAGE]
  -v          --version     Display DROID's version number and exit.
  -h          --help        Display this help message and exit")

(def version
  (str "DROID Version "
       (->> "project.clj" slurp read-string (drop 2) (cons :version) (apply hash-map) :version)))

(defn- add-spaces
  [n]
  (->> #(str " ") (repeatedly n) (string/join)))

(defn- parse-bool
  [arg]
  (if (->> arg (string/lower-case) (string/trim) (re-matches #"(true|false)"))
    (= arg "true")
    (throw (IllegalArgumentException. "Allowed values are 'true' and 'false'"))))

(def cli-options
  [["-c" "--check-config"
    "Check the validity of the configuration file and exit"]

   ["-d" "--dump-config"
    "Output current configuration parameters to STDOUT and exit"]

   ["-i" "--init-config"
    (str "Initialize a new configuration file based on the default\n"
         (add-spaces 38) "configuration. If all of the optional command line\n"
         (add-spaces 38) "parameters: --port, --site-admins,\n"
         (add-spaces 38) "--enable-fallback-docker, --fallback-docker-image,\n"
         (add-spaces 38) "--local-mode, --github-app-id, --pem-file,\n"
         (add-spaces 38) "--project-github-coords, --enable-project-docker, and\n"
         (add-spaces 38) "--project-docker-image are specified, use these values for\n"
         (add-spaces 38) "the new configuration. If any of these are not supplied,\n"
         (add-spaces 38) "ask the user for this information interactively")]

   [nil "--port PORT"
    (str "The port that the server will listen on. (Ignored unless\n"
         (add-spaces 38) "--init-config is also given.)")
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Port must be a number between 0 and 65536"]]

   [nil "--site-admins USERIDS"
    (str "A comma-separated list of GitHub userids who should be\n"
         (add-spaces 38) "considered site administrators (ignored unless --init-config\n"
         (add-spaces 38) "is also given)")
    :validate [#(re-matches #"^\s*[\w\.\_\-]+(\s*,\s*[\w\.\_\-]+)*" %)
               "Site admins must be specified as a comma-separated list"]]

   [nil "--enable-fallback-docker BOOL"
    (str "Enable a fallback docker configuration for projects that do\n"
         (add-spaces 38) "not define their own (ignored unless --init-config is also\n"
         (add-spaces 38) "given)")
    :parse-fn parse-bool]

   [nil "--fallback-docker-image IMAGE"
    (str "The docker image to use in the fallback docker configuration\n"
         (add-spaces 38) "(ignored unless --init-config is also given)")
    :validate [#(re-matches #"^[\w\:\/\.\_\-]+$" %) "Invalid image name"]]

   [nil "--local-mode BOOL"
    (str "Configure DROID to run in local mode (ignored unless\n"
         (add-spaces 38) "--init-config is also given)")
    :parse-fn parse-bool]

   [nil "--github-app-id APP_ID"
    (str "The GitHub App ID used for authentication (not used in local\n"
         (add-spaces 38) "mode; ignored unless --init-config is also given)")
    :parse-fn #(Integer/parseInt %)]

   [nil "--pem-file FILENAME"
    (str "The name of the private key file to use for authentication\n"
         (add-spaces 38) "(not used in local mode; ignored unless --init-config is also\n"
         (add-spaces 38) "given)")
    :validate [#(re-matches #"^[\w\/\.\_\-]+$" %) (str "Invalid PEM filename. No spaces or special "
                                                       "characters other than '/', '.', '_', '-' "
                                                       "are allowed")]]

   [nil "--project-github-coords COORDS"
    (str "The project's address in GitHub, of the form:\n"
         (add-spaces 38) "'<org or owner>/<repository name>'\n"
         (add-spaces 38) "(ignored unless --init-config is also given)")
    :validate [#(re-matches #"^[\w\.\_\-]+/[\w\.\_\-]+$" %) "Invalid GitHub coordinates"]]

   [nil "--enable-project-docker BOOL"
    (str "Enable docker for the project to be configured (ignored unless\n"
         (add-spaces 38) "--init-config is also given)")
    :parse-fn parse-bool]

   [nil "--project-docker-image IMAGE"
    (str "The docker image to use for the configured project (ignored\n"
         (add-spaces 38) "unless --init-config is also given)")
    :validate [#(re-matches #"^[\w\:\/\.\_\-]+$" %) "Invalid image name"]]

   ["-v" "--version" "Display DROID's version number and exit"]

   ["-h" "--help" "Display this help message and exit"]])

(defn handle-cli-opts
  "Parses command-line arguments to DROID and acts accordingly."
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (when (not (empty? arguments))
      (binding [*out* *err*]
        (->> arguments (string/join ", ") (println "The following arguments were not processed:"))))

    (cond
      errors
      (binding [*out* *err*]
        (doseq [error errors]
          (println error))
        (println usage)
        (System/exit 1))

      (:help options)
      (do (println usage)
          (println "\nWhere:")
          (println summary)
          (System/exit 0))

      (:version options)
      (do (println version)
          (System/exit 0))

      ;; We don't need to explicitly call the check-config function here since it is automatically
      ;; called when the config module is loaded at statup.
      (:check-config options)
      (do (println "Configuration OK")
          (System/exit 0))

      (:dump-config options)
      (do (dump-config)
          (System/exit 0))

      (:init-config options)
      (do (init-config options)
          (System/exit 0)))))

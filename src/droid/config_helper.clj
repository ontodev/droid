(ns droid.config-helper
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as string]
            [droid.config :refer [get-config get-docker-config get-default-config notify fail]]
            [droid.log :as log]))

(defn- check-explicit-config
  "Runs a series of tests on the explicitly defined configuration parameters."
  []
  (let [root-constraints
        {:cgi-timeout {:allowed-types [Long]}
         :docker-config {:required-always? true, :allowed-types [clojure.lang.PersistentArrayMap
                                                                 clojure.lang.PersistentHashMap]}
         :github-app-id {:required-when {:local-mode false}, :allowed-types [Long]}
         :html-body-colors {:allowed-types [String]}
         :local-mode {:allowed-types [Boolean]}
         :log-file {:allowed-types [String]}
         :log-level {:required-always? true, :allowed-types [clojure.lang.Keyword]}
         :pem-file {:required-when {:local-mode false}, :allowed-types [String]}
         :projects {:required-always? true, :allowed-types [clojure.lang.PersistentArrayMap
                                                            clojure.lang.PersistentHashMap]}
         :push-with-installation-token {:allowed-types [Boolean]}
         :remove-containers-on-shutdown {:allowed-types [Boolean]}
         :insecure-site {:allowed-types [Boolean]}
         :server-port {:required-always? true, :allowed-types [Long]}
         :site-admin-github-ids {:allowed-types [clojure.lang.PersistentHashSet]}}

        project-constraints
        {:project-title {:required-always? true, :allowed-types [String]}
         :project-welcome {:allowed-types [String]}
         :project-description {:required-always? true, :allowed-types [String]}
         :github-coordinates {:required-always? true, :allowed-types [String]}
         :makefile-path {:allowed-types [String]}
         :env {:allowed-types [clojure.lang.PersistentArrayMap, clojure.lang.PersistentHashMap]}
         :docker-config {:allowed-types [clojure.lang.PersistentArrayMap,
                                         clojure.lang.PersistentHashMap]}}

        docker-constraints
        {:disabled? {:allowed-types [Boolean]}
         :image {:required-when {:disabled? false}, :allowed-types [String]}
         :workspace-dir {:required-when {:disabled? false}, :allowed-types [String]}
         :temp-dir {:required-when {:disabled? false}, :allowed-types [String]}
         :default-working-dir {:required-when {:disabled? false}, :allowed-types [String]}
         :shell-command {:required-when {:disabled? false}, :allowed-types [String]}
         :env {:allowed-types [clojure.lang.PersistentArrayMap,
                               clojure.lang.PersistentHashMap]}}

        is-required?
        (fn [constraint-key constraints actual-config]
          "Returns true if (1) the parameter is always required, or (2) the parameter is
          conditionally required, and the conditions hold"
          (or (->> (constraint-key constraints) :required-always?)
              (->> (constraint-key constraints)
                   :required-when
                   ;; convert the hash-map into a sequence of [key value] pairs:
                   (seq)
                   ;; For each [key value] pair, check if the value required by the constraint
                   ;; matches the one in the config map. The result will be a sequence of Booleans.
                   ;; If every constraint is satisfied, the sequence will look like '(true true ...)
                   (map #(= (->> (first %) (get actual-config) (boolean))
                            (->> (second %) (boolean))))
                   ((fn [boolean-seq] (and (not (empty? boolean-seq))
                                           (every? #(= % true) boolean-seq)))))))

        crosscheck-config-constraints
        (fn [config-to-check constraints]
          "Checks the given config against the given constraints, and then checks to see if every
           parameter required by the constraints is in the config."
          (let [config-keys (keys config-to-check)
                constraint-keys (keys constraints)]
            ;; Check that each config parameter satisfies the above constraints:
            (doseq [config-key config-keys]
              (let [param-type (-> config-key (get-config config-to-check) (type))
                    allowed-types (-> (config-key constraints) (get :allowed-types))]
                ;; Only validate the config-key if it is present (if not, param-type will be nil):
                (when param-type
                  (cond (= allowed-types nil)
                        (notify "Warning: Ignoring unexpected parameter:" config-key)
                        (not-any? #(= param-type %) allowed-types)
                        (fail "Configuration error: Configuration parameter" config-key
                              "has invalid type:" param-type
                              "(valid types are:" allowed-types ")")))))
            ;; Check whether any configuration parameters are missing:
            (doseq [constraint-key constraint-keys]
              (when (and (is-required? constraint-key constraints config-to-check)
                         (->> constraint-key (get config-to-check) (nil?)))
                (fail "Configuration error: Missing required configuration parameter:"
                      constraint-key (let [conditional-clause (-> (constraint-key constraints)
                                                                  :required-when)]
                                       (when conditional-clause
                                         (str " (required when: " conditional-clause ")"))))))))]

    (let [explicit-config (-> (if (-> (io/file "config.edn") (.exists))
                                "config.edn"
                                "example-config.edn")
                              (slurp)
                              (clojure.edn/read-string))]
      ;; Check root-level configuration:
      (log/info "Checking root-level configuration ...")
      (crosscheck-config-constraints explicit-config root-constraints)
      ;; Check root-level docker-configuration:
      (let [docker-config (get-config :docker-config explicit-config)]
        (log/info "Checking root-level docker configuration ...")
        (crosscheck-config-constraints docker-config docker-constraints))
      ;; Check project-level configuration:
      (let [project-configs (-> (get-config :projects explicit-config) (seq))]
        (doseq [project-keyval project-configs]
          (let [project-name (first project-keyval)
                project-config (second project-keyval)
                docker-config (get-docker-config project-name project-config)]
            (log/info "Checking configuration for project" project-name "...")
            (when (-> (type project-name) (= String) (not))
              (fail "Configuration error: Project identifier:" project-name
                    "should be of type String"))
            (crosscheck-config-constraints project-config project-constraints)
            ;; Check the project-level docker-config:
            (when docker-config
              (log/info "Checking docker configuration for project" project-name "...")
              (crosscheck-config-constraints docker-config docker-constraints))))))))

(defn- config-questionnaire
  "Interactively accept answers to a series of questions and return the configuration map formed
  by applying the answers received to the default configuration."
  [options]
  (letfn [(ask [question & [default]]
            ;; Asks a question, indicating the default that will be assumed if no answer is given,
            ;; and wait for the user to supply the answer
            (println)
            (println question)
            (cond (= (type default) Boolean)
                  (if default
                    (println "[Default: Y]")
                    (println "[Default: N]"))

                  (= (type default) clojure.lang.PersistentHashSet)
                  (if (= default #{})
                    (println "[Default: None]")
                    default)

                  (not (nil? default))
                  (-> "[Default: " (str default "]") (println)))
            (print "> ")
            (flush)
            (-> (read-line)
                (#(try
                    (string/trim %)
                    ;; If the user supplied no input (e.g., by pressing Ctrl-D), then triming the
                    ;; answer will generate a null pointer, which we can safely ignore here, exiting
                    ;; the program:
                    (catch java.lang.NullPointerException e
                      (System/exit 1))))))

          (get-server-port []
            (let [default (get-default-config :server-port)
                  ask-for-server-port #(ask "Enter the port # that the server will listen on."
                                            default)]
              (loop [answer (ask-for-server-port)]
                (let [server-port (if (and (not (nil? answer)) (empty? answer))
                                    default
                                    (try
                                      (Integer/parseInt answer)
                                      (catch java.lang.NumberFormatException e
                                        (println answer "is not a valid port #. Try again.")
                                        nil)))]
                  (or server-port
                      (recur (ask-for-server-port)))))))

          (get-insecure-site []
            (let [default (get-default-config :insecure-site)
                  use-secure-site? #(ask "Use HTTPS? (y/n)" (not default))]
              (loop [answer (use-secure-site?)]
                (let [insecure-site? (if (and (not (nil? answer)) (empty? answer))
                                       default
                                       (cond (-> answer (string/lower-case) (= "y")) false
                                             (-> answer (string/lower-case) (= "n")) true))]
                  (if-not (nil? insecure-site?)
                    insecure-site?
                    (do (println "Please enter 'y' or 'n'")
                        (recur (use-secure-site?))))))))

          (get-site-admins []
            (let [default (get-default-config :site-admin-github-ids)
                  ask-for-site-admins #(ask (str "Enter a comma-separated list of GitHub userids "
                                                 "that DROID should consider to be\nsite "
                                                 "administrators.")
                                            default)]
              (loop [answer (ask-for-site-admins)]
                (let [site-admins (if (and (not (nil? answer)) (empty? answer))
                                    default
                                    (when (re-matches #"^\s*[\w\.\_\-]+(\s*,\s*[\w\.\_\-]+)*"
                                                      answer)
                                      (->> #"\s*,\s*" (string/split answer) (into #{}))))]
                  (if site-admins
                    site-admins
                    (do (println answer "is not a comma-separated list of userids")
                        (recur (ask-for-site-admins))))))))

          (get-local-mode []
            (let [default (get-default-config :local-mode)
                  use-local-mode? #(ask
                                    (str "DROID can run in local mode, in which case you will need "
                                         "to set the environment\nvariable PERSONAL_ACCESS_TOKEN "
                                         "to the value of the GitHub personal access token\n(PAT) "
                                         "that will be used for authentication. A PAT is "
                                         "associated with a single\nuser, who will own all GitHub "
                                         "actions performed by DROID. Alternately, DROID\ncan run "
                                         "in server mode, in which case you will be asked to "
                                         "supply a GitHub App\nID and PEM file to use for "
                                         "authentication to GitHub on behalf of the currently\n"
                                         "logged in user.\nShould DROID run in local mode?")
                                    default)]
              (loop [answer (use-local-mode?)]
                (let [local-mode? (if (and (not (nil? answer)) (empty? answer))
                                    default
                                    (cond (-> answer (string/lower-case) (= "y")) true
                                          (-> answer (string/lower-case) (= "n")) false))]
                  (if-not (nil? local-mode?)
                    local-mode?
                    (do (println "Please enter 'y' or 'n'")
                        (recur (use-local-mode?))))))))

          (get-github-app-id []
            (let [ask-for-app-id #(ask "Enter the GitHub App ID to use for authentication.")]
              (loop [answer (ask-for-app-id)]
                (let [app-id (try (Integer/parseInt answer)
                                  (catch java.lang.NumberFormatException e
                                    (println answer "is not a valid GitHub App ID. Try again.")
                                    nil))]
                  (or app-id
                      (recur (ask-for-app-id)))))))

          (get-pem-file []
            (let [ask-for-pem-file #(ask
                                     (str "Enter the name of the .pem file "
                                          (str "(relative to " (-> (java.io.File. "")
                                                                   .getAbsolutePath) ")")
                                          "\nto use for GitHub App authentication."))]
              (loop [answer (ask-for-pem-file)]
                (let [pem-file (when (->> answer (re-matches #"^[\w\/\.\_\-]+$"))
                                 answer)]
                  (or pem-file
                      (do
                        (println "Please enter a valid filename (no spaces or special characters).")
                        (recur (ask-for-pem-file))))))))

          (get-github-coords []
            (let [ask-for-coords #(ask (str "Enter the github coordinates for the new project. "
                                            "The is the part of the URL\nfor the project "
                                            "repository that comes after github.com/,e.g.,\n"
                                            "https://github.com/GITHUB_COORDINATES, where "
                                            "GITHUB_COORDINATES is of the\nform: "
                                            "'<org or owner>/<repository name>' and contains "
                                            "no spaces or special\ncharacters other than '/', '_',"
                                            "'-'."))]
              (loop [answer (ask-for-coords)]
                (let [coords (when (->> answer (re-matches #"^[\w\.\_\-]+/[\w\.\_\-]+$"))
                               answer)]
                  (or coords
                      (do (println "Coordinates must be in the form:"
                                   "'<org or owner>/<repository name>' and should\nnot contain any"
                                   "special characters other than '/', '_', and '-'.")
                          (recur (ask-for-coords))))))))

          (get-project-docker-disabled? [project-name]
            (let [default (-> (get-default-config :projects) (get "project1")
                              :docker-config :disabled?)
                  enable-docker? #(ask (str "Do you want to enable docker for: " project-name
                                            "? (y/n)")
                                       (not default))]
              (loop [answer (enable-docker?)]
                (let [docker-disabled? (if (and (not (nil? answer)) (empty? answer))
                                         default
                                         (cond (-> answer (string/lower-case) (= "y")) false
                                               (-> answer (string/lower-case) (= "n")) true))]
                  (if-not (nil? docker-disabled?)
                    docker-disabled?
                    (do (println "Please enter 'y' or 'n'")
                        (recur (enable-docker?))))))))

          (get-project-docker-image [project-name]
            (let [default (-> (get-default-config :projects) (get "project1") :docker-config :image)
                  get-image #(ask (str "Enter the docker image to use when creating containers for "
                                       "project: " project-name)
                                  default)]
              (loop [answer (get-image)]
                (let [docker-image (if (and (not (nil? answer)) (empty? answer))
                                     default
                                     (when (->> answer (re-matches #"^[\w\:\/\.\_\-]+$"))
                                       answer))]
                  (if-not (nil? docker-image)
                    docker-image
                    (do (println "Please enter a valid image name")
                        (recur (get-image))))))))

          (arg-or-func [arg func]
            (if-not (nil? arg)
              arg
              (func)))

          (get-projects [{:keys [project-github-coords enable-project-docker project-docker-image]}]
            ;; Returns a list of two-place vectors representing key-value pairs in the projects map.
            (if (or project-github-coords enable-project-docker project-docker-image)
              ;; If one or more of the parameters specified on the command line is project-related,
              ;; configure one (and only) one project, asking the user to supply any of the missing
              ;; project parameters:
              (let [github-coords (arg-or-func project-github-coords get-github-coords)
                    project-name (-> github-coords (string/replace #"[^\w]" "-"))
                    docker-disabled? (arg-or-func (when-not (nil? enable-project-docker)
                                                    (not enable-project-docker))
                                                  #(get-project-docker-disabled? project-name))
                    docker-image (when-not docker-disabled?
                                   (arg-or-func project-docker-image
                                                #(get-project-docker-image project-name)))]
                {project-name {:project-title "PROJECT"
                               :project-welcome "welcome message"
                               :project-description "description"
                               :github-coordinates github-coords
                               :docker-config {:disabled? docker-disabled?
                                               :image (if-not docker-disabled?
                                                        docker-image
                                                        (-> :docker-config (get-default-config)
                                                            :image))
                                               :workspace-dir "/workspace/"
                                               :temp-dir "/tmp/droid/"
                                               :default-working-dir "/workspace/"
                                               :shell-command "sh"
                                               :env {}}}})
              ;; Otherwise if no project parameters have been specified, allow the user to configure
              ;; multiple projects interactively:
              (let [one-more-project? #(ask "Configure a new project? (y/n)" false)]
                (loop [answer (one-more-project?)
                       project-list '()]
                  (let [new-project? (if (or (not answer) (empty? answer))
                                       false
                                       (cond (-> answer (string/lower-case) (= "y")) true
                                             (-> answer (string/lower-case) (= "n")) false))]
                    (cond
                      ;; If a new project is to be configured, ask a series of questions about it:
                      new-project?
                      (let [github-coords (get-github-coords)
                            project-name (-> github-coords (string/replace #"[^\w]" "-"))
                            docker-disabled? (get-project-docker-disabled? project-name)
                            docker-image (when-not docker-disabled?
                                           (get-project-docker-image project-name))]
                        (->> (vector project-name
                                     {:github-coordinates github-coords
                                      :docker-config (-> (hash-map :disabled? docker-disabled?)
                                                         (merge
                                                          (when-not docker-disabled?
                                                            (hash-map :image docker-image))))})
                             (conj project-list)
                             (recur (one-more-project?))))

                      (nil? new-project?)
                      (do (println "Please enter 'y' or 'n'")
                          (recur (one-more-project?) project-list))

                      ;; We are done; return the generated project list:
                      :else
                      project-list))))))]

    (let [{:keys [port site-admins local-mode github-app-id pem-file project-github-coords
                  enable-project-docker project-docker-image insecure-site]} options
          local-mode? (arg-or-func local-mode get-local-mode)
          github-app-id (when-not local-mode?
                          (arg-or-func github-app-id get-github-app-id))
          pem-file (when-not local-mode?
                     (arg-or-func pem-file get-pem-file))
          site-admins (when-not local-mode?
                        (arg-or-func site-admins get-site-admins))
          insecure-site? (arg-or-func insecure-site get-insecure-site)
          server-port (arg-or-func port get-server-port)
          projects (get-projects options)

          default-docker-config (-> (get-default-config) :docker-config)
          default-project-config (-> (get-default-config) :projects (get "project1"))]

      (-> (get-default-config)
          (merge {:server-port server-port
                  :insecure-site insecure-site?
                  :local-mode local-mode?})
          (merge (when-not local-mode?
                   {:github-app-id github-app-id
                    :pem-file pem-file
                    :site-admin-github-ids site-admins}))
          (assoc :projects
                 (->> projects
                      (seq)
                      (map (fn [map-entry]
                             (hash-map (first map-entry)
                                       (-> (second map-entry)
                                           (assoc :docker-config
                                                  (->> (second map-entry)
                                                       :docker-config
                                                       (merge default-docker-config)))
                                           (#(merge default-project-config %))))))
                      (apply merge)
                      (#(or % {"project1" default-project-config}))))))))

(defn init-config
  "Generate a new customized config.edn file based on user input."
  ([options]
   (if (-> "config.edn" (io/file) (.exists))
     (do
       (println "A file called config.edn already exists in DROID's root directory. This file"
                "\nmust be either moved or removed before initializing a new configuration.")
       (System/exit 1)))
   (let [config-answers (config-questionnaire options)]
     (spit "config.edn" "")
     ;; First extract all of the comments from the example configuration file and write them to
     ;; STDOUT. These comments should contain useful documentation on the configuration parameters
     ;; that will be written to config.edn..
     (with-open [rdr (io/reader "example-config.edn")]
       (doseq [line (->> rdr (line-seq) (map string/trim))]
         (when (->> line (re-matches #"^;;(\s+.*)*$"))
           (spit "config.edn" (str line "\n") :append true))))
     ;; Now write the custom configuration map to config.edn:
     (spit "config.edn" "\n" :append true)
     (->> (io/writer "config.edn" :append true) (pprint config-answers))
     (println "New configuration written to config.edn. You should now edit this file in your"
              "\nfavorite editor to further specify configuration parameters like project"
              "\ntitles, custom makefile paths, etc.")))
  ([]
   (init-config {})))

(defn dump-config
  "Dumps the actually configured parameters being used by DROID."
  []
  ;; First extract all of the comments from the example configuration file and write them to STDOUT.
  ;; These comments should contain useful documentation on the configuration parameters that are to
  ;; be dumped.
  (with-open [rdr (io/reader "example-config.edn")]
    (doseq [line (->> rdr (line-seq) (map string/trim))]
      (when (->> line (re-matches #"^;;(\s+.*)*$"))
        (println line))))
  ;; Now dump the configuration map:
  (println)
  (-> (get-config) (pprint)))

;; Validate the configuration map at startup:
(check-explicit-config)

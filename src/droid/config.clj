(ns droid.config
  (:require [clojure.java.io :as io]
            [clojure.string :as string]))

(def config
  "A map of configuration parameters, loaded from the config.edn file in the data directory.
  If that file does not exist, use the example-config.edn file as a fallback."
  (let [config-filename (if (-> (io/file "config.edn") (.exists))
                          "config.edn"
                          (binding [*out* *err*]
                            (println "config.edn not found. Using example-config.edn.")
                            "example-config.edn"))]
    (->> config-filename (slurp) (clojure.edn/read-string))))

(defn get-config
  "Get the configuration parameter corresponding to the given keyword. If the parameter has
  entries for different operating environments, get the one corresponding to the currently
  configured operating environment (:dev, :test, or :prod), given by the :op-env parameter in the
  config file."
  [param-keyword & [alt-config]]
  (let [config (or alt-config config)
        op-env (:op-env config)
        param-rec (-> config (get param-keyword))]
    (if (and (map? param-rec)
             (contains? param-rec op-env))
      (get param-rec op-env)
      (get config param-keyword))))

(defn get-docker-config
  "Returns the docker config for a project, or if no docker config is defined for the project,
  returns the default docker config"
  [project-name & [alt-config]]
  (let [config (or alt-config config)]
    (or (-> :projects (get-config config) (get project-name) :docker-config)
        (get-config :docker-config config))))

(defn- check-config
  "Runs a series of tests on the configuration map"
  []
  (let [notify
        (fn [first-word & other-words]
          (binding [*out* *err*]
            (->> other-words (string/join " ") (str first-word " ") (println))))

        root-constraints
        {:cgi-timeout {:allowed-types [Long]}
         :docker-config {:required-always? true, :allowed-types [clojure.lang.PersistentArrayMap
                                                                 clojure.lang.PersistentHashMap]}
         :github-app-id {:required-when {:local-mode false}, :allowed-types [Long]}
         :html-body-colors {:allowed-types [String]}
         :local-mode {:allowed-types [Boolean]}
         :log-file {:required-always? true, :allowed-types [String nil]}
         :log-level {:required-always? true, :allowed-types [clojure.lang.Keyword]}
         :op-env {:required-always? true, :allowed-types [clojure.lang.Keyword]}
         :pem-file {:required-when {:local-mode false}, :allowed-types [String]}
         :projects {:required-always? true, :allowed-types [clojure.lang.PersistentArrayMap
                                                            clojure.lang.PersistentHashMap]}
         :push-with-installation-token {:allowed-types [Boolean]}
         :remove-containers-on-shutdown {:allowed-types [Boolean]}
         :secure-site {:required-always? true, :allowed-types [Boolean]}
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
         :env {:required-when {:disabled? false}, :allowed-types [clojure.lang.PersistentArrayMap,
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
          "Checks the given config against the given constraints, and then checks to see if any
           parameters required by the constraints are in the config."
          (let [config-keys (keys config-to-check)
                constraint-keys (keys constraints)]
            ;; Check that each config parameter satisfies the above constraints:
            (doseq [config-key config-keys]
              (let [param-type (-> config-key (get-config config-to-check) (type))
                    allowed-types (-> (config-key constraints) (get :allowed-types))]
                (cond (= allowed-types nil)
                      (notify "WARNING - Unexpected parameter:" config-key "found in configuration."
                              "It will be ignored.")
                      (not-any? #(= param-type %) allowed-types)
                      (notify "ERROR - configuration parameter" config-key "has invalid type:"
                              param-type "It should be one of" allowed-types))))
            ;; Check whether any configuration parameters are missing:
            (doseq [constraint-key constraint-keys]
              (when (and (is-required? constraint-key constraints config-to-check)
                         (->> constraint-key (get config-to-check) (nil?)))
                (notify "ERROR - missing configuration parameter:" constraint-key)))))]

    ;; Check root-level configuration:
    (notify "INFO - checking root-level configuration ...")
    (crosscheck-config-constraints config root-constraints)
    ;; Check root-level docker-configuration:
    (let [docker-config (get-config :docker-config)]
      (notify "INFO - checking root-level docker configuration ...")
      (crosscheck-config-constraints docker-config docker-constraints))
    ;; Check project-level configuration:
    (let [project-configs (-> (get-config :projects) (seq))]
      (doseq [project-keyval project-configs]
        (let [project-name (first project-keyval)
              project-config (second project-keyval)
              docker-config (get-docker-config project-name project-config)]
          (notify "INFO - checking configuration for project" project-name "...")
          (when (-> (type project-name) (= String) (not))
            (notify "ERROR - project identifier" project-name "should be of type String"))
          (crosscheck-config-constraints project-config project-constraints)
          ;; Check the project-level docker-config:
          (when docker-config
            (notify "INFO - checking docker configuration for project" project-name "...")
            (crosscheck-config-constraints docker-config docker-constraints)))))))

;; Validate the configuration map at startup:
(check-config)

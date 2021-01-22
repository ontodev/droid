(ns droid.config
  (:require [clojure.java.io :as io]))

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
  [param-keyword]
  (let [op-env (:op-env config)
        param-rec (-> config (get param-keyword))]
    (if (and (map? param-rec)
             (contains? param-rec op-env))
      (get param-rec op-env)
      (get config param-keyword))))

(defn get-docker-config
  "Returns the docker config for a project, or if no docker config is defined for the project,
  returns the default docker config"
  [project-name]
  (or (-> :projects (get-config) (get project-name) :docker-config)
      (get-config :docker-config)))


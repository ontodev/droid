(ns droid.config
  (:require [clojure.java.io :as io]
            [clojure.string :as string]))

(defn notify
  [first-word & other-words]
  (binding [*out* *err*]
    (->> other-words (string/join " ") (str first-word " ") (println))))

(defn fail
  [first-word & other-words]
  (binding [*out* *err*]
    (->> other-words (string/join " ") (str first-word " ") (println))
    (System/exit 1)))

(def ^:private default-config
  "A map of configuration parameters, loaded from the example-config.edn file in the root directory"
  (try (-> "example-config.edn" (slurp) (clojure.edn/read-string))
       (catch Exception e
         (fail "ERROR reading example-config.edn:" (.getMessage e)))))

(def ^:private config
  "A map of configuration parameters, loaded from the config.edn file in the root directory, with
  defaults filled in from example-config.edn. If config.edn doesn't exist, use example-config.edn."
  (let [actual-config (when (-> (io/file "config.edn") (.exists))
                        (try (->> "config.edn" (slurp) (clojure.edn/read-string))
                             (catch Exception e
                               (fail "ERROR reading config.edn:" (.getMessage e)))))
        default-docker-config (:docker-config default-config)
        default-project-config (-> default-config (:projects) (get "project1"))]
    ;; Begin with the default configuration, then override the root-level parameters, docker
    ;; configuration, and individual project configurations:
    (if-not actual-config
      (do (notify "WARN - config.edn not found. Using example-config.edn.")
          default-config)
      (-> default-config
          (merge actual-config)
          (assoc :docker-config (-> default-docker-config
                                    (merge (:docker-config actual-config))))
          (assoc :projects
                 (->> actual-config
                      :projects
                      (seq)
                      (map (fn [map-entry]
                             (array-map (first map-entry)
                                        (-> (second map-entry)
                                            (assoc :docker-config
                                                   (->> (second map-entry)
                                                        :docker-config
                                                        (merge default-docker-config)))
                                            (#(merge default-project-config %))))))
                      (apply merge)))))))

(defn get-config
  "Get the configuration parameter corresponding to the given keyword, if one is specified. If
  alt-config is also specified, look there for the parameter, otherwise look for it in the
  global config. If no configuration parameter has been specified, return the entire configuration
  map."
  ([param-keyword & [alt-config]]
   (let [config (or alt-config config)]
     (get config param-keyword)))
  ([]
   config))

(defn get-default-config
  "Get the default configuration parameter corresponding to the given keyword, if one is specified.
  If no configuration parameter is specified, return the entire default configuration map."
  ([param-keyword]
   (get-config param-keyword default-config))
  ([]
   default-config))

(defn get-docker-config
  "Returns the docker config for a project, or if no docker config is defined for the project,
  returns the default docker config"
  [project-name & [alt-config]]
  (let [config (or alt-config config)]
    (or (-> :projects (get-config config) (get project-name) :docker-config)
        (get-config :docker-config config))))

(defn get-env
  "Looks for the key :env within the given config map, then for each entry in the map, if it
  points to a valid file, read the value of the environment variable from the file, otherwise leave
  the value of the environment variable unchanged. Finally, return the possibly interpolated map."
  [config-map]
  (let [env-map (or (:env config-map) {})
        extract-val #(if-not (string/starts-with? % "file://")
                       %
                       (try
                         (-> % (string/replace-first #"^file://" "") (slurp))
                         (catch Exception e
                           (notify "WARN - Could not read environment variable from" % ";"
                                   (.getMessage e))
                           %)))]
    (->> env-map
         (seq)
         (map #(let [key (first %)
                     val (-> % (second) (extract-val))]
                 (hash-map key val)))
         (apply merge)
         (#(or % {})))))

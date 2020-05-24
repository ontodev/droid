(ns droid.config)

(def default-config
  "The default configuration that will be used if the configuration file cannot be found."
  {:projects {}

   ;; Application settings (should be one of :dev, :test, :prod)
   :op-env :dev

   :server-port {:dev 8090
                 :test 8090
                 :prod 8090}

   ;; Should be one of :debug, :info, :warn, :error, :fatal
   :log-level {:dev :debug
               :test :info
               :prod :info}

   ;; false for http, true for https
   :secure-site {:dev false
                 :test true
                 :prod true}

   ;; List of userids that are considered site administrators:
   :site-admin-github-ids {:dev #{""}
                           :test #{""}
                           :prod #{""}}})

(def config
  "A map of configuration parameters, loaded from the config.edn file in the data directory.
  If that file does not exist, return a default configuration hashmap that is suitable for
  unit (dev) testing."
  (let [config-filename "config.edn"]
    (try
      (->> config-filename
           (slurp)
           (clojure.edn/read-string))
      (catch java.io.FileNotFoundException e
        default-config))))

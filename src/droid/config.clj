(ns droid.config
  )

(def config
  "A map of configuration parameters, loaded from the config.edn file in the data directory"
  (->> "config.edn"
       (slurp)
       (clojure.edn/read-string)))

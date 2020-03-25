(ns droid.core
  (:require [org.httpkit.server :refer [run-server]]
            [droid.config :refer [config]]
            [droid.handler :as handler]
            [droid.log :as log]))


(defn- wrap-app
  "Wrapper that simply returns the webapp defined in the droid.handler module"
  []
  #'handler/app)


(defn -main []
  (let [op-env (:op-env config)
        port (-> config
                 :server-port
                 (get op-env))]
    (log/info (str "Starting HTTP server on port " port "."))
    (run-server (wrap-app) {:port port})))

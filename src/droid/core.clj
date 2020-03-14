(ns droid.core
  (:require [org.httpkit.server :refer [run-server]]
            [droid.handler :as handler]
            [droid.log :as log]))

(defn- wrap-app
  "Wrapper that simply returns the webapp defined in the droid.handler module"
  []
  #'handler/app)

(defn -main []
  (let [port 8090]
    (log/info (str "Starting HTTP server on port " port "."))
    (run-server (wrap-app) {:port port})))

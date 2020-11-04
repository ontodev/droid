(ns droid.core
  (:require [clojure.repl :as repl]
            [org.httpkit.server :refer [run-server]]
            [droid.branches :refer [remove-local-branch-containers]]
            [droid.config :refer [get-config]]
            [droid.handler :as handler]
            [droid.log :as log])
  (:gen-class))

(defn- wrap-app
  "Wrapper that simply returns the webapp defined in the droid.handler module"
  []
  #'handler/app)

(defn- shutdown
  "Exits the application gracefully"
  ([]
   ;; Right before shutting down the application do the following:
   (log/info "DROID shutting down.")
   ;; TODO: instead of remove, do a stop/start if it is :test or :prod and maybe also add
   ;; a configurable remove for :dev.
   (remove-local-branch-containers)
   (shutdown-agents))

  ([_]
   ;; If an interrupt is received, we exit:
   (System/exit 0)))

;; Call the one-argument version of shutdown when an interrupt signal is received:
(repl/set-break-handler! shutdown)

(defn -main []
  ;; Add a shutdown hook to the no-argument version of `shutdown`:
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable shutdown))
  (let [port (get-config :server-port)]
    (log/info (str "Starting HTTP server on port " port "."))
    (run-server (wrap-app) {:port port})))

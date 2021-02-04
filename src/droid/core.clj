(ns droid.core
  (:require [clojure.repl :as repl]
            [org.httpkit.server :refer [run-server]]
            [droid.branches :refer [pause-branch-containers remove-branch-containers]]
            [droid.config :refer [get-config dump-config]]
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
   (log/info "Pausing any running branch containers")
   (pause-branch-containers true)
   (when (get-config :remove-containers-on-shutdown)
     (log/info "Removing any existing branch containers")
     (remove-branch-containers))
   (shutdown-agents))

  ([_]
   ;; If an interrupt is received, we exit:
   (System/exit 0)))

;; Call the one-argument version of shutdown when an interrupt signal is received:
(repl/set-break-handler! shutdown)

(defn -main [& args]
  (let [cli-option (first args)
        usage-message (str "Command-line options:\n"
                           " --check-config Check the validity of the configuration file and exit\n"
                           " --dump-config  Output the configuration parameters and exit")]
    (cond
      (= cli-option "--help")
      (binding [*out* *err*]
        (println usage-message)
        (System/exit 0))

      ;; We don't need to explicitly call the check-config function here since it is automatically
      ;; called when the config module is loaded at statup.
      (= cli-option "--check-config")
      (System/exit 0)

      (= cli-option "--dump-config")
      (do (dump-config)
          (System/exit 0))

      ;; If there is no command line option, do nothing:
      (nil? cli-option)
      (do)

      :else
      (binding [*out* *err*]
        (println "Unrecognised command-line option:" cli-option)
        (println usage-message)
        (System/exit 1))))

  ;; Add a shutdown hook to the no-argument version of `shutdown`:
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable shutdown))
  (log/info "Unpausing any paused branch containers")
  (pause-branch-containers false)
  (let [port (get-config :server-port)]
    (log/info (str "Starting HTTP server on port " port "."))
    (run-server (wrap-app) {:port port})))

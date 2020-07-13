(ns droid.agent
  (:require [droid.config :refer [get-config]]
            [droid.log :as log]))

(def default-agent-error-handler
  "The default error handler to use with agents"
  (fn [the-agent exception]
    (log/error (.getMessage exception))
    (when (and (->> :op-env (get-config) (= :dev))
               (->> :log-level (get-config) (= :debug)))
      (.printStackTrace exception))))

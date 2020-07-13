(ns droid.command
  (:require [clojure.string :as string]
            [me.raynes.conch.low-level :as sh]
            [droid.log :as log]))

(defn run-command
  "Run the given command, then return a vector containing (1) the process that was created, and (2)
  its exit code wrapped in a future. Note that `command` must be a vector representing the function
  arguments that will be sent to (me.raynes.conch.low-level/proc). If `timeout` has been specified,
  then the command will run for no more than the specified number of milliseconds."
  ([command timeout]
   (log/debug "Running" (->> command (string/join " ")))
   (let [process (apply sh/proc command)
         exit-code (-> process
                       (#(if timeout
                           (sh/exit-code % timeout)
                           (sh/exit-code %)))
                       (future))]
     (vector process exit-code)))
  ([command]
   (run-command command nil)))

(defn run-commands
  "Run all the shell commands from the given command list. If any of them return an error code,
  throw an exception."
  [command-list]
  (doseq [command command-list]
    (let [[process exit-code] (run-command command)]
      (when-not (= @exit-code 0)
        (-> (str "Error while running '" command "': ")
            (str (sh/stream-to-string process :err))
            (Exception.)
            (throw))))))

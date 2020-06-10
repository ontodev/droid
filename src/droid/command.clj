(ns droid.command
  (:require [clojure.string :as string]
            [me.raynes.conch.low-level :as sh]
            [droid.log :as log]))

(defn run-commands
  "Run all the shell commands from the given command list."
  [command-list]
  (doseq [command command-list]
    (let [process (apply sh/proc command)
          exit-code (future (sh/exit-code process))]
      (log/debug "Running" (->> command (string/join " ")))
      (when-not (= @exit-code 0)
        (-> (str "Error while running '" command "': ")
            (str (sh/stream-to-string process :err))
            (Exception.)
            (throw))))))

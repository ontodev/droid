(ns droid.cli-handler
  (:require [droid.config :refer [init-config dump-config]]
            [clojure.string :as string]))

(defn handle-cli-opts
  "Parses command-line arguments to DROID and acts accordingly."
  [args]
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

      (= cli-option "--init-config")
      (do (init-config)
          (System/exit 0))

      ;; If there is no command line option, do nothing:
      (nil? cli-option)
      (do)

      :else
      (binding [*out* *err*]
        (println "Unrecognised command-line option:" cli-option)
        (println usage-message)
        (System/exit 1)))))

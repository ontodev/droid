(ns droid.secrets
  (:require [environ.core :refer [env]]
            [droid.config :refer [get-config]]
            [droid.log :as log]))

(def secrets
  "Secret IDs and passcodes, loaded from environment variables."
  ;; Note that the env package maps an environment variable named ENV_VAR into the keyword
  ;; :env-var, so below :github-client-id is associated with GITHUB_CLIENT_ID and similarly for
  ;; others.
  (->> [:github-client-id :github-client-secret :personal-access-token]
       (map #(let [val (env %)]
               (if val
                 ;; If it is found return a hashmap with one entry:
                 {% val}
                 ;; Otherwise handle each possible case according to the logic below:
                 (cond (or (= % :github-client-id) (= % :github-client-secret))
                       (when-not (get-config :local-mode)
                         (log/critical (str % " must be set for non-local-mode")))

                       (= % :personal-access-token)
                       (when (get-config :local-mode)
                         (log/critical (str % " must be set for local-mode")))))))
       ;; Merge the hashmaps corresponding to each environment variable into one hashmap:
       (apply merge)))

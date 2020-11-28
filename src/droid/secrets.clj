(ns droid.secrets
  (:require [environ.core :refer [env]]
            [droid.log :as log]))

(def secrets
  "Secret IDs and passcodes, loaded from environment variables."
  ;; Note that the env package maps an environment variable named ENV_VAR into the keyword
  ;; :env-var, so below :github-client-id is associated with GITHUB_CLIENT_ID and similarly for
  ;; others.
  (->> [:github-client-id :github-client-secret :personal-access-token]
       (map #(let [val (env %)]
               (if (nil? val)
                 ;; Certain environment variables will cause DROID to exit with a fatal error
                 ;; if they do not exist.
                 (when (not= % :personal-access-token)
                   (-> % (str " not set") (log/fail)))
                 ;; If it is found return a hashmap with one entry:
                 {% val})))
       ;; Merge the hashmaps corresponding to each environment variable into one hashmap:
       (apply merge)))

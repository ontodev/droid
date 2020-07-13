(ns droid.log
  (:require [droid.config :refer [get-config]]))

(def log-levels {:debug 0 :info 1 :warn 2 :warning 2 :error 3 :fatal 4})

(defn- screened-out?
  "Given a keword representing the log-level, check to see whether the application configuration
  requires it to be screened out."
  [log-level]
  (let [config-level (->> :log-level
                          (get-config)
                          (get log-levels))
        given-level (log-level log-levels)]
    (< given-level config-level)))

(defn- log
  "Log the message represented by the given words preceeded by the date and time. If a log file is
  defined in the config, write the message to it, otherwise write it to stderr."
  [first-word & other-words]
  (let [now (-> "yyyy-MM-dd HH:mm:ss.SSSZ"
                (java.text.SimpleDateFormat.)
                (.format (new java.util.Date)))
        message (->> other-words
                     (clojure.string/join " ")
                     (str first-word " "))]
    (if (get-config :log-file)
      (spit (get-config :log-file)
            (str now "-" message "\n")
            :append true)
      (binding [*out* *err*]
        (println now "-" message)))))

(defn debug
  [first-word & other-words]
  (when (not (screened-out? :debug))
    (apply log "DEBUG" first-word other-words)))

(defn info
  [first-word & other-words]
  (when (not (screened-out? :info))
    (apply log "INFO" first-word other-words)))

(defn warn
  [first-word & other-words]
  (when (not (screened-out? :warn))
    (apply log "WARN" first-word other-words)))

(defn warning
  [first-word & other-words]
  (when (not (screened-out? :warning))
    (apply log "WARN" first-word other-words)))

(defn error
  [first-word & other-words]
  (when (not (screened-out? :error))
    (apply log "ERROR" first-word other-words)))

(defn fatal
  [first-word & other-words]
  (when (not (screened-out? :fatal))
    (apply log "FATAL" first-word other-words)))

(defn fail
  "Logs a fatal error and then exits with a failure status (unless the server is running in
  development mode."
  [errorstr]
  (fatal errorstr)
  (when-not (= (get-config :op-env) :dev)
    (System/exit 1)))

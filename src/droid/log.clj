(ns droid.log
  (:require [droid.config :refer [config]]))

(def log-levels {:debug 0 :info 1 :warn 2 :warning 2 :error 3 :fatal 4})

(defn- screened-out?
  "Given a keword representing the log-level, check to see whether the application configuration
  requires it to be screened out."
  [log-level]
  (let [op-env (:op-env config)
        config-level (->> config
                          :log-level
                          op-env
                          (get log-levels))
        given-level (log-level log-levels)]
    (< given-level config-level)))

(defn- log
  "Log the message represented by the given words to stderr, preceeded by the date and time."
  [first-word & other-words]
  (let [now (-> "yyyy-MM-dd HH:mm:ss.SSSZ"
                (java.text.SimpleDateFormat.)
                (.format (new java.util.Date)))
        message (->> other-words
                     (clojure.string/join " ")
                     (str first-word " "))]
    (binding [*out* *err*]
      (println now "-" message))))

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

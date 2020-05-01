(ns droid.db
  (:require [clojure.java.jdbc :as jdbc]
            ;;[java-jdbc.ddl :as ddl]
            ;;[java-jdbc.sql :as sql]
            [jdbc-ring-session.core :refer [jdbc-store]]))

(def db-path
  "Filesystem path for the session database."
  "./db/sessiondb")

(def db
  "The database specification"
  {:classname "org.h2.Driver"
   :subprotocol "h2"
   :subname db-path})

;; Create the lone table in the database that we will need to store session info:
(try
  (jdbc/db-do-commands
   db
   "CREATE TABLE session_store (
      session_id VARCHAR(36) NOT NULL,
      idle_timeout BIGINT DEFAULT NULL,
      absolute_timeout BIGINT DEFAULT NULL,
      value BINARY(10000),
      PRIMARY KEY (session_id)
    )")
  (catch org.h2.jdbc.JdbcBatchUpdateException e
    (when-not (-> e (.getMessage) (.contains "already exists"))
      (throw e))))

(def store
  "The actual storage container through which the session_store table in the database is accessed."
  (jdbc-store db))

(ns droid.db
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [jdbc-ring-session.core :refer [jdbc-store]]
            [droid.log :as log]))

(defn update-or-insert!
  "Updates columns or inserts a new row in the specified table"
  [db table row where-clause]
  ;; This function is adapted from one of the examples on:
  ;; http://clojure-doc.org/articles/ecosystem/java_jdbc/using_sql.html
  (jdbc/with-db-transaction [t-con db]
    (let [result (jdbc/update! t-con table row where-clause)]
      (if (zero? (first result))
        (jdbc/insert! t-con table row)
        result))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Code related to the session database
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def session-db-path
  "Filesystem path for the session database."
  "./db/session-db")

(def session-db-spec
  "The session database specification"
  {:classname "org.h2.Driver"
   :subprotocol "h2"
   :subname session-db-path})

;; Create the lone table in the database that we will need to store session info:
(try
  (jdbc/db-do-commands
   session-db-spec
   "CREATE TABLE session_store (
      session_id VARCHAR(36) NOT NULL,
      idle_timeout BIGINT DEFAULT NULL,
      absolute_timeout BIGINT DEFAULT NULL,
      value BINARY(10000),
      PRIMARY KEY (session_id))")
  (catch org.h2.jdbc.JdbcBatchUpdateException e
    (when-not (-> e (.getMessage) (.contains "already exists"))
      (throw e))))

(def session-store
  "The actual storage container through which the session_store table in the database is accessed."
  (jdbc-store session-db-spec))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Code related to the metadata database
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def metadata-db-path
  "Filesystem path for the metadata database"
  "./db/metadata-db")

(def metadata-db-spec
  "The metadata database specification"
  {:classname "org.h2.Driver"
   :subprotocol "h2"
   :subname metadata-db-path})

(try
  (jdbc/db-do-commands
   metadata-db-spec
   "CREATE TABLE metadata_store (
      project_name VARCHAR(64) NOT NULL,
      branch_name VARCHAR(64) NOT NULL,
      action VARCHAR(64) DEFAULT NULL,
      cancelled BOOLEAN DEFAULT FALSE,
      command VARCHAR(64) DEFAULT NULL,
      exit_code SMALLINT DEFAULT NULL,
      start_time BIGINT DEFAULT NULL,
      PRIMARY KEY (project_name, branch_name))")
  (catch org.h2.jdbc.JdbcBatchUpdateException e
    (when-not (-> e (.getMessage) (.contains "already exists"))
      (throw e))))

(defn persist-branch-metadata
  "Persist the metadata for the given branch to the metadata database."
  [{:keys [project-name branch-name action cancelled command exit-code start-time] :as branch}]
  (let [exit-code-to-insert (when (and exit-code (realized? exit-code))
                              (if (= @exit-code :timeout)
                                1
                                @exit-code))]
    (update-or-insert! metadata-db-spec "metadata_store" {"project_name" project-name,
                                                          "branch_name" branch-name,
                                                          "action" action,
                                                          "cancelled" cancelled,
                                                          "command" command,
                                                          "exit_code" exit-code-to-insert,
                                                          "start_time" start-time}
                       [(format "project_name = '%s' and branch_name = '%s'"
                                project-name branch-name)])))

(defn get-persisted-branch-metadata
  "Given a project name and a branch name, retrieve the corresponding metadata from the metadata
  database, in the form of a map."
  [project-name branch-name]
  (let [query-string (-> "SELECT * FROM metadata_store "
                         (str "WHERE project_name = '%s' ")
                         (str "AND branch_name = '%s'")
                         (format project-name branch-name))]
    (log/debug "Querying metadata database:" query-string)
    (->> (jdbc/query metadata-db-spec [query-string])
         ;; The table's primary key is a combination of project name and branch name, so there will
         ;; only be one row returned:
         (first)
         ;; The h2 database doesn't support "-" in column names, so we need to convert underscores
         ;; into dashes:
         (map #(-> (key %)
                   (name)
                   (string/replace #"_" "-")
                   (keyword)
                   (hash-map (val %))))
         (apply merge)
         ;; Convert the exit code into a future:
         (#(let [exit-code (:exit-code %)]
             (if exit-code
               (assoc % :exit-code (future exit-code))
               %))))))

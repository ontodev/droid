(ns droid.data
  (:require [clojure.java.io :as io]
            [environ.core :refer [env]]
            [droid.config :refer [config]]
            [droid.log :as log]
            [droid.make :as make]))


(defn- fail
  "Logs a fatal error and then exits with a failure status (unless the server is running in
  development mode."
  [errorstr]
  (log/fatal errorstr)
  (when-not (= (:op-env config) :dev)
    (System/exit 1)))


;; Load the secret ids and passcodes from environment variables GITHUB_CLIENT_ID and
;; GITHUB_CLIENT_SECRET:
(def secrets
  (->> [:github-client-id :github-client-secret]
       (map #(let [val (env %)]
               (if (nil? val)
                 ;; Raise an error if the environment variable isn't found:
                 (-> % (str " not set") (fail))
                 ;; Otherwise return a hashmap with one entry:
                 {% val})))
       ;; Merge the hashmaps corresponding to each environment variable into one hashmap:
       (apply merge)))


(defn- delete-recursively
  "Delete all files and directories recursively under and including topname."
  [topname]
  (let [filenames (->> topname (io/file) (.list))]
    (doseq [filename filenames]
      (let [path (str topname "/" filename)]
        (if (-> path (io/file) (.isDirectory))
          (delete-recursively path)
          (io/delete-file path true)))))
  (io/delete-file topname true))


;; A hashmap with information for all of the branches in the workspace:
(def branches
  (do
    (when (->> "workspace"
               (io/file)
               (#(or (not (.exists %)) (not (.isDirectory %)))))
      (fail "workspace/ doesn't exist or isn't a directory."))

    ;; Destroy and recreate the temp/ directory:
    (delete-recursively "temp")
    (.mkdir (io/file "temp"))
    ;; Each sub-directory of the workspace represents a branch with the same name.
    (let [branch-dirs (->> "workspace" (io/file) (.list))]
      (->> (for [branch-dir branch-dirs]
             (when (-> "workspace/" (str branch-dir) (io/file) (.isDirectory))
               ;; Create a sub-directory with the same name as the branch in the
               ;; temp/ directory:
               (-> "temp/" (str branch-dir) (io/file) (.mkdir))
               ;; Create an empty console.txt file in the branch's sub-directory in temp/:
               (-> "temp/" (str branch-dir "/console.txt") (spit nil))
               ;; Create a hashmap entry mapping the branch name to the contents of its
               ;; corresponding workspace directory. These contents are represented by a set
               ;; (initially empty) of hashmaps, each of which represents the attributes of an
               ;; individual file or sub-directory under the branch directory. The contents of a
               ;; branch as a whole (i.e. the initially empty set) is atomic.
               (-> branch-dir (keyword) (hash-map (atom {})))))
           ;; Combine the sequence of hashmap entries generated by the for loop into a single map:
           (apply merge)))))


(defn refresh-branch!
  "Reload the map containing information on the contents of the directory corresponding to the
  given branch in the workspace."
  [branch-name]
  (let [branch (->> branch-name (keyword) (get branches))]
    ;; The contents of the directory for the branch are represented by a hashmap, mapping the
    ;; keywordized name of each file/sub-directory in the branch to nested hashmap with
    ;; info about that file/sub-directory. This info is at a minimum the file/sub-directory's
    ;; non-keywordized name. Other info may be added later.
    (->> branch-name
         (str "workspace/")
         (io/file)
         (.list)
         ;; We skip the Makefile for now. It will be populated separately later on.
         (map #(when-not (= % "Makefile")
                 (hash-map (keyword %) (hash-map :name %))))
         ;; Merge the sequence of hashmaps just generated into a larger hashmap:
         (apply merge)
         ;; Merge the newly generated hashmap with the currently saved hashmap:
         (swap! branch merge))

    ;; Now read in the Makefile and update the info relating to it in the branch as necessary:
    (swap! branch merge (make/get-makefile-info branch-name branch))
    ;; Read the contents of the console file in the branch's temp directory and add those contents
    ;; to the branch:
    (swap! branch assoc :console (-> "temp/" (str branch-name "/console.txt") (slurp)))

    ;; If there is a process running currently, add info to the branch about how long it has been
    ;; running for:
    (when (and (not (->> @branch :process nil?))
               (not (->> @branch :exit-code nil?))
               (not (->> @branch :exit-code realized?)))
      (swap! branch assoc :run-time (->> @branch :start-time (- (System/currentTimeMillis)))))
    ;; Return the new contents of the branch to the caller:
    @branch))

(ns droid.data
  (:require [clojure.java.io :as io]
            [environ.core :refer [env]]
            [droid.log :as log]
            [droid.make :as make]))


(defn- fail
  "Logs a fatal error and then exits with a failure status"
  [errorstr]
  (log/fatal errorstr)
  (System/exit 1))


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
       ;; Merge all of the hashmaps into one large hashmap:
       (apply merge)))


(defn- delete-recursively
  "Delete all files and directories recursively under and including element."
  [element]
  (let [filenames (->> element (io/file) (.list))]
    (doseq [filename filenames]
      (let [path (-> element (str "/" filename))]
        (if (-> path (io/file) (.isDirectory))
          (delete-recursively path)
          (io/delete-file path true)))))
  (io/delete-file element true))


(def branches
  (do
    (when (->> "workspace"
               (io/file)
               (#(or (not (.exists %)) (not (.isDirectory %)))))
      (fail "The workspace/ directory doesn't exist. Exiting."))

    ;; Destroy and recreate the temp/ directory:
    (delete-recursively "temp")
    (.mkdir (io/file "temp"))
    ;; Each sub-directory of a workspace represents a branch with the same name.
    (let [branch-dirs (->> "workspace" (io/file) (.list))]
      (->> (for [branch-dir branch-dirs]
             (when (-> "workspace/" (str branch-dir) (io/file) (.isDirectory))
               ;; Create a sub-directory with the same name as the branch in the
               ;; temp/ directory:
               (-> "temp/" (str branch-dir) (io/file) (.mkdir))
               ;; Create a console.txt file in the branch's sub-directory in temp/:
               (-> "temp/" (str branch-dir "/console.txt") (spit nil))
               ;; Create a hashmap entry mapping the branch name to the contents of its
               ;; corresponding workspace directory. These contents are represented by a set
               ;; (initially empty) of hashmaps, each of which represents the attributes of an
               ;; individual file or sub-directory under the branch directory. The contents of a
               ;; branch as a whole (i.e. the initially empty set) is atomic.
               (-> branch-dir (keyword) (hash-map (atom {})))))
           ;; Combine the sequence ofe hashmap entries generated by the for loop into a single map:
           (apply merge)))))


(defn refresh-branch!
  "Reload the map containing information on the contents of the directory corresponding to the
  given branch in the workspace."
  [branch-name]
  (let [branch (->> branch-name (keyword) (get branches))]
    (->> branch-name
         (str "workspace/")
         (io/file)
         (.list)
         ;; The contents of the directory for the branch are represented by a hashmap, mapping the
         ;; keywordized name of each file/sub-directory in the branch to further hashmap with
         ;; info about the file/sub-directory. This info is at a minimum the file/sub-directory's
         ;; non-keywordized name. Other info may be added later. We skip the Makefile for now. It
         ;; will be populated separately later on.
         (map #(when-not (= % "Makefile")
                 (hash-map (keyword %) (hash-map :name %))))
         ;; Merge the sequence of hashmaps just generated into a larger hashmap:
         (apply merge)
         ;; Merge the newly generated hashmap with the current hashmap for the branch:
         (swap! branch merge))

    ;; Read in the Makefile and update the information relating to it in the branch as necessary:
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

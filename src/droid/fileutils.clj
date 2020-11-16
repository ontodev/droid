(ns droid.fileutils
  (:require [clojure.java.io :as io]
            [droid.log :as log]))

(defn delete-recursively
  "Delete all files and directories recursively under and including topname."
  [topname]
  (let [filenames (->> topname (io/file) (.list))]
    (doseq [filename filenames]
      (let [path (str topname "/" filename)]
        (if (-> path (io/file) (.isDirectory))
          (delete-recursively path)
          ;; TODO: Consider setting this to false
          (io/delete-file path true)))))
  ;; TODO: Consider setting this to false
  (io/delete-file topname true)
  (log/debug "Deleted" topname))

(defn recreate-dir-if-not-exists
  "If the given directory doesn't exist or it isn't a directory, recreate it."
  [dirname]
  (when-not (-> dirname (io/file) (.isDirectory))
    (log/debug "(Re)creating directory:" dirname)
    ;; By setting silent mode to true, the command won't complain if the file doesn't exist:
    (io/delete-file dirname true)
    (.mkdir (io/file dirname))))

(defn get-droid-dir
  "Get DROID's root directory"
  []
  (-> "." (io/file) (.getCanonicalPath) (str "/")))

(defn get-workspace-dir
  "Given a project and optionally a branch name, generate the appropriate workspace path"
  ([project-name]
   (str (get-droid-dir) "projects/" project-name "/workspace"))
  ([project-name branch-name]
   (str (get-droid-dir) "projects/" project-name "/workspace/" branch-name)))

(defn get-temp-dir
  "Given a project and optionally a branch name, generate the appropriate temp path"
  ([project-name]
   (str (get-droid-dir) "projects/" project-name "/temp"))
  ([project-name branch-name]
   (str (get-droid-dir) "projects/" project-name "/temp/" branch-name)))

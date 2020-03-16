(ns droid.make
  (:require [clojure.java.io :as io]
            [me.raynes.conch.low-level :as sh]
            [droid.log :as log]))


(defn- parse-makefile-contents
  "INSERT DOC HERE"
  [branch-name]
  (if-not (->> (str "workspace/" branch-name "/Makefile")
               (io/file)
               (.exists))
    (log/warn "Branch" branch-name "does not contain a Makefile.")

    (let [contents (-> (sh/proc "make" "-pn" :dir (str "workspace/" branch-name))
                       (sh/stream-to-string :out))]
      ;; TODO: parse the contents of the Makefile ...
      ;;(print contents)))
      ))

  ;; TODO: These Makefile actions are hard-coded for now, but later they
  ;; will need to be read from the Makefile:
  {:actions ["action1" "action2"]})


(defn get-makefile-info
  "INSERT DOC HERE"
  [branch-name branch]
  (let [Makefile (:Makefile @branch)
        last-known-mod (when-not (nil? Makefile)
                         (:modified Makefile))
        actual-last-mod (->> (str "workspace/" branch-name "/Makefile")
                             (io/file)
                             (.lastModified))]
    ;; Do nothing unless the Makefile has been modified since we read it last:
    (when (or (nil? last-known-mod)
              (> actual-last-mod last-known-mod))
      {:Makefile (merge {:name "Makefile"
                         :modified actual-last-mod}
                        (parse-makefile-contents branch-name))})))

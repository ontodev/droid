(ns droid.make
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [markdown-to-hiccup.core :as m2h]
            [me.raynes.conch.low-level :as sh]
            [droid.dir :refer [get-workspace-dir]]
            [droid.log :as log]))

(defn- get-markdown-and-phonies
  "Iterate using `reduce` over the lines in the Makefile, and in the process progressively build up
  a map called `makefile`. This map begins as nil, but will eventually contain two entries:
  `:markdown`, which contains the markdown in the 'Workflow' part of the Makefile, and
  `:phony-targets`, which is a set containing all of the .PHONY targets in the Makefile."
  [project-name branch-name]
  (if-not (->> "/Makefile"
               (str (get-workspace-dir project-name branch-name))
               (io/file)
               (.exists))
    ;; If there is no Makefile in the workspace for the branch, then log a warning and return nil:
    (log/warn "Branch" branch-name "does not contain a Makefile.")
    ;; Otherwise, parse the Makefile:
    (letfn [(concat-markdown-lines [line1 line2]
              (cond
                (and (string/blank? line1) (string/blank? line2)) ""
                (string/blank? line1) line2
                (string/blank? line2) line1
                :else (str line1 "\n" line2)))]
      (->> "/Makefile"
           (str (get-workspace-dir project-name branch-name))
           (slurp)
           (string/split-lines)
           (reduce (fn [makefile next-line]
                     (cond
                       ;; Add any phony targets found to the set of such targets in the makefile
                       ;; record to be returned:
                       (re-matches #"^\s*\.PHONY:.*" next-line)
                       (assoc makefile :phony-targets
                              (into (or (:phony-targets makefile) #{})
                                    (->> (string/split next-line #"\s+")
                                         ;; Extract only the actual phony target names:
                                         (filter #(and (not (= "" %))
                                                       (not (= ".PHONY:" %)))))))

                       ;; If we have hit upon the start of the workflow, initialize the markdown
                       ;; part of the makefile record to be returned:
                       (nil? (:markdown makefile))
                       (when (= next-line "### Workflow")
                         (assoc makefile :markdown ""))

                       ;; If we're already done with parsing the workflow, just return what we have:
                       (string/ends-with? (:markdown makefile) "\n\n")
                       makefile

                       ;; If we are here, then we haven't hit the end of the workflow yet. Add any
                       ;; lines beginning in '#' or '# ' to the markdown:
                       (string/starts-with? next-line "# ")
                       (assoc makefile :markdown
                              (concat-markdown-lines (:markdown makefile) (subs next-line 2)))
                       (string/starts-with? next-line "#")
                       (assoc makefile :markdown
                              (concat-markdown-lines (:markdown makefile) (subs next-line 1)))

                       ;; If we are here then we have finished reading the workflow lines. Add a
                       ;; double newline to the end of the markdown to indicate that we're done:
                       :else
                       (assoc makefile :markdown
                              (-> makefile :markdown (str "\n\n")))))
                   ;; The call to reduce begins with an uninitialized makefile map:
                   nil)
           ;; Remove any extra newlines from the end of the workflow that was parsed, and finally
           ;; add the branch and project names to the map that is returned (this is a bit redundant,
           ;; since these are also available at the branch's top level, but having them here will
           ;; prove convenient later):
           (#(->> %
                  :markdown
                  (string/trim-newline)
                  (assoc % :markdown)
                  (merge {:branch-name branch-name, :project-name project-name})))))))

(defn- process-markdown
  "Given a makefile with: (1) markdown representing its workflow; (2) a list of phony targets;
  (3) the name of the branch that the makefile is on: Output a new makefile record that contains:
  (1) The original markdown; (2) the original phony target list; (3) the original branch name;
  (4) a list of all targets; (5) a list of those targets which represent general actions; (6) a list
  of those targets which represent views; (7) a html representation (in the form of a hiccup
  structure) of the markdown."
  [{:keys [markdown phony-targets project-name branch-name] :as makefile}]
  (when markdown
    (let [html (->> markdown (m2h/md->hiccup) (m2h/component))]
      (letfn [(flatten-to-1st-level [mixed-level-seq]
                ;; Takes a sequence of sequences nested to various levels and returns a list of
                ;; flattened sequences.
                ;; Note that the flatten-to-1st-level function is essentially a copy of code
                ;; contributed by the user bazeblackwood
                ;; (https://stackoverflow.com/users/2694851/bazeblackwood) to Stack Overflow
                ;; (see: https://stackoverflow.com/a/35300641/8599709).
                (->> mixed-level-seq
                     (mapcat #(if (every? coll? %)
                                (flatten-to-1st-level %)
                                (list %)))))

              (extract-nested-links [html]
                ;; Recurses through the html hiccup structure and extracts any links that are found
                ;; into a set."
                (->> (for [elem html]
                       (when (= (type elem) clojure.lang.PersistentVector)
                         (if (= (first elem) :a)
                           elem
                           (extract-nested-links elem))))
                     (flatten-to-1st-level)
                     (into #{})))

              (process-makefile-html [{:keys [html views general-actions branch-name] :as makefile}]
                ;; Recurses through the html hiccup structure and transforms any links that are
                ;; found into either action links or view links:
                (letfn [(process-link [[tag {href :href} text :as link]]
                          (cond
                            (some #(= href %) general-actions)
                            [tag {:href (str "/" project-name "/branches/" branch-name
                                             "?new-action=" href)
                                  :class "btn btn-primary btn-sm"} text]

                            (some #(= href %) views)
                            [tag {:href (str "/" project-name "/branches/" branch-name
                                             "/views/" href)} text]

                            :else
                            [tag {:href href} text]))]
                  (->> (for [elem html]
                         (if (= (type elem) clojure.lang.PersistentVector)
                           (if (= (first elem) :a)
                             (process-link elem)
                             (vec (process-makefile-html {:html elem, :views views,
                                                          :general-actions general-actions,
                                                          :branch-name branch-name})))
                           elem))
                       (vec))))]
        (->> html
             (extract-nested-links)
             ;; For all of the extracted links, add those that do not contain an 'authority' part
             ;; (i.e. a domain name) to the list of targets, and then also place them, as
             ;; appropriate, into one of the general-actions or views lists:
             (map (fn [[tag {href :href} text]]
                    (when (nil? (->> href
                                     (java.net.URI.)
                                     (bean)
                                     :authority))
                      (merge
                       {:targets #{href}}
                       (if (some #(= href %) phony-targets)
                         {:general-actions #{href}}
                         {:views #{href}})))))
             (apply merge-with into)
             ;; Add the original html to the makefile record, and then send everything through
             ;; process-makefile-html, which will transform the view and action links accordingly:
             (merge makefile {:html html})
             (#(->> %
                    (process-makefile-html)
                    (assoc % :html))))))))

(defn get-makefile-info
  "Given a hashmap representing a branch, if the info regarding the Makefile in the branch is older
  than the Makefile actually residing on disk, then return an updated version of the Makefile record
  back to the caller."
  [{:keys [project-name branch-name Makefile] :as branch}]
  (let [last-known-mod (when-not (nil? Makefile)
                         (:modified Makefile))
        actual-last-mod (->> "/Makefile"
                             (str (get-workspace-dir project-name branch-name))
                             (io/file)
                             (.lastModified))]
    ;; Return nothing unless the Makefile has been modified since we read it last:
    (when (or (nil? last-known-mod)
              (>= actual-last-mod last-known-mod))
      {:Makefile (merge {:name "Makefile"
                         :modified actual-last-mod}
                        (->> branch-name
                             (get-markdown-and-phonies project-name)
                             (process-markdown)))})))

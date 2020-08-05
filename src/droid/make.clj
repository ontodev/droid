(ns droid.make
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]
            [markdown-to-hiccup.core :as m2h]
            [droid.fileutils :refer [get-workspace-dir]]
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
                       (assoc makefile :workflow true :markdown []))

                     ;; If we're already done with parsing the workflow, just return what we have:
                     (not (:workflow makefile))
                     makefile

                     ;; If we are here, then we haven't hit the end of the workflow yet. Add any
                     ;; lines beginning in '#' or '# ' to the markdown:
                     (string/starts-with? next-line "# ")
                     (update-in makefile [:markdown] conj (subs next-line 2))

                     (string/starts-with? next-line "#")
                     (update-in makefile [:markdown] conj (subs next-line 1))

                     ;; If we are here then we have finished reading the workflow lines. Add a
                     ;; double newline to the end of the markdown to indicate that we're done:
                     :else
                     (assoc makefile :workflow false)))
                 ;; The call to reduce begins with an uninitialized makefile map:
                 nil)
         ;; Remove any extra newlines from the end of the workflow that was parsed, and finally
         ;; add the branch and project names to the map that is returned (this is a bit redundant,
         ;; since these are also available at the branch's top level, but having them here will
         ;; prove convenient later):
         ((fn [makefile]
            (->> (get makefile :markdown [])
                 (string/join "\n")
                 (string/trim-newline)
                 (assoc (dissoc makefile :workflow) :markdown)
                 (merge {:branch-name branch-name, :project-name project-name})))))))

(defn- process-markdown
  "Given a makefile with: (1) markdown representing its workflow; (2) a list of phony targets;
  (3) the name of the branch that the makefile is on: Output a new makefile record that contains:
  (1) The original markdown; (2) the original phony target list; (3) the original branch name;
  (4) a list of all targets; (5) a list of those targets which represent general actions; (6) a list
  of those targets which represent file views; (7) a list of those targets which represent directory
  views; (8) a list of those targets that represent executable views; (9) a html representation (in
  the form of a hiccup structure) of the markdown."
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

              (normalize-exec-view [href]
                ;; Executable views are prefixed in the Makefile by "./" and in general have query
                ;; parameters appended to them; e.g., "./build/view.py?param1=a&param2=b". When
                ;; recording an executable view in the map for the makefile, we normalize it by
                ;; removing any such prefix and/or suffix. At the same time we want to preserve
                ;; these in the actual HTML that gets rendered on the branch page. This function
                ;; translates a non-normalized view into a normalized version.
                (-> href (string/replace #"^\./" "") (string/replace #"\?.+$" "")))

              (process-makefile-html [{:keys [html file-views dir-views exec-views general-actions
                                              branch-name]
                                       :as makefile}]
                ;; Recurses through the html hiccup structure and transforms any links that are
                ;; found into either action links or view links:
                (letfn [(process-link [[tag {href :href} text :as link]]
                          (cond
                            (some #(= href %) general-actions)
                            [tag {:href (str "/" project-name "/branches/" branch-name
                                             "?new-action=" href)
                                  ;; The 'action-btn' class is a custom one to identify action
                                  ;; buttons which we will later want to conditionally restrict
                                  ;; access to.
                                  :class "btn btn-primary btn-sm action-btn"} text]

                            (or (some #(= href %) (set/union file-views dir-views))
                                (some #(-> href (normalize-exec-view) (= %)) exec-views))
                            [tag {:href (str "/" project-name "/branches/" branch-name
                                             "/views/" href)} text]

                            :else
                            [tag {:href href} text]))]
                  (->> (for [elem html]
                         (if (= (type elem) clojure.lang.PersistentVector)
                           (if (= (first elem) :a)
                             (process-link elem)
                             (vec (process-makefile-html {:html elem,
                                                          :file-views file-views,
                                                          :dir-views dir-views,
                                                          :exec-views exec-views
                                                          :general-actions general-actions,
                                                          :branch-name branch-name})))
                           elem))
                       (vec))))]
        (->> html
             (extract-nested-links)
             ;; For all of the extracted links, add those that do not contain an 'authority' part
             ;; (i.e. a domain name) to the list of targets, and then also place them, as
             ;; appropriate, into one of the general-actions, file-views, dir-views, or exec-views
             ;; lists:
             (map (fn [[tag {href :href} text]]
                    (when (nil? (->> href
                                     (java.net.URI.)
                                     (bean)
                                     :authority))
                      (merge
                       {:targets #{href}}
                       (cond
                         (some #(= href %) phony-targets)
                         {:general-actions #{href}}

                         (string/ends-with? href "/")
                         {:dir-views #{href}}

                         (string/starts-with? href "./")
                         {:exec-views #{(normalize-exec-view href)}}

                         :else
                         {:file-views #{href}})))))
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

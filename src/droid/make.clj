(ns droid.make
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]
            [markdown-to-hiccup.core :as m2h]
            [ring.util.codec :as codec]
            [droid.config :refer [get-config]]
            [droid.fileutils :refer [get-workspace-dir]]
            [droid.github :as gh]
            [droid.log :as log]))

(defn- get-markdown-and-phonies
  "Iterate using `reduce` over the lines in the Makefile, and in the process progressively build up
  a map called `makefile`. This map begins as nil, but will eventually contain two entries:
  `:markdown`, which contains the markdown in the 'Workflow' part of the Makefile, and
  `:phony-targets`, which is a set containing all of the .PHONY targets in the Makefile."
  [makefile-path project-name branch-name]
  (if-not (->> makefile-path
               (io/file)
               (.exists))
    ;; If there is no Makefile in the workspace for the branch, then log a warning and return nil:
    (log/warn "Branch" branch-name "does not contain a Makefile.")
    ;; Otherwise, parse the Makefile:
    (->> makefile-path
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

                     ;; If we are here then we have finished reading the workflow lines.
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

              (convert-code-to-href [[tag _ target :as elem]]
                ;; Converts a <code>...</code> block to a HTML link. Four cases are handled below.
                ;; 1. `make <single-phony-target>` where <single-phony-target> is one of the targets
                ;;    defined as PHONY in the Makefile.
                ;; 2. `./some_script.sh [--foo=bar --bat=man ...]`: a CGI script that supports
                ;;    being invoked on the command line with the given command-line options.
                ;; 3. `path/to/file.txt`: a "file view"
                ;; 4. `path/to/dir/`: a "directory view"
                ;; 5. `git <command>`: where command is one of the supported git commands (see the
                ;;    module github.clj
                (try
                  (cond
                    ;; Make targets:
                    (string/starts-with? target "make ")
                    (let [target (-> target (string/split #"\s+") (second))]
                      [:a {:href target} target])
                    ;; Supported git actions:
                    (string/starts-with? target "git ")
                    (let [git-cmd-parts (-> target (string/split #"\s+") (#(take 2 %)))]
                      [:a {:href (string/join "-" git-cmd-parts)} (last git-cmd-parts)])
                    ;; Views:
                    :else
                    (let [text (-> target
                                   ;; Executable views should only show the basename of the script
                                   ;; (without the extension):
                                   (string/replace #"^\.\/(\.\.\/)*([\w\-_]+\/)*([\w\-_]+)(\.\w+)"
                                                   "$3")
                                   ;; Replace '../' and trailing '/' in file and directory views:
                                   (string/replace #"\/$" "")
                                   (string/replace #"^(\.\.\/)+" ""))]
                      (cond
                        ;; Executable views:
                        (string/starts-with? target "./")
                        (let [href-base (-> target (string/trim) (string/split #"\s+") (first))
                              options (let [options (-> text (string/split #"\s+" 2))]
                                        (when (> (count options) 1)
                                          (->> options (last) (#(string/split % #"(=|\s*\-\-)"))
                                               (remove empty?) (map #(codec/url-encode %))
                                               (apply array-map))))
                              query-str (when-not (empty? options)
                                          (->> options
                                               (map #(-> %
                                                         (key)
                                                         (codec/url-encode)
                                                         (str "=" (-> % (val) (codec/url-encode)))))
                                               (string/join "&")))]
                          [:a {:href (->> href-base (#(if-not query-str % (str % "?" query-str))))}
                           text])
                        ;; Directory views (display only the basename; whitespace not supported).
                        (string/ends-with? target "/")
                        [:a {:href (string/replace target #"\s+.+$" "")}
                         (-> text (string/replace #"\s+.+$" "") (io/file) (.getName))]
                        ;; File views (display only the basename; whitespace not supported).
                        :else
                        [:a {:href (string/replace target #"\s+.+$" "")}
                         (-> text (string/replace #"\s+.+$" "") (io/file) (.getName))])))
                  (catch Exception e
                    (log/error "Exception while parsing target" (str target ":") e)
                    [:a {:href ""} [:span {:class "text-danger font-weight-bold"}
                                    "Unable to parse command"]])))

              (extract-nested-links [html]
                ;; Recurses through the html hiccup structure and extracts any links that are found
                ;; into a set which is then returned"
                (->> (for [elem html]
                       (when (= (type elem) clojure.lang.PersistentVector)
                         (cond
                           ;; We have found a link:
                           (= (first elem) :a)
                           elem
                           ;; We have found a code block. Convert it to a link:
                           (= (first elem) :code)
                           (convert-code-to-href elem)
                           ;; Anything else, continue recursing:
                           :else
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
                                              git-actions branch-name]
                                       :as makefile}]
                ;; Recurses through the html hiccup structure and transforms any links that are
                ;; found into either action links or view links. Links for general-actions,
                ;; exec-views, and GitHub actions are marked as restricted-access links. This is a
                ;; custom class which is used by the html module to prevent certain users from
                ;; clicking on the corresponding link.
                (letfn [(process-link [[tag {href :href} text :as link]]
                          (cond
                            (some #(= href %) general-actions)
                            [tag {:href (str "/" project-name "/branches/" branch-name
                                             "?new-action=" href)
                                  :class "mb-1 mt-1 btn btn-primary btn-sm restricted-access"} text]

                            (some #(= href %) git-actions)
                            (let [git-keyword (keyword href)]
                              [tag
                               {:href (-> gh/git-actions (get git-keyword) :html-param)
                                :class (-> gh/git-actions (get git-keyword) :html-class
                                           (str " mt-1 mb-1 restricted-access"))}
                               (-> gh/git-actions (get git-keyword) :html-btn-label)])

                            (or (some #(= href %) (set/union file-views dir-views))
                                (some #(-> href (normalize-exec-view) (= %)) exec-views))
                            ;; Replace any instances of '../' in the href with 'PREV_DIR/' which
                            ;; DROID will then have to interpret when rendering a view. Note that
                            ;; this is encoding is for the href only. The actual view path within
                            ;; file-views/dir-views/exec-views still needs to contain the '../'
                            (let [encoded-href (string/replace href #"\.\.\/" "PREV_DIR/")]
                              [tag
                               (merge
                                {:href (str "/" project-name "/branches/" branch-name
                                            "/views/" encoded-href)
                                 :target "_blank"}
                                (when (some #(-> href (normalize-exec-view) (= %)) exec-views)
                                  {:class "restricted-access"}))
                               text])

                            :else
                            [tag {:href href :target "_blank"} text]))]
                  (->> (for [elem html]
                         (if (= (type elem) clojure.lang.PersistentVector)
                           (cond
                             ;; <a> tags for links:
                             (= (first elem) :a) (process-link elem)
                             ;; <code> tags for make targets, scripts, and other views (these must
                             ;; first be converted into links before being processed:
                             (= (first elem) :code)
                             (-> elem (convert-code-to-href) (process-link))
                             ;; Everything else:
                             :else (vec (process-makefile-html {:html elem,
                                                                :file-views file-views,
                                                                :dir-views dir-views,
                                                                :exec-views exec-views
                                                                :general-actions general-actions,
                                                                :git-actions git-actions,
                                                                :branch-name branch-name})))
                           elem))
                       (vec))))]
        (->> html
             (extract-nested-links)
             ;; For all of the extracted links, add those that do not contain an 'authority' part
             ;; (i.e. a domain name) to the list of targets, and then also place them, as
             ;; appropriate, into one of the general-actions, git-actions, file-views, dir-views,
             ;; or exec-views lists.
             (map (fn [[tag {href :href} text]]
                    (when (nil? (try (->> href
                                          (java.net.URI.)
                                          (bean)
                                          :authority)
                                     ;; Ignore URI parsing exceptions:
                                     (catch java.net.URISyntaxException e)))
                      (merge
                       {:targets #{href}}
                       (cond
                         (some #(= href %) phony-targets)
                         {:general-actions #{href}}

                         (string/starts-with? href "git")
                         {:git-actions #{href}}

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
  (let [makefile-path (-> :projects (get-config) (get project-name) :makefile-path)
        makefile-path (str (get-workspace-dir project-name branch-name)
                           (if makefile-path
                             (str "/" makefile-path)
                             "/Makefile"))
        last-known-mod (when-not (nil? Makefile)
                         (:modified Makefile))
        actual-last-mod (->> makefile-path
                             (io/file)
                             (.lastModified))]
    ;; Return nothing unless the Makefile has been modified since we read it last:
    (when (or (nil? last-known-mod)
              (>= actual-last-mod last-known-mod))
      {:Makefile (merge {:name (-> makefile-path (io/file) (.getName))
                         :modified actual-last-mod}
                        (->> branch-name
                             (get-markdown-and-phonies makefile-path project-name)
                             (process-markdown)))})))

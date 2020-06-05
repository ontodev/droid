(ns droid.data
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [environ.core :refer [env]]
            [me.raynes.conch.low-level :as sh]
            [droid.config :refer [config]]
            [droid.dir :refer [get-workspace-dir get-temp-dir]]
            [droid.github :as gh]
            [droid.log :as log]
            [droid.make :as make]))

(def secrets
  "Secret IDs and passcodes, loaded from environment variables."
  ;; Note that the env package maps an environment variable named ENV_VAR into the keyword
  ;; :env-var, so below :github-client-id is associated with GITHUB_CLIENT_ID and similarly for
  ;; :github-client-secret.
  (->> [:github-client-id :github-client-secret]
       (map #(let [val (env %)]
               (if (nil? val)
                 ;; Raise an error if the environment variable isn't found:
                 (-> % (str " not set") (log/fail))
                 ;; Otherwise return a hashmap with one entry:
                 {% val})))
       ;; Merge the hashmaps corresponding to each environment variable into one hashmap:
       (apply merge)))

(defn- default-agent-error-handler
  "The default error handler to use with agents"
  []
  (fn [the-agent exception]
    (log/error (.getMessage exception))))

(defn- delete-recursively
  "Delete all files and directories recursively under and including topname."
  [topname]
  (let [filenames (->> topname (io/file) (.list))]
    (doseq [filename filenames]
      (let [path (str topname "/" filename)]
        (if (-> path (io/file) (.isDirectory))
          (delete-recursively path)
          (io/delete-file path true)))))
  (io/delete-file topname true)
  (log/debug "Deleted" topname))

(defn- recreate-dir-if-not-exists
  "If the given directory doesn't exist or it isn't a directory, recreate it."
  [dirname]
  (when-not (-> dirname (io/file) (.isDirectory))
    (log/debug "(Re)creating directory:" dirname)
    ;; By setting silent mode to true, the command won't complain if the file doesn't exist:
    (io/delete-file dirname true)
    (.mkdir (io/file dirname))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Code related to remote branches (i.e., branches available via GitHub)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- initialize-remote-branches-for-project
  "Initialize the remote GitHub branches corresponding to the given project, using the given
  login and token for the call to GitHub."
  [project-name login token]
  (-> project-name
      (keyword)
      (hash-map (gh/get-remote-branches project-name login token))))

(defn refresh-remote-branches-for-project
  "Refresh the remote GitHub branches associated with the given project, using the given login and
  OAuth2 token to authenticate the request to GitHub."
  [all-current-branches project-name
   {{{:keys [login]} :user} :session,
    {{:keys [token]} :github} :oauth2/access-tokens}]
  (if (or (nil? token) (nil? login))
    ;; If the user is not authenticated, log it but just return the branch collection as is:
    (do
      (log/debug "Ignoring non-authenticated request to refresh remote branches")
      all-current-branches)
    ;; Otherwise perform the refresh:
    (->> (initialize-remote-branches-for-project project-name login token)
         (merge all-current-branches))))

(def remote-branches
  "The remote branches, per project, that are available to be checked out from GitHub."
  ;; We begin with an empty hash-map:
  (agent {} :error-mode :continue, :error-handler default-agent-error-handler))

(defn remote-branch-exists?
  "Checks whether the given branch name exists among the remote branches associated with the
  given project name."
  [project-name branch-name]
  (->> project-name
       (keyword)
       (get @remote-branches)
       (map #(:name %))
       (some #(= branch-name %))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Code related to local branches (i.e., branches managed by the server in its workspace)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn refresh-local-branch
  "Given a map containing information on the contents of the directory corresponding to the given
  branch in the workspace, generate an updated version of the map by re-reading the directory
  contents and returning it."
  [{:keys [branch-name project-name] :as branch}]
  (letfn [(parse-git-status [text]
            ;; Parses the porcelain git status short output and extracts the branch tracking info
            ;; and info about uncommitted files. Note that this output is guaranteed to be the same
            ;; across git versions.
            (let [[branch-status & file-statuses] (string/split-lines text)
                  local-remote-re "(((\\S+)\\.{3}(\\S+)|(\\S+)))"
                  ahead-behind-re "( \\[(ahead (\\d+))?(, )?(behind (\\d+))?\\])?"
                  tracking-pattern (re-pattern (str "## " local-remote-re ahead-behind-re))
                  [_ _ _ local remote local-alt _ _ ahead _ _ behind] (re-find tracking-pattern
                                                                               branch-status)]
              {:raw-text text
               :local (or local local-alt)
               :remote remote
               :ahead (if (nil? ahead) 0 (Integer/parseInt ahead))
               :behind (if (nil? behind) 0 (Integer/parseInt behind))
               ;; Simple yes or no to the question of whether there are files to commit:
               :uncommitted? (-> #(not (string/starts-with? % "??"))
                                 (some file-statuses)
                                 (boolean))}))]
    (let [branch-workspace-dir (get-workspace-dir project-name branch-name)
          branch-temp-dir (get-temp-dir project-name branch-name)
          git-status (let [process (sh/proc "git" "status" "--short" "--branch" "--porcelain"
                                            :dir (get-workspace-dir project-name branch-name))
                           exit-code (future (sh/exit-code process))]
                       (if (= @exit-code 0)
                         (parse-git-status (sh/stream-to-string process :out))
                         (do
                           (log/error
                            "Error retrieving git status for branch" branch-name "of project"
                            project-name ":" (sh/stream-to-string process :err))
                           {})))]
      ;; The contents of the directory for the branch are represented by a hashmap, mapping the
      ;; keywordized name of each file/sub-directory in the branch to nested hashmap with
      ;; info about that file/sub-directory. This info is at a minimum the file/sub-directory's
      ;; non-keywordized name. Other info may be added later.
      (-> (->> branch-workspace-dir
               (io/file)
               (.list)
               ;; We skip the Makefile for now. It will be populated separately later on.
               (map #(when-not (= % "Makefile")
                       (hash-map (keyword %) (hash-map :name %))))
               ;; Merge the sequence of hashmaps just generated into a larger hashmap:
               (apply merge)
               ;; Merge the newly generated hashmap with the currently saved hashmap:
               (merge branch)
               ;; Merge in the Makefile info:
               (#(merge % (make/get-makefile-info branch))))
          ;; Add the git status of the branch:
          (assoc :git-status git-status)
          ;; Add the console contents and info about the running process, if any:
          (assoc :console (-> branch-temp-dir (str "/console.txt") (slurp)))
          (#(if (and (not (->> % :process nil?))
                     (not (->> % :exit-code nil?))
                     (not (->> % :exit-code realized?)))
              (assoc % :run-time (->> branch :start-time (- (System/currentTimeMillis))))
              %))))))

(defn- initialize-branch
  "Given the names of a project and branch, create the branch's temporary directory and console, and
  initialize and return an agent that will be used to manage access to the branch's resources."
  [project-name branch-name]
  (let [project-temp-dir (get-temp-dir project-name)]
    ;; If a subdirectory with the given branch name doesn't exist in the temp dir,
    ;; recreate it:
    (-> project-temp-dir (str branch-name) (recreate-dir-if-not-exists))
    ;; If the console.txt file doesn't already exist in the branch's temp dir, then initialize an
    ;; empty one:
    (when-not (-> project-temp-dir (str branch-name "/console.txt") (io/file) (.isFile))
      (-> project-temp-dir (str branch-name) (io/file) (.mkdir))
      (-> project-temp-dir (str branch-name "/console.txt") (spit nil)))

    ;; Create a hashmap entry mapping the branch name to the contents of its
    ;; corresponding workspace directory. These contents are represented by a hashmap
    ;; mapping a keywordized version of each filename/directory to a further hashmap
    ;; representing that file/directory. In addition to entries corresponding to
    ;; files and directories, a number of entries for meta-content are also present;
    ;; initially this is just the branch-name and project-name, for convenience.
    ;; The hashmap representing the branch as a whole is encapsulated inside an
    ;; agent, which will be used to serialise updates to the branch info.
    (-> branch-name
        (keyword)
        (hash-map (agent {:project-name project-name, :branch-name branch-name}
                         :error-mode :continue
                         :error-handler default-agent-error-handler)))))

(defn- refresh-local-branches-for-project
  "Returns a hashmap containing information about the contents and status of every local branch
  in the workspace of the given project. If the collection of current branches is given, an
  updated version of it is returned, otherwise a new collection is initialized."
  [project-name & [current-branches]]
  (let [project-workspace-dir (get-workspace-dir project-name)
        project-temp-dir (get-temp-dir project-name)]
    (when-not (->> project-workspace-dir (io/file) (.isDirectory))
      (log/fail (str project-workspace-dir " doesn't exist or isn't a directory.")))

    ;; If the temp directory doesn't exist or it isn't a directory, recreate it:
    (recreate-dir-if-not-exists project-temp-dir)

    ;; Each sub-directory of the workspace represents a branch with the same name.
    (let [branch-names (->> project-workspace-dir (io/file) (.list))]
      (->> branch-names
           (map #(when (-> project-workspace-dir (str %) (io/file) (.isDirectory))
                   (let [branch-key (keyword %)
                         branch-agent (branch-key current-branches)]
                     (if (nil? branch-agent)
                       (do
                         (log/info "Initializing branch:" % "of project:" project-name)
                         (initialize-branch project-name %))
                       (-> %
                           (keyword)
                           (hash-map (agent (refresh-local-branch @branch-agent)
                                            :error-mode :continue
                                            :error-handler default-agent-error-handler)))))))
           (apply merge)
           (hash-map (keyword project-name))))))

(defn refresh-local-branches
  "Refresh all of the local branches associated with the given sequence of project names."
  [all-branches project-names]
  (->> project-names
       (map #(->> %
                  (keyword)
                  (get all-branches)
                  (refresh-local-branches-for-project %)))
       ;; Merge the sequence of hash-maps generated into a single hash-map:
       (apply merge)
       ;; Merge that hash-map into the hash-map for all projects:
       (merge all-branches)))

(defn- kill-all-managed-processes
  "Kill all processes associated with the branches managed by the server."
  [all-branches]
  (letfn [(kill-process [{:keys [process action branch-name project-name] :as branch}]
            (if-not (nil? process)
              (do
                (log/info "Cancelling process:" action "on branch" branch-name "of" project-name)
                (sh/destroy process)
                (assoc branch :cancelled? true))
              branch))]
    (->> all-branches
         (keys)
         (map (fn [project-key]
                (->> all-branches
                     project-key
                     (vals)
                     (map deref)
                     (map kill-process)
                     (map #(hash-map (-> % :branch-name (keyword))
                                     (agent % :error-mode :continue
                                            :error-handler default-agent-error-handler)))
                     (apply merge)
                     (hash-map project-key))))
         (apply merge))))

(defn reset-all-local-branches
  "Reset all managed branches for all projects by killing all managed processes, deleting all
  temporary data, and reinitializing all local branches."
  [all-branches]
  (log/info "Killing all managed processes ...")
  (kill-all-managed-processes all-branches)
  (log/info "Deleting all temporary branch data ...")
  (doseq [project-name (-> config :projects (keys))]
    (-> project-name (get-temp-dir) (delete-recursively)))
  (log/info "Reinitializing local branches ...")
  (refresh-local-branches {} (-> config :projects (keys))))

(defn delete-local-branch
  "Deletes the given branch of the given project from the given managed server branches,
  and deletes the workspace and temporary directories for the branch in the filesystem."
  [all-branches project-name branch-name]
  (-> project-name (get-workspace-dir) (str "/" branch-name) (delete-recursively))
  (-> project-name (get-temp-dir) (str "/" branch-name) (delete-recursively))
  (-> all-branches
      (get (keyword project-name))
      (dissoc (keyword branch-name))
      (#(hash-map (keyword project-name) %))
      (#(merge all-branches %))))

(defn checkout-remote-branch-to-local
  "Checkout a remote GitHub branch into the local workspace."
  [all-branches project-name branch-name]
  (let [[org repo] (-> config :projects (get project-name) :github-coordinates (string/split #"/"))
        cloned-branch-dir (-> project-name (get-workspace-dir) (str branch-name))]
    ;; Clone the remote branch and configure it to use colours. If there is an error, then log it,
    ;; remove the newly created branch directory if it exists, and return the branch collection
    ;; back unchanged. Otherwise refresh the local branches for the project, which should pick up
    ;; the newly cloned directory:
    (try
      (doseq [command [["git" "clone" "--branch" branch-name
                        (str "git@github.com:" org "/" repo) branch-name
                        :dir (get-workspace-dir project-name)]
                       ["git" "config" "--local" "color.ui" "always" :dir cloned-branch-dir]]]
        (let [process (apply sh/proc command)
              exit-code (future (sh/exit-code process))]
          (log/debug "Running" (->> command (string/join " ")))
          (when-not (= @exit-code 0)
            (-> (str "Error while running '" command "': ")
                (str (sh/stream-to-string process :err))
                (Exception.)
                (throw)))))
      (catch Exception e
        (log/error (.getMessage e))
        (delete-recursively cloned-branch-dir)
        all-branches)))
  ;; Otherwise refresh the project; it should pick up the new branch:
  (refresh-local-branches all-branches [project-name]))

(defn create-local-branch
  "Creates a local branch with the given branch name in the workspace for the given project, with
  the branch point given by `base-branch-name`, and adds it to the collection of local branches."
  [all-branches project-name branch-name base-branch-name]
  (let [[org repo] (-> config :projects (get project-name) :github-coordinates (string/split #"/"))
        new-branch-dir (-> project-name (get-workspace-dir) (str branch-name))]
    ;; Clone the base branch from upstream, then locally checkout to a new branch, and push the
    ;; new branch to upstream. If there is an error in any of these commands, then log it, remove
    ;; the newly created branch directory (if it exists), and return the branch collection
    ;; unchanged. Otherwise, initialize the new branch and merge it into the branch collection to
    ;; return.
    (try
      (doseq [command [["git" "clone" "--branch" base-branch-name
                        (str "git@github.com:" org "/" repo) branch-name
                        :dir (get-workspace-dir project-name)]
                       ["git" "config" "--local" "color.ui" "always" :dir new-branch-dir]
                       ["git" "checkout" "-b" branch-name :dir new-branch-dir]
                       ["git" "push" "--set-upstream" "origin" branch-name :dir new-branch-dir]]]
        (let [process (apply sh/proc command)
              exit-code (future (sh/exit-code process))]
          (log/debug "Running" (->> command (string/join " ")))
          (when-not (= @exit-code 0)
            (-> (str "Error while running '" command "': ")
                (str (sh/stream-to-string process :err))
                (Exception.)
                (throw)))))
      (catch Exception e
        (log/error (.getMessage e))
        (delete-recursively new-branch-dir)
        all-branches)))
  (merge all-branches (initialize-branch project-name branch-name)))

(def local-branches
  "An agent to handle access to the hashmap that contains info on all of the branches managed by the
  server instance."
  (-> config
      :projects
      (keys)
      (#(refresh-local-branches {} %))
      (agent :error-mode :continue :error-handler default-agent-error-handler)))

(defn local-branch-exists?
  "Checks whether the given branch name exists among the local branches associated with the
  given project name."
  [project-name branch-name]
  (->> project-name
       (keyword)
       (get @local-branches)
       (keys)
       (some #(= branch-name (name %)))))

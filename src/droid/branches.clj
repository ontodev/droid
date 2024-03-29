(ns droid.branches
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [me.raynes.conch.low-level :as sh]
            [droid.agent :refer [default-agent-error-handler]]
            [droid.command :as cmd]
            [droid.config :refer [get-config get-docker-config get-env]]
            [droid.db :as db]
            [droid.fileutils :refer [delete-recursively recreate-dir-if-not-exists
                                     get-workspace-dir get-temp-dir get-make-dir]]
            [droid.github :as gh]
            [droid.log :as log]
            [droid.make :as make]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Code related to remote branches (i.e., branches available via GitHub)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- initialize-remote-branches-for-project
  "Initialize the remote GitHub branches corresponding to the given project, using the given
  login and token for the call to GitHub."
  [project-name login token]
  (let [default-branch (gh/get-default-branch project-name login token)
        remote-branches (->> (gh/get-remote-branches project-name login token)
                             (map #(if (= (:name %) default-branch)
                                     (assoc % :default-branch true)
                                     %)))]
    (-> project-name
        (keyword)
        (hash-map remote-branches))))

(defn refresh-remote-branches-for-project
  "Refresh the remote GitHub branches associated with the given project, using the given login and
  OAuth2 token to authenticate the request to GitHub. If no credentials are provided, fetch an
  installation token from GitHub and use it instead"
  [all-current-branches project-name
   {{{:keys [login]} :user} :session,
    {{:keys [token]} :github} :oauth2/access-tokens}]
  (let [password (or token (gh/get-github-app-installation-token project-name))]
    (->> (initialize-remote-branches-for-project project-name login password)
         (merge all-current-branches))))

(defn delete-remote-branch
  "Deletes the remote branch with the given name from the given project. Accepts the branch list
  as the first argument and threads it through with the deleted branch removed. Can be used with
  an agent."
  [all-current-branches project-name branch-name
   {{{:keys [login]} :user} :session,
    {{:keys [token]} :github} :oauth2/access-tokens}]
  (log/info "Deleting remote branch:" branch-name "of project:" project-name)
  (-> (gh/delete-branch project-name branch-name login token)
      ;; If the delete was successful, remove the deleted branch from the branch list:
      ((fn [success?]
         (when success?
           (->> (keyword project-name)
                (get all-current-branches)
                (filter #(not= (:name %) branch-name))
                (vec)
                (hash-map (keyword project-name))))))))

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

(defn get-remote-main
  "Gets the main branch name (a.k.a. the default branch name) for the given project."
  [project-name]
  (->> project-name
       (keyword)
       (get @remote-branches)
       (filter #(= (:default-branch %) true))
       (first)
       :name))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Code related to local branches (i.e., branches managed by the server in its workspace)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-branch-command
  "Runs the given command. If docker has been activated for the given project, then run the
  command in the given branch's container. Optionally exit after the given value for timeout
  (in milliseconds)."
  [command project-name branch-name & [timeout]]
  (let [docker-config (get-docker-config project-name)
        ;; Use the container name instead of the actual ID (it works just as well):
        container-id (str project-name "-" branch-name)
        git-head (-> (get-workspace-dir project-name branch-name) (str "/.git/HEAD") (slurp)
                     (string/trim-newline))
        project-env (-> :projects (get-config) (get project-name) (get-env))
        ;; Redefine the command by adding in the project-level environment. The docker-specific
        ;; environment variables will be taken care of by run-command:
        command (if-not (empty? project-env)
                  (cmd/supplement-command-env command project-env)
                  command)
        ;; Possibly redefine the command again; if the actual branch for the directory does not
        ;; match the branch name, turn the command into an echo command for an error message:
        command (if (-> "ref: refs/heads/" (str branch-name) (re-pattern) (re-matches git-head))
                  command
                  ["sh" "-c" (str "echo \"Refusing to run command: "
                                  (->> command (filter string?) (string/join " ")) ". "
                                  "The branch name: " branch-name
                                  " does not match the HEAD: " git-head " for this directory\" "
                                  "1>&2; exit 1")])]
    (cmd/run-command command timeout (when-not (:disabled? docker-config)
                                       {:project-name project-name
                                        :branch-name branch-name
                                        :container-id container-id}))))

(defn path-executable?
  "Determine whether the given path is executable. Note that this is done from the server's point of
  view even though, in principle, the answer to whether a given path is executable may be different
  depending on whether it is viewed from the server or the docker container's point of view.
  However in practice the answer should always be the same, since the server has set up the docker
  containers in the first place."
  [script-path]
  (-> script-path (io/file) (.canExecute)))

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
          git-status (let [[process exit-code]
                           (cmd/run-command ["git" "status" "--short" "--branch" "--porcelain"
                                             :dir (get-workspace-dir project-name branch-name)])]
                       (if (= @exit-code 0)
                         (parse-git-status (sh/stream-to-string process :out))
                         (do
                           (log/error
                            "Error retrieving git status for branch" branch-name "of project"
                            project-name ":" (sh/stream-to-string process :err))
                           {})))]
      (-> branch
          ;; Merge in the Makefile info:
          (merge (make/get-makefile-info branch))
          ;; Add the git status of the branch:
          (assoc :git-status git-status)
          ;; Add the console contents and info about the running process, if any:
          (assoc :console (-> branch-temp-dir (str "/console.txt") (slurp)))
          (#(if (and (not (->> % :process nil?))
                     (not (->> % :exit-code nil?))
                     (not (->> % :exit-code realized?)))
              (assoc % :run-time (->> branch :start-time (- (System/currentTimeMillis))))
              %))))))

(defn- create-image-and-container-for-branch
  "Create a new branch-specific docker image using the given image reference as the argument
  to --tag, and attach the build process to the given branch so that its output can be
  monitored in the console"
  [{:keys [branch-name project-name] :as branch} & [image-passed]]
  (log/info "Creating/pulling docker image and container for branch:" branch-name "of project:"
            project-name)
  (let [container-name (str project-name "-" branch-name)
        docker-config (get-docker-config project-name)
        ws-dir (-> (get-workspace-dir project-name branch-name) (str "/"))
        tmp-dir (-> (get-temp-dir project-name branch-name) (str "/"))
        output-redirect (str "> " (get-temp-dir project-name branch-name)
                             "/console.txt 2>&1")
        image-ref (-> (if image-passed
                        image-passed
                        (:image docker-config))
                      ;; Docker expects image names to be lowercase:
                      (string/lower-case))
        create-img-cmd (-> (if (re-find #":.+$" image-ref)
                             image-ref
                             (str image-ref ":latest"))
                           (#(if image-passed
                               (str "docker build --tag " % " .")
                               (str "docker pull " %))))
        command-base (str create-img-cmd " " output-redirect
                          " &&"
                          " docker create --interactive --tty"
                          " --name " container-name
                          " --volume " (str ws-dir ":" (:workspace-dir docker-config))
                          " --volume " (str tmp-dir ":" (:temp-dir docker-config))
                          (->> (:extra-volumes docker-config)
                               (map #(str " --volume " % ":" %))
                               (apply str))
                          " " image-ref " "
                          (:shell-command docker-config) " >" output-redirect
                          " &&"
                          " docker start " container-name " >" output-redirect)
        command ["sh" "-c" command-base
                 :dir (get-workspace-dir project-name branch-name)]
        [process exit-code] (cmd/run-command command)]
    (assoc branch
           :action "create-docker-image-and-container"
           :command command-base
           :process process
           :start-time (System/currentTimeMillis)
           :cancelled false
           :exit-code exit-code)))

(defn- branch-metadata-watcher
  "A watcher for a branch agent that persists the state of the agent's branch whenever it changes."
  [watcher-key branch-agent old-branch new-branch]
  (when-not (= old-branch new-branch)
    (-> "Persisting branch metadata for branch '%s' of project '%s' to the metadata database."
        (format (:branch-name new-branch) (:project-name new-branch))
        (log/debug))
    (db/persist-branch-metadata new-branch)))

(defn- initialize-branch
  "Given the names of a project and branch, create the branch's temporary directory and console, and
  initialize and return a hashmap with an entry corresponding to an agent that will be used to
  manage access to the branch's resources. If docker is active for the given project, then create an
  image to use for associating a container with the branch."
  [project-name branch-name & [server-startup?]]
  (let [project-temp-dir (get-temp-dir project-name)]
    ;; If a subdirectory with the given branch name doesn't exist in the temp dir,
    ;; recreate it:
    (-> project-temp-dir (str "/" branch-name) (recreate-dir-if-not-exists))
    ;; If the console.txt file doesn't already exist in the branch's temp dir, then initialize an
    ;; empty one:
    (when-not (-> project-temp-dir (str "/" branch-name "/console.txt") (io/file) (.isFile))
      (-> project-temp-dir (str "/" branch-name) (io/file) (.mkdir))
      (-> project-temp-dir (str "/" branch-name "/console.txt") (spit nil)))

    ;; Create a hashmap entry mapping the keywordized version of the branch name to a "branch",
    ;; i.e., the contents of its corresponding workspace directory. These contents are represented
    ;; by a hashmap mapping a keywordized version of each filename/directory to a further hashmap
    ;; representing that file/directory. In addition to entries corresponding to files and
    ;; directories, a number of entries for meta-content are also present.
    ;;
    ;; The hashmap representing the branch is encapsulated inside an agent, which will be used to
    ;; serialise updates to the branch. To each agent is assigned a watcher, which will persist the
    ;; state of the branch, whenever it changes, to the metadata database.
    ;;
    ;; If there is a record corresponding to the branch in the metadata database, this is used to
    ;; initialize the branch, otherwise it is initialized with the branch-name and project-name.
    ;; Other branch info will be added later.
    (let [branch-agent (agent (or (db/get-persisted-branch-metadata project-name branch-name)
                                  {:project-name project-name, :branch-name branch-name})
                              :error-mode :continue
                              :error-handler default-agent-error-handler)
          docker-active? (-> (get-docker-config project-name) :disabled? (not))]
      ;; Create/pull the image and container for the branch:
      (when (and docker-active? (not server-startup?))
        (if (-> (get-workspace-dir project-name branch-name) (str "/Dockerfile") (io/file)
                (.exists))
          (send-off branch-agent create-image-and-container-for-branch
                    (str project-name "-" branch-name))
          (send-off branch-agent create-image-and-container-for-branch)))

      ;; The keyword identifying the watcher is composed of its project and branch names joined by
      ;; a '-'. This can be used if the watcher needs to be removed via `remove-watch` later.
      (-> (str project-name "-" branch-name)
          (keyword)
          (#(add-watch branch-agent % branch-metadata-watcher))
          (#(hash-map (keyword branch-name) %))))))

(defn- refresh-local-branches-for-project
  "Returns a hashmap containing information about the contents and status of every local branch
  in the workspace of the given project. If the collection of current branches is given, an
  updated version of it is returned, otherwise a new collection is initialized."
  [server-startup? project-name current-branches]
  (let [project-workspace-dir (get-workspace-dir project-name)
        project-temp-dir (get-temp-dir project-name)]

    ;; Recreate the temp and workspace directories if they don't exist:
    (try
      (recreate-dir-if-not-exists project-workspace-dir)
      (recreate-dir-if-not-exists project-temp-dir)
      (catch Exception e
        (log/critical (.getMessage e))))

    ;; Each sub-directory of the workspace represents a branch with the same name.
    (let [branch-names (->> project-workspace-dir (io/file) (.list))]
      (->> branch-names
           (map #(when (-> project-workspace-dir (str "/" %) (io/file) (.isDirectory))
                   (let [branch-key (keyword %)
                         branch-agent (branch-key current-branches)]
                     (if (nil? branch-agent)
                       (do
                         (log/info "Initializing branch:" % "of project:" project-name)
                         (initialize-branch project-name % server-startup?))
                       (-> %
                           (keyword)
                           (hash-map (send-off branch-agent refresh-local-branch)))))))
           (apply merge)
           (hash-map (keyword project-name))))))

(defn refresh-local-branches
  "Refresh all of the local branches associated with the given sequence of project names."
  [all-branches project-names & [server-startup?]]
  (->> project-names
       (map #(->> %
                  (keyword)
                  (get all-branches)
                  (refresh-local-branches-for-project server-startup? %)))
       ;; Merge the sequence of hash-maps generated into a single hash-map:
       (apply merge)
       ;; Merge that hash-map into the hash-map for all projects:
       (merge all-branches)))

(defn kill-process
  "Kill the running process associated with the given branch and return the branch to the caller."
  [{:keys [process action command branch-name project-name] :as branch}
   & [{:keys [login] :as user}]]
  (if-not (nil? process)
    (do
      (-> (str "Cancelling process " action " on branch " branch-name " of " project-name)
          (#(if login
              (str % " on behalf of " login)
              %))
          (log/info))
      ;; If docker is configured for this project, then first kill the docker process corresponding
      ;; to the command within the container:
      (when (-> project-name (get-docker-config) :disabled? (not))
        (let [docker-container (str project-name "-" branch-name)
              [docker-process docker-exit-code] (cmd/run-command ["docker" "exec" docker-container
                                                                  "ps" "-o" "pid,args"])]
          (if-not (= 0 @docker-exit-code)
            (cmd/throw-process-exception docker-process (str "Error finding PID to kill in "
                                                             "container " docker-container))
            (let [ps-line (->> (sh/stream-to-string docker-process :out) (string/split-lines)
                               (some #(when (-> command (re-pattern) (re-find %))
                                        %)))]
              (if-not ps-line
                (log/info "Process for '" command "' not found within container" docker-container)
                (do
                  (log/info "Killing process: '" ps-line "' in container" docker-container)
                  (let [pid (-> ps-line (string/trim) (string/split #"\s+") (first))]
                    (let [[docker-kill-proc
                           docker-kill-exit-code] (cmd/run-command ["docker" "exec" docker-container
                                                                    "kill" pid])]
                      (when-not (= 0 @docker-kill-exit-code)
                        (cmd/throw-process-exception
                         docker-kill-proc (str "Can't kill process" pid "in container"
                                               docker-container)))))))))))
      ;; Now destroy the parent process attached to the branch and mark it as cancelled:
      (sh/destroy process)
      (assoc branch :cancelled true))
    branch))

(defn- kill-all-managed-processes
  "Kill all processes associated with the branches managed by the server."
  [all-branches]
  (->> all-branches
       (keys)
       (map (fn [project-key]
              (->> all-branches
                   project-key
                   (vals) ;; <-- these are the branch agents
                   (map #(send-off % kill-process))
                   ;; After killing all the managed processes, re-merge the agents into the
                   ;; global hash-map:
                   (map #(hash-map (-> % (deref) :branch-name (keyword))
                                   %))
                   (apply merge)
                   (hash-map project-key))))
       (apply merge)))

(defn reset-all-local-branches
  "Reset all managed branches for all projects by killing all managed processes, deleting all
  temporary data, and reinitializing all local branches."
  [all-branches]
  (log/info "Killing all managed processes ...")
  (kill-all-managed-processes all-branches)
  (log/info "Deleting all temporary branch data ...")
  (doseq [project-name (-> :projects (get-config) (keys))]
    (-> project-name (get-temp-dir) (delete-recursively)))
  (log/info "Reinitializing local branches ...")
  (refresh-local-branches {} (-> :projects (get-config) (keys))))

(defn pause-branch-container
  "If pause? is true, pause the container for the given branch of the given project, otherwise
  unpause them."
  [project-name branch-name pause?]
  (let [[process exit-code] (cmd/run-command ["docker" (if pause? "pause" "unpause")
                                              (-> project-name (str "-" branch-name))])]
    (let [error-msg (sh/stream-to-string process :err)]
      (if-not (= @exit-code 0)
        (when-not (re-find #"No such container" error-msg)
          (log/error "Error" (if pause? "pausing" "unpausing") "container:" error-msg))
        (log/info (if pause? "Paused" "Unpaused")
                  "docker container for branch:" branch-name "in project:" project-name)))))

(defn- remove-branch-container
  "Given a project and branch name, remove the corresponding container."
  [project-name branch-name]
  (let [[process exit-code] (cmd/run-command ["docker" "rm" "-f"
                                              (-> project-name (str "-" branch-name))])]
    (let [error-msg (sh/stream-to-string process :err)]
      (if-not (= @exit-code 0)
        (when-not (re-find #"No such container" error-msg)
          (cmd/throw-process-exception process "Error removing container"))
        (log/info "Removed docker container for branch:" branch-name "in project:" project-name)))))

(defn delete-local-branch
  "Deletes the given branch of the given project from the given managed server branches, and deletes
  the workspace and temporary directories for the branch in the filesystem. If make-clean? is true,
  then run make clean in the branch's workspace, ignoring any errors, before deleting the branch."
  [all-branches project-name branch-name make-clean?]
  (when make-clean?
    (log/info "Runing `make clean` in branch:" branch-name "of project" project-name)
    (let [makefile-name (-> all-branches (get (keyword project-name)) (get (keyword branch-name))
                            (deref) :Makefile :name)
          [process exit-code] (run-branch-command
                               ["make" "-i" "-k" "-f" makefile-name "clean"
                                :dir (get-make-dir project-name branch-name)]
                               project-name branch-name)]
      (or (= @exit-code 0) (cmd/throw-process-exception
                            process "Error while running `make clean`"))))

  (remove-branch-container project-name branch-name)
  (-> project-name (get-workspace-dir) (str "/" branch-name) (delete-recursively))
  (-> project-name (get-temp-dir) (str "/" branch-name) (delete-recursively))
  (-> all-branches
      (get (keyword project-name))
      (dissoc (keyword branch-name))
      (#(hash-map (keyword project-name) %))
      (#(merge all-branches %))))

(defn store-creds
  "Given the names of a project and of a branch within that project, and the login and token for the
  current user's session, store these credentials in a file in the directory for the branch, or if
  the :push-with-installation-token parameter is set to true in the config file, fetch an
  installation token to use instead. Note that the all-branches parameter is required in order to
  serialize this function through an agent, but it is simply passed through without modification."
  [all-branches project-name branch-name
   {{{:keys [login]} :user} :session,
    {{:keys [token]} :github} :oauth2/access-tokens}]
  (let [[org repo] (-> :projects (get-config) (get project-name) :github-coordinates
                       (string/split #"/"))
        password (if (get-config :push-with-installation-token)
                   (gh/get-github-app-installation-token project-name)
                   token)
        url (str "https://" login ":" password "@github.com/" org "/" repo)]
    (log/debug "Storing github credentials for" project-name "/" branch-name)
    (-> project-name (get-workspace-dir) (str "/" branch-name "/.git-credentials") (spit url))
    all-branches))

(defn remove-creds
  "Given the names of a project and a branch within that project, remove the credentials file from
  the directory for the branch. The all-branches parameter is required in order to serialize this
  function through an agent, but it is simply passed through without modification."
  [all-branches project-name branch-name]
  (log/debug "Removing github credentials from" project-name "/" branch-name)
  (-> project-name (get-workspace-dir) (str "/" branch-name "/.git-credentials")
      (io/delete-file true))
  all-branches)

(defn checkout-remote-branch-to-local
  "Checkout a remote GitHub branch into the local workspace."
  [all-branches project-name branch-name]
  (let [[org repo] (-> :projects (get-config) (get project-name) :github-coordinates
                       (string/split #"/"))
        cloned-branch-dir (-> project-name (get-workspace-dir) (str "/" branch-name))]
    ;; Clone the remote branch and configure it to use colours. If there is an error, then log it,
    ;; remove the newly created branch directory if it exists, and return the branch collection
    ;; back unchanged. Otherwise refresh the local branches for the project, which should pick up
    ;; the newly cloned directory:
    (try
      (cmd/run-commands [["git" "clone" "--branch" branch-name
                          (str "https://github.com/" org "/" repo) branch-name
                          :dir (get-workspace-dir project-name)]
                         ["sh" "-c"
                          (str "grep -qs '.git-credentials' .gitignore || "
                               "echo '.git-credentials' >> .gitignore")
                          :dir cloned-branch-dir]
                         ["sh" "-c" (str "git config credential.helper "
                                         "'store --file=.git-credentials'")
                          :dir cloned-branch-dir]
                         ["git" "config" "--local" "color.ui" "always" :dir cloned-branch-dir]
                         ["git" "config" "user.name" (get-config :github-user-name)
                          :dir cloned-branch-dir]
                         ["git" "config" "user.email" (get-config :github-user-email)
                          :dir cloned-branch-dir]
                         ["git" "fetch" :dir cloned-branch-dir]])
      (catch Exception e
        (log/error (.getMessage e))
        (delete-recursively cloned-branch-dir)
        all-branches)))
  ;; Otherwise refresh the project; it should pick up the new branch:
  (refresh-local-branches all-branches [project-name]))

(defn create-local-branch
  "Creates a local branch with the given branch name in the workspace for the given project, with
  the branch point given by `base-branch-name`, and adds it to the collection of local branches."
  [all-branches project-name branch-name base-branch-name request]
  (let [[org repo] (-> :projects (get-config) (get project-name) :github-coordinates
                       (string/split #"/"))
        new-branch-dir (-> project-name (get-workspace-dir) (str "/" branch-name))]
    ;; Clone the base branch from upstream, then locally checkout to a new branch, and push the
    ;; new branch to upstream. If there is an error in any of these commands, then log it, remove
    ;; the newly created branch directory (if it exists), and return the branch collection
    ;; unchanged. Otherwise, initialize the new branch and merge it into the branch collection to
    ;; return.
    (try
      (cmd/run-commands [["git" "clone" "--branch" base-branch-name
                          (str "https://github.com/" org "/" repo) branch-name
                          :dir (get-workspace-dir project-name)]
                         ["sh" "-c"
                          (str "grep -qs '.git-credentials' .gitignore || "
                               "echo '.git-credentials' >> .gitignore")
                          :dir new-branch-dir]])
      (store-creds all-branches project-name branch-name request)
      (cmd/run-commands [["sh" "-c" (str "git config credential.helper "
                                         "'store --file=.git-credentials'")
                          :dir new-branch-dir]
                         ["git" "config" "--local" "color.ui" "always" :dir new-branch-dir]
                         ["git" "config" "user.name" (get-config :github-user-name)
                          :dir new-branch-dir]
                         ["git" "config" "user.email" (get-config :github-user-email)
                          :dir new-branch-dir]
                         ["git" "checkout" "-b" branch-name :dir new-branch-dir]
                         ["git" "push" "--set-upstream" "origin" branch-name :dir new-branch-dir]
                         ["git" "fetch" :dir new-branch-dir]])
      (remove-creds all-branches project-name branch-name)
      (catch Exception e
        (log/error (.getMessage e))
        (delete-recursively new-branch-dir)
        all-branches)))
  (-> all-branches
      (get (keyword project-name))
      (merge (initialize-branch project-name branch-name))
      (#(assoc all-branches (keyword project-name) %))))

(def local-branches
  "An agent to handle access to the hashmap that contains info on all of the branches managed by the
  server instance."
  (-> :projects
      (get-config)
      (keys)
      (#(refresh-local-branches {} % true))
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Code related to general container management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn remove-branch-containers-for-project
  "Remove all containers associated with managed branches for a given project"
  [project-name]
  (doseq [branch-name (-> @local-branches (get (keyword project-name)) (keys) (#(map name %)))]
    (remove-branch-container project-name branch-name)))

(defn remove-branch-containers
  "Remove all containers associated with managed branches for all projects."
  []
  (doseq [project-name (-> :projects (get-config) (keys))]
    (remove-branch-containers-for-project project-name)))

(defn pause-branch-containers-for-project
  "If pause? is true, then pause all containers associated with managed branches for the given
  project, otherwise unpause them."
  [project-name pause?]
  (doseq [branch-name (-> @local-branches (get (keyword project-name)) (keys) (#(map name %)))]
    (pause-branch-container project-name branch-name pause?)))

(defn pause-branch-containers
  "If pause? is true, then pause all containers associated with managed branches for all projects,
  otherwise unpause them."
  [pause?]
  (doseq [project-name (-> :projects (get-config) (keys))]
    (pause-branch-containers-for-project project-name pause?)))

;; Example usage: (send-off container-serializer func arg)
(def container-serializer
  "An agent that has no role other than to be used to serialize calls to the containers."
  (agent {} :error-mode :continue, :error-handler default-agent-error-handler))

(defn rebuild-branch-container
  "Rebuild the docker container for a single branch (unless docker is disabled for the branch's
  project). This function is serialisable through a branch agent."
  [{:keys [branch-name project-name] :as branch}]
  (let [docker-name (str project-name "-" branch-name)
        ws-dir (get-workspace-dir project-name branch-name)
        docker-config (get-docker-config project-name)]
    (if (:disabled? docker-config)
      (do
        (log/info "Docker configuration is inactive for project:" project-name)
        branch)
      (do
        (log/info "Removing branch container" docker-name)
        (remove-branch-container project-name branch-name)
        (log/info "Building/pulling image and container for branch" docker-name)
        (if (-> ws-dir (str "/Dockerfile") (io/file) (.exists))
          ;; If the branch directory contains a Dockerfile, use it to create a branch-specific image
          (create-image-and-container-for-branch branch docker-name)
          ;; Otherwise the project-level default will be used
          (create-image-and-container-for-branch branch))))))

(defn rebuild-images-and-containers
  "Given a project name, if the project has been configured to use docker, pull the image specified
  in the config file. If there are Dockerfiles on any of the local branches, use them to build
  branch-specific images. Additionally, create the containers for all of the local branches. The
  first argument to this function, `_`, exists to make it possible to serialize calls to this
  function through an agent, but is otherwise unused."
  [_ project-name]
  (let [ws-dir (get-workspace-dir project-name)
        docker-config (get-docker-config project-name)]
    (if (:disabled? docker-config)
      (log/info "Docker configuration is inactive for project:" project-name)
      (do
        (log/info "Removing branch containers for project:" project-name)
        (remove-branch-containers-for-project project-name)
        (log/info "Building/pulling images and containers for project" project-name)
        (doseq [branch-name (.list (io/file ws-dir))]
          (let [image-ref (str project-name "-" branch-name)
                branch-agent (-> @local-branches (get (keyword project-name))
                                 (get (keyword branch-name)))]
            (when branch-agent
              (if (-> ws-dir (str "/" branch-name "/Dockerfile") (io/file) (.exists))
                (send-off branch-agent create-image-and-container-for-branch
                          (str project-name "-" branch-name))
                (send-off branch-agent create-image-and-container-for-branch)))))))))

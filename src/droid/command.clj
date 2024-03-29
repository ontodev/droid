(ns droid.command
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [me.raynes.conch.low-level :as sh]
            [droid.agent :refer [default-agent-error-handler]]
            [droid.config :refer [get-config get-docker-config get-env]]
            [droid.fileutils :refer [get-workspace-dir get-temp-dir]]
            [droid.log :as log]))

(defn throw-process-exception
  "Throw an exception using the error stream associated with the given process"
  [process summary]
  (-> summary (str ": " (sh/stream-to-string process :err)) (Exception.) (throw)))

(defn local-to-docker
  "Given a command, a project name, and a branch name, replace the local workspace and temp
  directories, wherever they appear in the command, with the docker workspace and temp directories,
  respectively, that have been configured for the project."
  [project-name branch-name command]
  (let [local-workspace-dir (get-workspace-dir project-name branch-name)
        local-tmp-dir (get-temp-dir project-name branch-name)
        docker-config (get-docker-config project-name)
        docker-workspace-dir (:workspace-dir docker-config)
        docker-tmp-dir (:temp-dir docker-config)
        make-switch #(if-not (string? %)
                       %
                       (-> %
                           (string/replace (re-pattern local-workspace-dir) docker-workspace-dir)
                           (string/replace (re-pattern local-tmp-dir) docker-tmp-dir)))]
    (log/debug "Mapping" local-workspace-dir "to" docker-workspace-dir "and" local-tmp-dir "to"
               docker-tmp-dir "in container.")
    (cond
      ;; If the command is a single string, then make the substitution in it and return the result:
      (string? command)
      (make-switch command)

      ;; If the command is a collection, then for every item in it, if it is a string and it does
      ;; not have a keyword immediately preceding it in the collection, make the substitution. If
      ;; it does have a keyword preceding it, the substitution will possibly happen later.
      (coll? command)
      (->> command
           (map-indexed (fn [index value]
                          (if (and (-> index (#(nth command % nil)) (string?))
                                   (-> index (dec) (#(nth command % nil)) (keyword?) (not)))
                            (make-switch value)
                            value)))))))

(defn- get-option-value
  "Looks in `command` (a vector) for the given keyword, and once it is found, returns
  the next item in the vector"
  [command option-keyword]
  (let [keyword-index (.indexOf command option-keyword)]
    (when (and (>= keyword-index 0) (< (inc keyword-index) (count command)))
      (nth command (inc keyword-index)))))

(defn supplement-command-env
  "Supplements the given command's environment with the given extra environment variables"
  [command extra-env]
  (let [env-map (or (get-option-value command :env) {})
        supplemented-env-map (->> extra-env (merge env-map))]
    (if (empty? env-map)
      (->> command
           (remove #(= :env %))
           (vec)
           (#(into % [:env supplemented-env-map])))
      (->> command
           (map (fn [item]
                  (if (= item env-map)
                    supplemented-env-map
                    item)))))))

(defn run-command
  "Run the given command, then return a vector containing (1) the process that was created, and (2)
  its exit code wrapped in a future. Note that `command` must be a vector representing the function
  arguments that will be sent to (me.raynes.conch.low-level/proc). If `timeout` has been specified,
  then the command will run for no more than the specified number of milliseconds."
  [command & [timeout {:keys [project-name branch-name container-id] :as container-info}]]
  (let [;; Only log string portions of the command since other parts may contain secrets:
        command-log (->> command (filter string?) (string/join " "))
        process (try
                  (if-not container-id
                    (do (log/debug "Running" command-log "without a container")
                        (apply sh/proc command))
                    (let [docker-config (get-docker-config project-name)
                          docker-cmd-base (->> docker-config (get-env)
                                               (supplement-command-env command))
                          ;; For each key in the environment map,
                          ;; {:VAR1 "var1-value" :VAR2 "var2-value" ...}, generate command-line
                          ;; arguments for docker like: -e VAR1 -e VAR2 -e VAR3 ...
                          ;; (we don't need to specify the values of these variables because sh/proc
                          ;; will make those available to the docker container through the map):
                          env-map-to-cli-opts (fn [env-map]
                                                (->> env-map
                                                     (map #(key %))
                                                     (map #(into ["-e"] [%]))
                                                     (apply concat)
                                                     (vec)))
                          ;; Get the working dir from `command`, defaulting to whatever is in the
                          ;; docker config:
                          get-working-dir #(->> (or (get-option-value command :dir)
                                                    (:default-working-dir docker-config))
                                                (str "/"))]
                      (log/debug "Running" command-log "in container" container-id)
                      (->> docker-cmd-base
                           (local-to-docker project-name branch-name)
                           (into [container-id])
                           (into (-> docker-config (get-env) (env-map-to-cli-opts)))
                           (into (-> (get-option-value command :env) (env-map-to-cli-opts)))
                           (into ["docker" "exec" "--workdir"
                                  (->> (get-working-dir)
                                       (local-to-docker project-name branch-name))])
                           (apply sh/proc))))
                  (catch Exception e
                    ;; If an exception is thrown by sh/proc, launch an echo command with the
                    ;; exception message, which the caller can use to write to the console:
                    (apply sh/proc ["sh" "-c" (str "echo \"" (-> e (str)
                                                                 (string/escape char-escape-string))
                                                   "\" 1>&2; exit 1")])))
        exit-code (-> process
                      (#(if timeout
                          (sh/exit-code % timeout)
                          (sh/exit-code %)))
                      (#(do (when timeout (log/debug "Timeout is set:" timeout)) %))
                      (future))]
    (vector process exit-code)))

(defn run-commands
  "Run all the shell commands from the given command list. If any of them return an error code,
  throw an exception. Meant for quick commands that will not run for a signnificant length of time"
  [command-list & [timeout]]
  (doseq [command command-list]
    (let [[process exit-code] (run-command command timeout)]
      ;; Note the blocking dereference here. No long running commands should use this function.
      (when-not (= @exit-code 0)
        (-> (str "Error while running '" command "': ")
            (str (sh/stream-to-string process :err))
            (Exception.)
            (throw))))))

(defn program-exists?
  "Returns true if the given program can be found in the command $PATH"
  [program]
  (let [[process exit-code] (run-command ["which" program])]
    (when (not= @exit-code 0)
      (log/debug "Program:" program "does not exist"))
    (or (= @exit-code 0) false)))

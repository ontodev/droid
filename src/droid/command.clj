(ns droid.command
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [me.raynes.conch.low-level :as sh]
            [droid.agent :refer [default-agent-error-handler]]
            [droid.config :refer [get-config]]
            [droid.fileutils :refer [get-workspace-dir get-temp-dir]]
            [droid.log :as log]))

;; Example usage: (send-off container-serializer func arg)
(def container-serializer
  "An agent that has no role other than to be used to serialize calls to the containers."
  (agent {} :error-mode :continue, :error-handler default-agent-error-handler))

(defn rebuild-container-image
  "Given a project name, if the project has been configured to use docker, and if the master
  branch of the project has been checked out locally and contains a Dockerfile, then build
  the image according to the instructions in the Dockerfile. The first argument,
  `dummy-agent-content`, exists to make it possible to serialize calls to this function through
  an agent, but is otherwise unused."
  [dummy-agent-content project-name]
  (let [ws-dir (get-workspace-dir project-name)
        docker-config (-> :projects (get-config) (get project-name) :docker-config)]
    (cond
      (not (:active? docker-config))
      (log/info "Docker configuration is inactive for project:" project-name)

      ;; TODO: If there is no master branch, then look for any other local branch and use it instead
      (-> ws-dir (str "/master") (io/file) (.exists) (not))
      (log/warn "No master branch exists for project:" project-name "; not building docker image")

      (-> ws-dir (str "/master/Dockerfile") (io/file) (.exists) (not))
      (log/warn "No Dockerfile on master branch of project:" project-name
                "; not building docker image")

      :else
      (let [process (sh/proc "docker" "build" "--tag" (:image docker-config) "."
                             :dir (str ws-dir "/master"))
            ;; Redirect process's stdout to our stdout:
            output (future (sh/stream-to-out process :out))
            exit-code (future (sh/exit-code process))]
        (log/info "Building docker image" (:image docker-config) "...")
        (when-not (= @exit-code 0)
          (throw (Exception. (str "Error while building image: "
                                  (sh/stream-to-string process :err)))))
        (log/info "Docker image built for" (:image docker-config))))))

(defn- container-for
  "Retrieves the container ID corresponding to the given branch if it exists, or if it doesn't
  exist, creates one, starts it, and then returns the newly created container ID."
  [{:keys [branch-name project-name] :as branch}]
  (let [docker-config (-> :projects (get-config) (get project-name) :docker-config)

        throw-exception (fn [process summary]
                          (-> summary
                              (str ": " (sh/stream-to-string process :err))
                              (Exception.)
                              (throw)))

        create-container (fn [container-name]
                           ;; Creates a docker container encapsulating a bash shell:
                           (log/debug "Creating container for" container-name "with docker config:"
                                      docker-config)
                           (let [root-dir (.getCanonicalPath (clojure.java.io/file "."))
                                 ws-dir (get-workspace-dir project-name branch-name)
                                 tmp-dir (get-temp-dir project-name branch-name)
                                 process (sh/proc
                                          "docker" "create" "--interactive" "--tty"
                                          "--name" container-name
                                          "--workdir" (:work-dir docker-config)
                                          "--volume" (str root-dir "/" ws-dir ":/" ws-dir)
                                          "--volume" (str root-dir "/" tmp-dir ":/" tmp-dir)
                                          (:image docker-config)
                                          (:shell-command docker-config))
                                 exit-code (future (sh/exit-code process))]
                             (when-not (= @exit-code 0)
                               (throw-exception process "Error while creating container"))
                             ;; The container id can be retrieved from STDOUT:
                             (-> (sh/stream-to-string process :out)
                                 (string/trim-newline))))

        start-container (fn [container-id-or-name]
                          ;; Starts the docker container with the given container ID (or name):
                          (log/debug "Starting container:" container-id-or-name)
                          (let [process (sh/proc "docker" "start" container-id-or-name)
                                exit-code (future (sh/exit-code process))]
                            (when-not (= @exit-code 0)
                              (throw-exception process "Error while starting container"))
                            ;; Return the container id (or name) that was passed in, for threading:
                            container-id-or-name))

        get-container (fn [container-name]
                        (let [process (sh/proc "docker" "ps" "-q" "-f" (str "name=" container-name))
                              exit-code (future (sh/exit-code process))]
                          (when-not (= @exit-code 0)
                            (throw-exception process "Error retrieving container"))
                          ;; If there is no such container the output will be an empty string,
                          ;; otherwise it will be the container id. In the former case send it
                          ;; back as nil:
                          (-> (sh/stream-to-string process :out)
                              (string/trim-newline)
                              (#(if (empty? %)
                                  (log/debug "No container found for" container-name)
                                  %)))))]

    ;; If the project is configured to use docker then return either its existing container's ID, or
    ;; create one, start it, and return its container ID:
    (cond (nil? branch)
          (log/debug "No branch specified; not looking for a container.")

          (not (:active? docker-config))
          (log/info "Docker configuration inactive. Not retrieving container.")

          :else
          (let [container-name (-> project-name (str "-" branch-name))]
            (log/debug "Looking for an existing container for branch:" branch-name
                       "in project:" project-name)
            (or (get-container container-name)
                ;; If there is no existing container for this branch, create one and start it:
                (try
                  (->> container-name (create-container) (start-container))
                  (catch Exception e
                    (log/error (.getMessage e))
                    (when (and (->> :op-env (get-config) (= :dev))
                               (->> :log-level (get-config) (= :debug)))
                      (.printStackTrace e)))))))))


;; TODO: Need to delete a container when its branch is deleted, and need to gracefully shut down, deleting all containers.

;; TODO: Remove this comment:
;; To debug this in Leiningen:
;; (use '[me.raynes.conch.low-level :as sh])
;; (let [branch (->> @local-branches :for-testing-with-droid :master (deref)) [process exit-code] (run-command ["ls" "-l" :dir "projects/for-testing-with-droid/workspace/master" :env {"fass" "shoal", "crick" "reed"}] nil branch)] (-> (sh/stream-to-string process :out) (println)))
;; or:
;; (let [branch (->> @local-branches :for-testing-with-droid :master (deref)) [process exit-code] (run-command ["bash" "-c" "exec echo $fass" :dir "projects/for-testing-with-droid/workspace/master" :env {"fass" "shoal", "crick" "reed"}] nil branch)] (-> (sh/stream-to-string process :out) (println)))


(defn run-command
  "Run the given command, then return a vector containing (1) the process that was created, and (2)
  its exit code wrapped in a future. Note that `command` must be a vector representing the function
  arguments that will be sent to (me.raynes.conch.low-level/proc). If `timeout` has been specified,
  then the command will run for no more than the specified number of milliseconds."
  [command & [timeout {:keys [project-name] :as branch}]]
  (log/debug "Request to run command:" command "with timeout:" (or timeout "none"))
  (let [docker-config (-> :projects (get-config) (get project-name) :docker-config)
        container-id (container-for branch)
        get-option-value (fn [option-keyword]
                           ;; Looks in `command` (a vector of strings) for the given keyword, and
                           ;; once it is found, returns the next item in the vector.
                           (let [keyword-index (.indexOf command option-keyword)]
                             (when (and (>= keyword-index 0) (< (inc keyword-index) (count command)))
                               (nth command (inc keyword-index)))))
        get-work-dir #(->> (or (get-option-value :dir)
                               (:work-dir docker-config))
                           (str "/"))
        parse-env (fn []
                    ;; If the `command` vector contains an environment, e.g.,
                    ;;   [... :env {:VAR1 "var1-value" :VAR2 "var2-value" ...} ...]
                    ;; then for each environment variable, generate command-line arguments like:
                    ;;   -e VAR1 -e VAR2 -e VAR3 ...
                    ;; We don't need to specify the values of these arguments because docker reads
                    ;; them from the environment of its caller, which includes them.
                    (->> (get-option-value :env)
                         (map #(key %))
                         (map #(into ["-e"] [%]))
                         (apply concat)
                         (vec)))
        process (if container-id
                  (do (->> command
                           (into [container-id])
                           (into (parse-env))
                           (into ["docker" "exec" "--workdir" (get-work-dir)])
                           (#(do (log/debug "Running" (->> % (string/join " ")) "in container"
                                            container-id)
                                 %))
                           (apply sh/proc)))
                  (do (log/debug "Running" (->> command (string/join " ")) "without a container")
                      (apply sh/proc command)))
        exit-code (-> process
                      (#(if timeout
                          (sh/exit-code % timeout)
                          (sh/exit-code %)))
                      (future))]
    (vector process exit-code)))

(defn run-commands
  "Run all the shell commands from the given command list. If any of them return an error code,
  throw an exception."
  [command-list & [timeout branch]]
  (doseq [command command-list]
    (let [[process exit-code] (run-command command timeout branch)]
      (when-not (= @exit-code 0)
        (-> (str "Error while running '" command "': ")
            (str (sh/stream-to-string process :err))
            (Exception.)
            (throw))))))

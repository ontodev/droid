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

(defn- throw-process-exception
  "Throw an exception using the error stream associated with the given process"
  [process summary]
  (-> summary (str ": " (sh/stream-to-string process :err)) (Exception.) (throw)))

(defn- lookup-image
  "Given a reference to an image, lookup its ID."
  [image-ref]
  (let [process (sh/proc "docker" "images" "-q" "-f" (str "reference=" image-ref))
        exit-code (future (sh/exit-code process))]
    (when-not (= @exit-code 0)
      (throw-process-exception process "Error looking up image"))
    ;; If there is no such image the output will be an empty string,
    ;; otherwise it will be the image id. In the former case send it
    ;; back as nil:
    (-> (sh/stream-to-string process :out)
        (string/trim-newline)
        (#(if (empty? %)
            (log/debug "No image found for" image-ref)
            %)))))

;; TODO: Call this function using send-off after creating and checking out a branch.
(defn create-image-for-branch
  "Create a new branch-specific image using the given reference as the argument to --tag"
  [{:keys [branch-name project-name] :as branch} image-ref]
  (log/info "Creating docker image" image-ref "...")
  (let [command-base (str "docker build --tag " image-ref " .")
        command ["bash" "-c" (str command-base " > " (get-temp-dir project-name branch-name)
                                  "/console.txt 2>&1")
                 :dir (get-workspace-dir project-name branch-name)]
        process (apply sh/proc command)
        exit-code (future (sh/exit-code process))]
    (assoc branch
           :action "create docker image"
           :command command-base
           :process process
           :start-time (System/currentTimeMillis)
           :cancelled false
           :exit-code exit-code)))

(defn rebuild-container-images
  "Given a project name, if the project has been configured to use docker, pull the image specified
  in the config file. Additionally, if there are Dockerfiles on any of the local branches, use them
  to build branch-specific images. The first argument to this function, `_`, exists to make it
  possible to serialize calls to this function through an agent, but is otherwise unused."
  [_ project-name]
  (let [ws-dir (get-workspace-dir project-name)
        docker-config (-> :projects (get-config) (get project-name) :docker-config)]
    (if (not (:active? docker-config))
      (log/info "Docker configuration is inactive for project:" project-name)
      (do
        ;; Pull the project-level image:
        (let [command ["docker" "pull" (:image docker-config)]
              process (apply sh/proc command)
              ;; Redirect process's stdout to our stdout:
              output (future (sh/stream-to-out process :out))
              exit-code (future (sh/exit-code process))]
          (log/info "Retrieving docker image" (:image docker-config))
          (when-not (= @exit-code 0)
            (throw (Exception. (str "Error while retrieving image: "
                                    (sh/stream-to-string process :err)))))
          (log/info "Docker image" (:image docker-config) "retrieved"))
        ;; Now build any branch-specific images for branches that have a Dockerfile in their
        ;; top-level directory, naming the image by joining the project and branch names with "-".
        (doseq [branch-name (.list (io/file ws-dir))]
          (when (-> ws-dir (str "/" branch-name "/Dockerfile") (io/file) (.exists))
            (let [image-ref (str project-name "-" branch-name)
                  dockerfile-dir (str ws-dir "/" branch-name)
                  command ["docker" "build" "--tag" image-ref "." :dir dockerfile-dir]
                  process (apply sh/proc command)
                  ;; Redirect process's stdout to our stdout:
                  output (future (sh/stream-to-out process :out))
                  exit-code (future (sh/exit-code process))]
              (log/info "Building docker image" image-ref "using Dockerfile in" dockerfile-dir)
              (when-not (= @exit-code 0)
                (throw (Exception. (str "Error while building image: "
                                        (sh/stream-to-string process :err)))))
              (log/info "Docker image" image-ref "built"))))))))

(defn remove-container
  "Given a project and branch name, remove the corresponding container."
  [project-name branch-name]
  (let [process (sh/proc "docker" "rm" "-f" (-> project-name (str "-" branch-name)))
        exit-code (sh/exit-code process)]
    (let [error-msg (sh/stream-to-string process :err)]
      (if-not (= exit-code 0)
        (when-not (re-find #"No such container" error-msg)
          (throw (Exception. (str "Error removing image: " error-msg))))
        (log/info "Removed docker container for branch:" branch-name "in project:" project-name)))))

(defn- container-for
  "Retrieves the container ID corresponding to the given branch if it exists, or if it doesn't
  exist, creates one (along with its image if that also doesn't exist), starts it, and then returns
  the newly created container ID."
  [{:keys [branch-name project-name] :as branch}]
  (let [docker-config (-> :projects (get-config) (get project-name) :docker-config)

        get-image (fn []
                    ;; Get the image corresponding to this project and branch and return its ID.
                    ;; Throw an exception if it doesn't exist.
                    (let [branch-dir (get-workspace-dir project-name branch-name)
                          image-id (if-not (-> branch-dir (str "/Dockerfile") (io/file) (.exists))
                                     (-> docker-config :image (lookup-image))
                                     (-> (str project-name "-" branch-name) (lookup-image)))]
                      (when-not image-id
                        (-> (str "Project-level docker image for " project-name
                                 " does not exist") (Exception.) (throw)))
                      ;; Return the image-id:
                      image-id))

        create-container (fn [container-name image-id]
                           ;; Creates a docker container encapsulating a shell:
                           (log/debug "Creating container for" container-name "with docker config:"
                                      docker-config)
                           (let [ws-dir (-> (get-workspace-dir project-name branch-name) (str "/"))
                                 tmp-dir (-> (get-temp-dir project-name branch-name) (str "/"))
                                 process (sh/proc
                                          "docker" "create" "--interactive" "--tty"
                                          "--name" container-name
                                          "--volume" (str ws-dir ":" (:workspace-dir docker-config))
                                          "--volume" (str tmp-dir ":" (:temp-dir docker-config))
                                          image-id
                                          (:shell-command docker-config))
                                 exit-code (future (sh/exit-code process))]
                             (when-not (= @exit-code 0)
                               (throw-process-exception process "Error while creating container"))
                             ;; The container id can be retrieved from STDOUT:
                             (-> (sh/stream-to-string process :out)
                                 (string/trim-newline))))

        start-container (fn [container-id-or-name]
                          ;; Starts the docker container with the given container ID (or name):
                          (log/debug "Starting container:" container-id-or-name)
                          (let [process (sh/proc "docker" "start" container-id-or-name)
                                exit-code (future (sh/exit-code process))]
                            (when-not (= @exit-code 0)
                              (throw-process-exception process "Error while starting container"))
                            ;; Return the container id (or name) that was passed in, for threading:
                            container-id-or-name))

        get-container (fn [container-name]
                        (let [process (sh/proc "docker" "ps" "-q" "-f" (str "name=" container-name))
                              exit-code (future (sh/exit-code process))]
                          (when-not (= @exit-code 0)
                            (throw-process-exception process "Error retrieving container"))
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
    (cond
      (nil? branch)
      (log/debug "No branch specified; not looking for a container.")

      (not (:active? docker-config))
      (log/debug "Docker configuration inactive. Not retrieving container.")

      :else
      (let [image-id (get-image)
            container-name (-> project-name (str "-" branch-name))]
        (or (get-container container-name)
            ;; If there is no existing container for this branch, create one and start it:
            (try
              (-> container-name (create-container image-id) (start-container))
              (catch Exception e
                (log/error (.getMessage e))
                (when (and (->> :op-env (get-config) (= :dev))
                           (->> :log-level (get-config) (= :debug)))
                  (.printStackTrace e)))))))))

(defn local-to-docker
  "Given a command, a project name, and a branch name, replace the local workspace and temp
  directories, wherever they appear in the command, with the docker workspace and temp directories,
  respectively, that have been configured for the project."
  [project-name branch-name command]
  (let [local-workspace-dir (get-workspace-dir project-name branch-name)
        local-tmp-dir (get-temp-dir project-name branch-name)
        docker-config (-> :projects (get-config) (get project-name) :docker-config)
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

(defn run-command
  "Run the given command, then return a vector containing (1) the process that was created, and (2)
  its exit code wrapped in a future. Note that `command` must be a vector representing the function
  arguments that will be sent to (me.raynes.conch.low-level/proc). If `timeout` has been specified,
  then the command will run for no more than the specified number of milliseconds."
  [command & [timeout {:keys [project-name branch-name] :as branch}]]
  (log/debug "Request to run command:" (->> command (filter string?) (string/join " "))
             "with timeout:" (or timeout "none"))
  (let [docker-config (-> :projects (get-config) (get project-name) :docker-config)
        container-id (container-for branch)
        ;; Looks in `command` (a vector of strings that has been passed to the outer function) for
        ;; the given keyword, and once it is found, returns the next item in the vector:
        get-option-value (fn [option-keyword]
                           (let [keyword-index (.indexOf command option-keyword)]
                             (when (and (>= keyword-index 0) (< (inc keyword-index) (count command)))
                               (nth command (inc keyword-index)))))
        ;; Supplements `command` with extra information from the docker configuration for the given
        ;; project:
        get-docker-cmd-base (fn []
                              (let [env-map (or (get-option-value :env) {})
                                    supplemented-env-map (->> docker-config :env (merge env-map))]
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
        ;; For each key in the environment map, {:VAR1 "var1-value" :VAR2 "var2-value" ...},
        ;; generate command-line arguments for docker like:
        ;;   -e VAR1 -e VAR2 -e VAR3 ...
        ;; (we don't need to specify the values of these variables because sh/proc will make those
        ;; available to the docker container through the environment map):
        env-map-to-cli-opts (fn [env-map]
                              (->> env-map
                                   (map #(key %))
                                   (map #(into ["-e"] [%]))
                                   (apply concat)
                                   (vec)))
        ;; Get the working dir from `command`, defaulting to whatever is in the docker config:
        get-working-dir #(->> (or (get-option-value :dir)
                                  (:default-working-dir docker-config))
                              (str "/"))
        process (if container-id
                  (do (log/debug "Running" (->> command (filter string?) (string/join " "))
                                 "in container" container-id)
                      (->> (get-docker-cmd-base)
                           (local-to-docker project-name branch-name)
                           (into [container-id])
                           (into (-> docker-config :env (env-map-to-cli-opts)))
                           (into (-> (get-option-value :env) (env-map-to-cli-opts)))
                           (into ["docker" "exec" "--workdir"
                                  (->> (get-working-dir)
                                       (local-to-docker project-name branch-name))])
                           (apply sh/proc)))
                  (do (log/debug "Running" (->> command (filter string?) (string/join " "))
                                 "without a container")
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

(defn executable?
  "Determine whether the given script-path is executable. If a branch is provided and the branch has
  a container, then check whether the script is executable in that container, otherwise check if it
  is executable from the server's point of view."
  [script-path & [{:keys [project-name branch-name] :as branch}]]
  (let [container-id (container-for branch)]
    (if-not container-id
      (-> script-path (io/file) (.canExecute))
      (let [[process exit-code] (run-command ["test" "-x" script-path] nil branch)]
        (or (= @exit-code 0) false)))))

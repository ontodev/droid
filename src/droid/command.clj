(ns droid.command
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [me.raynes.conch.low-level :as sh]
            [droid.agent :refer [default-agent-error-handler]]
            [droid.config :refer [get-config]]
            [droid.fileutils :refer [get-workspace-dir get-temp-dir]]
            [droid.log :as log]))

;; TODO: Need to delete a container when its branch is deleted, and need to gracefully shut down, deleting all containers.

;; To debug in Leiningen:
;; (use '[me.raynes.conch.low-level :as sh])
;; (let [[process exit-code] (run-command ["ls" "-l"] nil (-> @droid.branches/local-branches :for-testing-with-droid :master (deref)))] (-> (sh/stream-to-string process :out) (println)))
(defn- container-for
  "Retrieves the container ID corresponding to the given branch if it exists, or if it doesn't
  exist then create one and start it and return the newly created container ID."
  [{:keys [branch-name project-name Dockerfile] :as branch}]
  (let [throw-exception (fn [process summary]
                          (-> summary
                              (str ": " (sh/stream-to-string process :err))
                              (Exception.)
                              (throw)))

        create-container (fn [container-name]
                           ;; Creates a docker container encapsulating a bash shell:
                           (log/debug "Creating container for" container-name)
                           (let [root-dir (.getCanonicalPath (clojure.java.io/file "."))
                                 ws-dir (get-workspace-dir project-name branch-name)
                                 tmp-dir (get-temp-dir project-name branch-name)
                                 process (sh/proc
                                          "docker" "create" "--interactive" "--tty"
                                          "--name" container-name
                                          "--workdir" "/"
                                          "--volume" (str root-dir "/" ws-dir ":/" ws-dir)
                                          "--volume" (str root-dir "/" tmp-dir ":/" tmp-dir)
                                          (get-config :docker-image)
                                          "bash")
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

    ;; If the branch has a Dockerfile then return either its existing container's ID, or create one,
    ;; start it, and return its container ID:
    (when Dockerfile
      (let [container-name (-> project-name (str "-" branch-name))]
        (or (get-container container-name)
            (->> container-name (create-container) (start-container)))))))

(defn run-command
  "Run the given command, then return a vector containing (1) the process that was created, and (2)
  its exit code wrapped in a future. Note that `command` must be a vector representing the function
  arguments that will be sent to (me.raynes.conch.low-level/proc). If `timeout` has been specified,
  then the command will run for no more than the specified number of milliseconds."
  [command & [timeout branch]]
  (let [container-id (container-for branch)
        process (if container-id
                  (do (log/debug "Running" (->> command (string/join " ")) "in container"
                                 container-id)
                      (->> command
                           (into ["docker" "exec" container-id])
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
  [command-list]
  (doseq [command command-list]
    (let [[process exit-code] (run-command command)]
      (when-not (= @exit-code 0)
        (-> (str "Error while running '" command "': ")
            (str (sh/stream-to-string process :err))
            (Exception.)
            (throw))))))

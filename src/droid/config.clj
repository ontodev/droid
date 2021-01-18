(ns droid.config)

(def default-config
  "The default configuration that will be used if the configuration file cannot be found."
  {:projects {}

   ;; Application settings (should be one of :dev, :test, :prod)
   :op-env :dev

   :server-port {:dev 8090, :test 8090, :prod 8090}

   ;; Should be one of :debug, :info, :warn, :error, :fatal
   :log-level {:dev :debug, :test :info, :prod :info}

   ;; false for http, true for https
   :secure-site {:dev false, :test true, :prod true}

   ;; List of userids that are considered site administrators:
   :site-admin-github-ids {:dev #{""}, :test #{""}, :prod #{""}}

   ;; Time in milliseconds to wait for a CGI script to finish:
   :cgi-timeout {:dev 60000, :test 60000, :prod 60000}

   ;; File to write logging output to. If nil, stderr is used:
   :log-file {:dev nil, :test "droid.log", :prod "droid.log"}

   ;; Bootstrap colors to use for background and text in <body>
   :html-body-colors "bg-white"})

(def config
  "A map of configuration parameters, loaded from the config.edn file in the data directory.
  If that file does not exist, return a default configuration hashmap that is suitable for
  unit (dev) testing."
  (let [config-filename "config.edn"]
    (try
      (->> config-filename
           (slurp)
           (clojure.edn/read-string))
      (catch java.io.FileNotFoundException e
        default-config))))

(defn get-config
  "Get the configuration parameter corresponding to the given keyword. If the parameter has
  entries for different operating environments, get the one corresponding to the currently
  configured operating environment (:dev, :test, or :prod), given by the :op-env parameter in the
  config file."
  [param-keyword]
  (let [op-env (:op-env config)
        param-rec (-> config (get param-keyword))]
    (if (and (map? param-rec)
             (contains? param-rec op-env))
      (get param-rec op-env)
      (get config param-keyword))))

(defn get-docker-config
  "Returns the docker config for a project, or if no docker config is defined for the project,
  returns the default docker config"
  [project-name]
  (or (-> :projects (get-config) (get project-name) :docker-config)
      (get-config :docker-config)))


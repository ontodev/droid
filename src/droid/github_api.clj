(ns droid.github-api
  (:require [clojure.string :as str]
            [tentacles.core :refer [api-call]]
            [tentacles.repos :refer [branches]]
            [droid.config :refer [config]]
            [droid.log :as log]))

(defn get-project-permissions
  "Given a GitHub login and an OAuth2 token, returns a hash-map specifying the user's permissions
  for every project managed by the server instance."
  [login token]
  (->> config
       :projects
       (map (fn [project]
              ;; Corresponding to each managed project is a mapping from a keywordized project
              ;; name to the user's GitHub permissions on that project, which can be one of:
              ;; "admin", "write", or "read". We set it to nil if the user is not authorized on a
              ;; project. For example: {:project-1 "admin"
              ;;                        :project-2 nil
              ;;                        :project-3 "read"
              ;;                        ... }
              (hash-map (-> project (key) (keyword))
                        (-> project
                            (val)
                            (get :github-coordinates)
                            (str/split #"/")
                            (#(api-call :get "repos/%s/%s/collaborators/%s/permission"
                                        [(first %) (second %) login] {:oauth-token token}))
                            (#(or (:permission %)
                                  (do (log/error "Unable to retrieve permissions for" login
                                                 "on project" (key project) "with reason:"
                                                 (-> % :body :message)
                                                 (str "(see: " (-> % :body :documentation_url) ")"))
                                      nil)))))))
       (apply merge)))

(defn get-remote-branches
  "Call the GitHub API to get the list of remote branches for the given project, using "
  [project-name {:keys [login token] :as options}]
  (let [[org repo] (-> config
                       :projects
                       (get project-name)
                       :github-coordinates
                       (str/split #"/"))
        remote-branches (do
                          (-> "Querying GitHub repo "
                              (str org "/" repo)
                              (str " for list of remote branches of project " project-name)
                              (#(str % (when login (str " on behalf of " login))))
                              (log/info))
                          (branches org repo options))]

    (if (= (type remote-branches) clojure.lang.PersistentHashMap)
      (do (log/error
           "Request to retrieve remote branches of project" project-name
           "failed with reason:" (-> remote-branches :body :message)
           (str "(see: " (-> remote-branches :body :documentation_url) ")"))
          [])
      (vec remote-branches))))

(ns droid.github-api
  (:require [clojure.string :as str]
            [tentacles.repos :as repos]
            [droid.config :refer [config]]
            [droid.log :as log]))

(defn project-permissions
  "Given a GitHub login and an OAuth2 token, returns a vector specifying the user's permissions
  for every project managed by the server instance."
  [login token]
  (->> config
       :projects
       (map (fn [project]
              ;; Corresponding to each managed project is a hash-map, mapping keywordized project
              ;; names to a further hash-map containing the user's GitHub permissions on that
              ;; project, or nil if the user is not authorized on that project.
              ;; For example: [{:project-1 {:admin true, :push true, :pull true}}
              ;;               {:project-2 nil}
              ;;               {:project-3 {:admin true, :push true, :pull true}}
              ;;               ... ]
              (hash-map (-> project (key) (keyword))
                        (-> project
                            (val)
                            (get :github-coordinates)
                            (str/split #"/")
                            (#(hash-map :org (first %) :repo (second %)))
                            (#(repos/collaborators (:org %) (:repo %) {:oauth-token token}))
                            (#(filter (fn [collab]
                                        (= login (:login collab)))
                                      %))
                            (first)
                            :permissions))))
       (vec)))

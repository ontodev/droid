(ns droid.github-api
  (:require [clojure.string :as str]
            [tentacles.repos :as repos]
            [droid.config :refer [config]]
            [droid.log :as log]))

(defn project-permissions
  "Given a GitHub login and an OAuth2 token, returns a hash-map specifying the user's permissions
  for every project managed by the server instance."
  [login token]
  (->> config
       :projects
       (map (fn [project]
              ;; Corresponding to each managed project is a mapping from a keywordized project
              ;; name to a hash-map containing the user's GitHub permissions on that project,
              ;; or nil if the user is not authorized on that project.
              ;; For example: {:project-1 {:admin true, :push true, :pull true}
              ;;               :project-2 nil
              ;;               :project-3 {:admin true, :push true, :pull true}
              ;;               ... }
              (hash-map (-> project (key) (keyword))
                        (-> project
                            (val)
                            (get :github-coordinates)
                            (str/split #"/")
                            (#(hash-map :org (first %) :repo (second %)))
                            (#(repos/collaborators (:org %) (:repo %) {:oauth-token token}))
                            ;; Pass the result through if it is a sequence, otherwise log a warning
                            ;; and pass down an empty sequence instead:
                            (#(if (seq? %)
                                %
                                (do (log/warn "Unable to retrieve permissions for" login
                                              "on project" (key project) "with reason:"
                                              (-> % :body :message)
                                              (str "(see: " (-> % :body :documentation_url) ")"))
                                    '())))
                            (#(filter (fn [collab]
                                        (= login (:login collab)))
                                      %))
                            (first)
                            :permissions))))
       (apply merge)))

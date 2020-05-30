(ns droid.github
  (:require [clojure.string :as string]
            [ring.util.codec :as codec]
            [tentacles.core :refer [api-call]]
            [tentacles.pulls :refer [pulls]]
            [tentacles.repos :refer [branches]]
            [droid.config :refer [config]]
            [droid.log :as log]))

(def git-actions
  "The version control operations available in DROID"
  {:git-status "git status"
   :git-diff "git diff"
   :git-fetch "git fetch"
   :git-pull "git pull"
   :git-commit (fn [encoded-commit-msg]
                 ;; A function to generate a command line string that will commit to git with the
                 ;; given URL encoded commit message.
                 (let [commit-msg (codec/url-decode encoded-commit-msg)]
                   (if (and (not (nil? commit-msg))
                            (-> commit-msg (string/trim) (not-empty)))
                     (format "git commit --all -m \"%s\"" commit-msg)
                     (log/error "Received empty commit message"))))
   ;; TODO: Implement these:
   :git-amend "true"
   :git-push "true"})

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
                            (string/split #"/")
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
  "Call the GitHub API to get the list of remote branches for the given project, using the given
  login and token for authentication."
  [project-name login token]
  (let [[org repo] (-> config
                       :projects
                       (get project-name)
                       :github-coordinates
                       (string/split #"/"))
        get-branch-pr (fn [branch-name]
                        ;; Call the GitHub API to fetch all open PRs in the repo, and then filter
                        ;; those that begin from the given branch. Note that there can be at most
                        ;; one such branch. We only need the html_url from the record (if any) that
                        ;; is returned.
                        (->> (pulls org repo {:oauth-token token, :state "open"})
                             (filter #(-> % :head :ref (= branch-name)))
                             (map #(get % :html_url))
                             (first)))
        remote-branches (do
                          (log/debug "Querying GitHub repository" (str org "/" repo)
                                     "for list of remote branches of project" project-name
                                     "using" (str login "'s") "credentials")
                          (branches org repo {:oauth-token token}))]

    (if (= (type remote-branches) clojure.lang.PersistentHashMap)
      (do (log/error
           "Request to retrieve remote branches of project" project-name
           "failed with reason:" (-> remote-branches :body :message)
           (str "(see: " (-> remote-branches :body :documentation_url) ")"))
          [])
      ;; Associate any open pull requests with their respective branches:
      (->> remote-branches
           (map #(assoc % :pull-request (-> % :name (get-branch-pr))))
           (vec)))))

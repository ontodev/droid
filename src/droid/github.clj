(ns droid.github
  (:require [clojure.string :as string]
            [cheshire.core :as cheshire]
            [clj-jwt.core  :refer [jwt sign to-str]]
            [clj-jwt.key   :refer [private-key]]
            [clj-time.core :refer [now plus minutes]]
            [org.httpkit.client :as http]
            [ring.util.codec :as codec]
            [tentacles.core :refer [api-call]]
            [tentacles.pulls :refer [create-pull pulls]]
            [tentacles.repos :refer [branches]]
            [droid.config :refer [get-config]]
            [droid.log :as log]
            [droid.secrets :refer [secrets]]))

(defn get-github-app-installation-token
  "Fetch a GitHub App installation token corresponding to the given project from GitHub"
  [project-name]
  (log/debug "Fetching GitHub App installation token for" project-name "from GitHub")
  ;; If we are in local mode use the PAT instead of the installation token:
  (if (get-config :local-mode)
    (do
      (log/debug "Using personal access token in lieu of installation token (local mode)")
      (:personal-access-token secrets))
    ;; Otherwise fetch the installation token from GitHub:
    (let [payload {:iss (-> :github-app-id (get-config) (str))
                   :exp (-> (now) (plus (minutes 10)))
                   :iat (now)}
          pem (-> :pem-file (get-config) (private-key))
          json-web-token (-> payload jwt (sign :RS256 pem) to-str)
          project-org (-> :projects (get-config) (get project-name) (get :github-coordinates)
                          (string/split #"/") (first))
          inst-id (let [{:keys [body status] :as resp}
                        @(http/get "https://api.github.com/app/installations"
                                   {:headers {"Authorization" (str "Bearer " json-web-token)}})]
                    (when (or (< status 200) (> status 299))
                      (throw
                       (Exception.
                        (str "Failed to get installations info from GitHub: " resp))))
                    (->> body (#(cheshire/parse-string % true))
                         (filter #(-> % :account :login (= project-org))) (first) :id))
          inst-token (let [{:keys [body status] :as resp}
                           @(http/post (str "https://api.github.com/app/installations/" inst-id
                                            "/access_tokens")
                                       {:headers {"Authorization" (str "Bearer " json-web-token)}})]
                       (when (or (< status 200) (> status 299))
                         (throw
                          (Exception.
                           (str "Failed to get installation token from GitHub: " resp))))
                       ;; Note that the token has further fields, including an expiry time, which
                       ;; we throw away since we will be deleting the token immediately:
                       (-> body (cheshire/parse-string true) :token))]
      inst-token)))

(def git-actions
  "The version control operations available in DROID"
  {:git-status {:command "git status"
                :html-param "?new-action=git-status"
                :html-class "btn btn-sm btn-success"
                :html-btn-label "Status"}
   :git-diff {:command "git diff"
              :html-param "?new-action=git-diff"
              :html-class "btn btn-sm btn-success"
              :html-btn-label "Diff"}
   :git-fetch {:command "git fetch"
               :html-param "?new-action=git-fetch"
               :html-class "btn btn-sm btn-success"
               :html-btn-label "Fetch"}
   :git-pull {:command "git pull"
              :html-param "?new-action=git-pull"
              :html-class "btn btn-sm btn-warning"
              :html-btn-label "Pull"}
   :git-push {:command "git push"
              :html-param "?confirm-push=1"
              :html-class "btn btn-sm btn-danger"
              :html-btn-label "Push"}
   :git-reset-hard {:command "git reset --hard"
                    :html-param "?confirm-reset-hard=1"
                    :html-class "btn btn-sm btn-danger"
                    :html-btn-label "Reset"}
   :git-commit {:command
                (fn [{:keys [msg user] :as options}]
                  ;; A function to generate a command line string that will commit to git with the
                  ;; given URL encoded commit message and given user info.
                  (let [commit-msg (codec/url-decode msg)]
                    (if (and (not (nil? commit-msg))
                             (not (string/blank? commit-msg)))
                      (format "git commit --all -m \"%s\" --author \"%s <%s>\""
                              commit-msg
                              (or (:name user) (:login user))
                              (or (:email user) ""))
                      (log/error "Received empty commit message for commit"))))

                :html-param
                "?get-commit-msg=1"

                :html-class
                "btn btn-sm btn-warning"

                :html-btn-label
                "Commit"}
   :git-amend {:command
               (fn [{:keys [msg user] :as options}]
                 ;; A function to generate a command line string that will ammend the last commit to
                 ;; git with the given URL encoded commit message and given user info.
                 (let [commit-msg (codec/url-decode msg)]
                   (if (and (not (nil? commit-msg))
                            (not (string/blank? commit-msg)))
                     (format "git commit --all --amend -m \"%s\" --author \"%s <%s>\""
                             commit-msg
                             (or (:name user) (:login user))
                             (or (:email user) ""))
                     (log/error "Received empty commit message for commit amendment"))))

               :html-param
               "?get-commit-amend-msg=1"

               :html-class
               "btn btn-sm btn-warning"

               :html-btn-label
               "Amend"}})

(defn create-pr
  "Calls the GitHub API to create a pull request with the given description on the given branch in
  the given project. Returns the URL of the PR if successful or an empty string otherwise."
  [project-name from-branch to-branch
   {{{:keys [login]} :user} :session
    {{:keys [token]} :github} :oauth2/access-tokens}
   draft-mode? pr-to-add]
  (log/info "Creating PR" (str "\"" pr-to-add "\"") "for user" (str login ":") "Merging"
            from-branch "to" to-branch "in project" project-name)
  (let [response (-> :projects
                     (get-config)
                     (get project-name)
                     (get :github-coordinates)
                     (string/split #"/")
                     (#(create-pull (first %) (second %) pr-to-add to-branch from-branch
                                    {:oauth-token token, :draft draft-mode?})))]
    (let [error-msg (-> response :body :message)]
      ;; If there is an error, log it and return an empty string, otherwise return the PR's URL:
      (if error-msg
        (do (log/error "Cound not create pull request. Reason:" error-msg)
            "")
        (:html_url response)))))

(defn get-project-permissions
  "Given a GitHub login and an OAuth2 token, returns a hash-map specifying the user's permissions
  for every project managed by the server instance."
  [login token]
  (->> :projects
       (get-config)
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

;; The maximum number of branches DROID is allowed to display. This cannot be greater than 100
;; (see https://docs.github.com/en/rest/reference/repos#list-branches)
(def max-remotes 100)

(defn get-remote-branches
  "Call the GitHub API to get the list of remote branches for the given project, using the given
  login and token for authentication."
  [project-name login token]
  (let [[org repo] (-> :projects
                       (get-config)
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
                                     (when login
                                       (str "using " login "'s credentials")))
                          (branches org repo {:oauth-token token
                                              :per-page max-remotes}))]
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

(defn get-default-branch
  "Call the GitHub API to get the default branch for the given project, using the given login and
  token for authentication"
  [project-name login token]
  (let [[org repo] (-> :projects
                       (get-config)
                       (get project-name)
                       :github-coordinates
                       (string/split #"/"))]
    (do
      (log/debug "Querying GitHub repository" (str org "/" repo)
                 "for the default branch for project" project-name
                 (when login
                   (str "using " login "'s credentials")))
      (-> (api-call :get "repos/%s/%s" [org repo] {:oauth-token token})
          (#(or (:default_branch %)
                (log/error "Unable to retrieve default branch for project" project-name
                           "with reason:" (-> % :body :message)
                           (str "(see: " (-> % :body :documentation_url) ")"))))))))

(defn delete-branch
  "Calls the GitHub API to delete the given branch of the given project. Returns true if the delete
  was successful and false otherwise."
  [project-name branch-name login token]
  (log/debug "Sending DELETE request for branch:" branch-name "of project" project-name
             "to GitHub on behalf of" login)
  (-> :projects (get-config) (get project-name) :github-coordinates (string/split #"/")
      (#(api-call :delete "repos/%s/%s/git/refs/heads/%s"
                  [(first %) (second %) branch-name] {:oauth-token token}))
      (#(if (or (< (:status %) 200)
                (> (:status %) 299))
          (do
            (log/error "Received error when trying to delete branch:"
                       (:status %) (-> % :body :message))
            false)
          true))))

(ns droid.html
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [function?]]
            [hiccup.page :refer [html5]]
            [hickory.core :as hickory]
            [me.raynes.conch.low-level :as sh]
            [droid.config :refer [config]]
            [droid.data :as data]
            [droid.make :as make]
            [droid.dir :refer [get-workspace-dir get-temp-dir]]
            [droid.github :as gh]
            [droid.log :as log]
            [ring.util.codec :as codec]
            [ring.util.response :refer [file-response redirect]]))

(def default-html-headers
  {"Content-Type" "text/html"})

(defn- read-only?
  "Returns true if the given user has read-only access to the site."
  [{{:keys [project-name]} :params
    {{:keys [login project-permissions]} :user} :session}]
  ;; Access will be read-only in the following cases:
  ;; - The user is logged out
  ;; - The user does not have write or admin permission on the requested project,
  ;;   and is not a site admin.
  (let [this-project-permissions (->> project-name (keyword) (get project-permissions))]
    (or (nil? login)
        (and (not= this-project-permissions "write")
             (not= this-project-permissions "admin")
             (not-any? #(= login %) (-> config :site-admin-github-ids (get (:op-env config))))))))

(defn- login-status
  "Render the user's login status."
  [{{:keys [project-name]} :params,
    {:keys [user]} :session,
    :as request}]
  (let [navbar-attrs {:class "p-0 navbar navbar-light bg-transparent"}]
    (if (:authenticated user)
      ;; If the user has been authenticated, render a navbar with login info and a logout link:
      [:nav navbar-attrs
       [:small "Logged in as " [:a {:target "__blank" :href (:html_url user)} (or (:name user)
                                                                                  (:login user))]
        ;; If the user is viewing a project page and has read-only access, indicate this:
        (when (and (not (nil? project-name))
                   (read-only? request))
          " (read-only access)")]
       [:small {:class "ml-auto"} [:a {:href "/logout"} "Logout"]]]
      ;; Otherwise the navbar will only have a login link:
      [:nav navbar-attrs
       [:small [:a {:href "/oauth2/github"} "Login via GitHub"]]])))

;; This function is useful for passing parameters through in a redirect without having to know
;; what they are:
(defn- params-to-query-str
  "Given a map of parameters, construct a string suitable for use in a GET request"
  [params]
  (->> params
       (map #(-> %
                 (key)
                 (name)
                 (str "=" (val %))))
       (string/join "&")))

(defn- html-response
  "Given a request map and a response map, return the response as an HTML page."
  [{:keys [session] :as request}
   {:keys [status headers title heading content]
    :as response
    :or {status 200
         headers default-html-headers
         title "DROID"
         heading title}}]
  {:status status
   :session session
   :headers default-html-headers
   :body
   (html5
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
     [:link
      {:rel "stylesheet"
       :href "https://stackpath.bootstrapcdn.com/bootstrap/4.4.1/css/bootstrap.min.css"
       :integrity "sha384-Vkoo8x4CGsO3+Hhxv8T/Q5PaXtkKtu6ug5TOeNV6gBiFeWPGFN9MuhOf23Q9Ifjh"
       :crossorigin "anonymous"}]
     [:link
      {:rel "stylesheet"
       :href "//cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.16.2/build/styles/default.min.css"}]
     [:title title]]
    [:body
     [:div {:id "content" :class "container p-3"}
      (login-status request)
      [:hr {:class "line1"}]
      [:h1 heading]
      content]
     ;; Optional JavaScript. jQuery first, then Popper.js, then Bootstrap JS.
     [:script {:src "https://code.jquery.com/jquery-3.4.1.slim.min.js"
               :integrity "sha384-J6qa4849blE2+poT4WnyKhv5vZF5SrPo0iEjwBvKU7imGFAV0wwj1yYfoRSJoZ+n"
               :crossorigin "anonymous"}]
     [:script {:src "https://cdn.jsdelivr.net/npm/popper.js@1.16.0/dist/umd/popper.min.js"
               :integrity "sha384-Q6E9RHvbIyZFJoft+2mJbHaEWldlvI9IOYy5n3zV9zzTtmI3UksdQRVvoxMfooAo"
               :crossorigin "anonymous"}]
     [:script {:src "https://stackpath.bootstrapcdn.com/bootstrap/4.4.1/js/bootstrap.min.js"
               :integrity "sha384-wfSDF2E50Y2D1uUdj0O3uMBJnjuUD4Ih7YwaYd1iqfktj0Uod8GCExl3Og8ifwB6"
               :crossorigin "anonymous"}]
     [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/moment.js/2.24.0/moment.min.js"}]
     [:script {:src "//cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.16.2/build/highlight.min.js"}]
     [:script (str "hljs.initHighlightingOnLoad();" ; highlight code
                   "$('.date').each(function() {" ; replace GMT dates with local dates
                   "  $(this).text(moment($(this).text()).format('YYYY-MM-DD hh:mm:ss'));"
                   "});"
                   "$('.since').each(function() {" ; replace GMT dates with friendly time period
                   "  $(this).text(moment($(this).text()).fromNow());"
                   "});")]

     ;; If the user has read-only access, run the following jQuery script to disable any action
     ;; buttons on the page:
     (when (read-only? request)
       [:script
        "$('.action-btn').each(function() {"
        "  $(this).addClass('disabled');"
        "  $(this).removeAttr('href');"
        "});"])])})

(defn- kill-process [{:keys [process action branch-name project-name] :as branch}
                     {:keys [login] :as user}]
  "Kill the running process associated with the given branch and return the branch to the caller."
  (when-not (nil? process)
    (log/info "Cancelling action" action "on branch" branch-name "of project" project-name
              "on behalf of user" login)
    (sh/destroy process)
    (assoc branch :cancelled? true)))

(defn- branch-status-summary
  "Given the names of a project and branch, output a summary string of the branch's commit status
  as compared with its remote."
  [project-name branch-name]
  (let [branch-agent (-> @data/local-branches
                         (get (keyword project-name))
                         (get (keyword branch-name)))

        [org repo] (-> config :projects (get project-name) :github-coordinates (string/split #"/"))

        render-remote (fn [remote]
                        [:a {:href (-> remote
                                       (string/split #"/")
                                       (last)
                                       (#(str "https://github.com/" org "/" repo "/tree/" %)))}
                         remote])]

    (send-off branch-agent data/refresh-local-branch)
    (await branch-agent)
    (if-not (-> @branch-agent :git-status :remote)
      [:span {:class "text-muted"} "No remote"]
      [:span
       (str (-> @branch-agent :git-status :ahead) " ahead, "
            (-> @branch-agent :git-status :behind) " behind ")
       (-> @branch-agent :git-status :remote (render-remote))
       (when (-> @branch-agent :git-status :uncommitted?)
         ", with uncommitted changes")])))

(defn- render-project-branches
  "Given the name of a project, render a list of its branches, with links. If restricted-access? is
  set to true, do not display any links to actions that would result in changes to the workspace."
  [project-name restricted-access?]
  (let [local-branch-names (->> project-name
                                (keyword)
                                (get @data/local-branches)
                                (keys)
                                (map name))

        [org repo] (-> config :projects (get project-name) :github-coordinates (string/split #"/"))

        project-url (str "/" project-name)

        remote-branches (->> @data/remote-branches
                             (#(get % (keyword project-name)))
                             ;; Branches with pull requests get displayed first, otherwise sort by
                             ;; name. The (juxt) function creates a sequence out of the given fields
                             ;; corresponding to each of the two entries to compare.
                             (sort-by (juxt :pull-request :name)
                                      #(let [pr-order (compare (-> %1 (first) (nil?))
                                                               (-> %2 (first) (nil?)))
                                             name-order (compare (second %1)
                                                                 (second %2))]
                                         (if (or (< pr-order 0)
                                                 (and (= pr-order 0) (< name-order 0)))
                                           ;; -1 signifies that one is "less than" two, 0 that they
                                           ;; are equal, and 1 that it is "greater than".
                                           -1
                                           1))))

        render-pr-link #(let [pr-url (:pull-request %)]
                          (when-not (nil? pr-url)
                            [:a {:href pr-url} "#" (-> pr-url (string/split #"/") (last))]))

        render-local-branch-row (fn [branch-name]
                                  (when branch-name
                                    [:tr
                                     (when-not restricted-access?
                                       [:td
                                        [:a {:href (str project-url "?to-delete=" branch-name)
                                             :data-toggle "tooltip" :title "Delete this branch"
                                             :class "badge badge-danger"} "Delete"]])
                                     [:td [:a {:href (str project-url "/branches/" branch-name)}
                                           branch-name]]
                                     [:td
                                      (let [remote-branch (->> remote-branches
                                                               (filter #(= branch-name (:name %)))
                                                               (first))]
                                        (when remote-branch
                                          (render-pr-link remote-branch)))]
                                     [:td (branch-status-summary project-name branch-name)]]))

        render-remote-branch-row (fn [remote-branch]
                                   (when-not restricted-access?
                                     [:tr
                                      [:td
                                       [:a {:href (str project-url "?to-checkout="
                                                       (:name remote-branch))
                                            :data-toggle "tooltip"
                                            :title "Checkout a local copy of this branch"
                                            :class "badge badge-info"} "Checkout"]]
                                      [:td [:a {:href (->> remote-branch
                                                           :name
                                                           (str "https://github.com/" org "/" repo
                                                                "/tree/"))}
                                            (:name remote-branch)]]
                                      [:td (render-pr-link remote-branch)]
                                      [:td]]))]

    [:table {:class "table table-sm table-striped table-borderless mt-3"}
     [:thead
      [:tr (when-not restricted-access? [:th]) [:th "Branch"] [:th "Pull request"] [:th "Git status"]]]
     ;; Render the local master branch first if it is present:
     (->> local-branch-names (filter #(= % "master")) (first) (render-local-branch-row))
     ;; Render all of the other local branches:
     (for [local-branch-name (->> local-branch-names
                                  (filter #(not= % "master"))
                                  ;; Branches with pull requests get displayed first, otherwise sort
                                  ;; by name.
                                  (sort (fn [one two]
                                          (letfn [(pr-nil? [branch-name]
                                                    (->> remote-branches
                                                         (filter #(= branch-name (:name %)))
                                                         (first)
                                                         :pull-request
                                                         (nil?)))]
                                            (let [pr-order (compare (pr-nil? one)
                                                                    (pr-nil? two))
                                                  name-order (compare one two)]
                                              (if (or (< pr-order 0)
                                                      (and (= pr-order 0) (< name-order 0)))
                                                ;; -1 signifies that one is "less than" two, 0 that
                                                ;; they are equal, and 1 that it is "greater than"
                                                -1
                                                1))))))]

       (render-local-branch-row local-branch-name))

     ;; Render the master branch if it is present and there is no local copy:
     (when (not-any? #(= "master" %) local-branch-names)
       (->> remote-branches
            (filter #(= (:name %) "master"))
            (first)
            (render-remote-branch-row)))
     ;; Render all of the other remote branches that don't have local copies:
     (for [remote-branch remote-branches]
       (when (and (not= (:name remote-branch) "master")
                  (not-any? #(= (:name remote-branch) %) local-branch-names))
         (render-remote-branch-row remote-branch)))]))

(defn render-404
  "Render the 404 - not found page"
  [request]
  (html-response
   request
   {:title "404 Page Not Found &mdash; DROID"
    :content [:div#contents
              [:p "The requested resource could not be found."]]
    :status 404}))

(defn index
  "Render the index page"
  [{:keys [params]
    {:keys [just-logged-out reset really-reset]} :params,
    {{:keys [login]} :user} :session,
    :as request}]
  (log/debug "Processing request in index with params:" params)
  (letfn [(site-admin? []
            (some #(= login %) (-> config :site-admin-github-ids (get (:op-env config)))))]
    (cond
      ;; If the user is a site-admin and has confirmed a reset action, do it now and redirect back
      ;; to this page:
      (and (site-admin?) (not (nil? really-reset)))
      (do
        (log/info "Site administrator" login "requested a global reset of branch data")
        (send-off data/local-branches data/reset-all-local-branches)
        (await data/local-branches)
        (redirect "/"))

      ;; Otherwise just render the page:
      :else
      (html-response
       request
       {:title "DROID"
        :heading [:div [:a {:href "/"} "DROID"]]
        :content [:div
                  [:p "DROID Reminds us that Ordinary Individuals can be Developers"]
                  [:div
                   (when-not (nil? just-logged-out)
                     [:div {:class "alert alert-info"}
                      "You have been logged out. If this is a shared computer you may also want to "
                      [:a {:href "https://github.com/logout"} "sign out of GitHub"]
                      [:div {:class "pt-2"}
                       [:a {:class "btn btn-sm btn-primary" :href "/"} "Dismiss"]]])
                   (when (and (site-admin?) (not (nil? reset)))
                     [:div {:class "alert alert-danger"}
                      "Are you sure you want to reset branch data for all projects? Note that "
                      "doing so will kill any running processes and clear all console data."
                      [:div {:class "pt-2"}
                       [:a {:class "btn btn-sm btn-primary" :href "/"} "No, cancel"]
                       [:span "&nbsp;"]
                       [:a {:class "btn btn-sm btn-danger" :href "/?really-reset=1"}
                        "Yes, continue"]]])
                   [:div
                    [:h3 "Available Projects"]
                    [:ul {:class ""}
                     (for [project (-> config :projects)]
                       [:li [:a {:href (->> project (key) (str "/"))}
                             (->> project (val) :project-title)]
                        [:span "&nbsp;"]
                        (->> project (val) :project-description)])]
                    (when (and (site-admin?) (nil? reset))
                      [:div {:class "pb-3"}
                       [:a {:class "btn btn-sm btn-danger" :href "/?reset=1"
                            :data-toggle "tooltip"
                            :title "Clear branch data for all projects"}
                        "Reset branch data"]])]]]}))))

(defn render-project
  "Render the home page for a project"
  [{:keys [params]
    {:keys [project-name refresh to-delete to-really-delete to-checkout
            create invalid-name-error to-create branch-from]} :params,
    :as request}]
  (log/debug "Processing request in render-project with params:" params)
  (let [this-url (str "/" project-name)
        project (-> config :projects (get project-name))]
    (letfn [(refname-valid? [refname]
              ;; Uses git's command line interface to determine whether the given refname is legal.
              (let [process (sh/proc "git" "check-ref-format" "--allow-onelevel" refname)
                    exit-code (future (sh/exit-code process))]
                (or (= @exit-code 0) false)))

            (create-branch [project-name branch-name]
              ;; Creates a new branch with the given name in the given project's workspace

              ;; Begin by refreshing local and remote branches since we must reference them below:
              (send-off data/local-branches data/refresh-local-branches [project-name])
              (send-off data/remote-branches
                        data/refresh-remote-branches-for-project project-name request)
              (await data/local-branches data/remote-branches)

              (cond
                (not (refname-valid? branch-name))
                (-> this-url
                    (str "?create=1&invalid-name-error=")
                    (str (-> "Invalid name: "
                             (str "&quot;" branch-name "&quot;")
                             (codec/url-encode)))
                    (redirect))

                (or (data/remote-branch-exists? project-name branch-name)
                    (data/local-branch-exists? project-name branch-name))
                (-> this-url
                    (str "?create=1&invalid-name-error=")
                    (str (-> "Already exists: "
                             (str "&quot;" branch-name "&quot;")
                             (codec/url-encode)))
                    (redirect))

                :else
                (do
                  ;; Create the branch, then refresh the local branch collection so that it shows up
                  ;; on the page:
                  (send-off data/local-branches data/create-local-branch project-name
                            branch-name branch-from request)
                  (send-off data/local-branches data/refresh-local-branches [project-name])
                  (await data/local-branches)
                  (redirect this-url))))]

      ;; Perform an action based on the parameters present in the request:
      (cond
        ;; No project found with the given name:
        (nil? project)
        (render-404 request)

        ;; Delete a local branch:
        (and (not (nil? to-really-delete))
             (not (read-only? request)))
        (do
          (log/info "Deletion of branch" to-really-delete "from" project-name "initiated by"
                    (-> request :session :user :login))
          (send-off data/local-branches data/delete-local-branch project-name to-really-delete)
          (await data/local-branches)
          (redirect this-url))

        ;; Checkout a remote branch into the local project workspace:
        (and (not (nil? to-checkout))
             (not (read-only? request)))
        (do
          (log/info "Checkout of remote branch" to-checkout "into workspace for" project-name
                    "initiated by" (-> request :session :user :login))
          (send-off data/local-branches
                    data/checkout-remote-branch-to-local project-name to-checkout)
          (await data/local-branches)
          (redirect this-url))

        ;; Create a new branch:
        (and (not (nil? to-create))
             (not (read-only? request)))
        (do
          (log/info "Creation of a new branch:" to-create "in project" project-name
                    "initiated by" (-> request :session :user :login))
          (create-branch project-name to-create))

        ;; Refresh local and remote branches:
        (not (nil? refresh))
        (do
          (send-off data/local-branches data/refresh-local-branches [project-name])
          (send-off data/remote-branches
                    data/refresh-remote-branches-for-project project-name request)
          (await data/local-branches data/remote-branches)
          (redirect this-url))

        ;; Otherwise just render the page
        :else
        (do
          ;; If the collection of remote branches is empty (which will be true if the server has
          ;; restarted recently) then refresh it:
          (when (and (->> project-name (keyword) (get @data/remote-branches) (empty?))
                     (not (read-only? request)))
            (send-off data/remote-branches
                      data/refresh-remote-branches-for-project project-name request)
            (await data/remote-branches))
          (html-response
           request
           {:title (->> project :project-title (str "DROID for "))
            :heading [:div
                      [:a {:href "/"} "DROID"]
                      " / "
                      (-> project :project-title)]
            :content [:div
                      [:p (->> project :project-description)]

                      ;; Display this alert and question when to-delete parameter is present:
                      (when (and (not (nil? to-delete))
                                 (not (read-only? request)))
                        [:div {:class "alert alert-danger"}
                         "Are you sure you want to delete the branch "
                         [:span {:class "text-monospace font-weight-bold"} to-delete] "?"
                         [:div {:class "pt-2"}
                          [:a {:class "btn btn-sm btn-primary" :href this-url} "No, cancel"]
                          [:span "&nbsp;"]
                          [:a {:class "btn btn-sm btn-danger"
                               :href (str this-url "?to-really-delete=" to-delete)}
                           "Yes, continue"]]])

                      ;; Display this alert and question when the create parameter is present:
                      (when (and (not (nil? create))
                                 (not (read-only? request)))
                        [:div {:class "alert alert-info mt-3"}
                         [:form {:action this-url :method "get"}
                          [:div {:class "font-weight-bold mb-3 text-primary"}
                           "Create a new branch"]
                          [:div {:class "form-group row"}
                           ;; Select list on available remote branches to serve as the
                           ;; branching-off point:
                           [:div {:class "col-sm-3"}
                            [:div
                             [:label {:for "branch-from" :class "mb-n1 text-secondary"}
                              "Branch from"]]
                            [:select {:id "branch-from" :name "branch-from"
                                      :class "form-control form-control-sm"}
                             (for [branch-name (->> project-name
                                                    (keyword)
                                                    (get @data/remote-branches)
                                                    (map #(get % :name))
                                                    (sort))]
                               ;; The master branch is selected by default:
                               [:option
                                (merge {:value branch-name} (when (= branch-name "master")
                                                              {:selected "selected"}))
                                branch-name])]]
                           ;; Input box for inputting the desired new branch name:
                           [:div {:class "col-sm-3"}
                            [:div
                             [:label {:for "to-create" :class "mb-n1 text-secondary"}
                              "Branch name"]]
                            [:div
                             [:input {:id "to-create" :name "to-create" :type "text"}]]
                            ;; If the user previously tried to create a branch with an invalid
                            ;; name, show an alert:
                            (when-not (nil? invalid-name-error)
                              [:div {:class "mb-1"}
                               [:small {:class "text-danger"} invalid-name-error]])]
                           ;; The create and cancel buttons:
                           [:div {:class "col-sm-3"}
                            [:div "&nbsp;"]
                            [:button {:type "submit" :class "btn btn-sm btn-success mr-2"}
                             "Create"]
                            [:a {:class "btn btn-sm btn-secondary ml-2" :href this-url}
                             "Cancel"]]]]])

                      ;; The non-conditional content of the project page is here:
                      [:h3 "Branches"]
                      [:div {:class "pb-2"}
                       [:a {:class "btn btn-sm btn-primary"
                            :href (str this-url "?refresh=1")
                            :data-toggle "tooltip"
                            :title "Refresh available local and remote branches"}
                        "Refresh"]
                       (when-not (read-only? request)
                         [:a {:class "btn btn-sm btn-success ml-2"
                              :href (str this-url "?create=1")
                              :data-toggle "tooltip"
                              :title "Create a new local branch"}
                          "Create new"])]
                      (->> request (read-only?) (render-project-branches project-name))]}))))))

(defn- render-status-bar-for-action
  "Given some branch data, render the status bar for the currently running process."
  [{:keys [branch-name project-name run-time exit-code cancelled? console] :as branch}]
  (let [branch-url (str "/" project-name "/branches/" branch-name)]
    (cond
      ;; The last process was cancelled:
      cancelled?
      [:p {:class "alert alert-warning"} "Process cancelled"]

      ;; No process is running (or has run):
      (nil? exit-code)
      (if-not (empty? console)
        ;; If there is console output display a warning, otherwise nothing:
        [:p {:class "alert alert-warning"} (str "Status of last command unknown (server restart?). "
                                                "Its output is displayed in the console below.")]
        [:div])

      ;; A process is still running:
      (not (realized? exit-code))
      [:p {:class "alert alert-warning"}
       (str "Processes still running after "
            (-> run-time (/ 1000) (float) (Math/ceil) (int)) " seconds.")
       [:span {:class "col-sm-2"} [:a {:class "btn btn-success btn-sm" :href branch-url}
                                   "Refresh"]]
       [:span {:class "col-sm-2"} [:a {:class "btn btn-danger btn-sm"
                                       :href (str branch-url "?new-action=cancel-DROID-process")}
                                   "Cancel"]]]

      ;; The last process completed successfully:
      (= 0 @exit-code)
      [:p {:class "alert alert-success"} "Success"]

      ;; The last process completed unsuccessfully:
      :else
      [:p {:class "alert alert-danger"} (str "ERROR: Exit code " @exit-code)])))

(defn- prompt-to-kill-for-action
  "Given some branch data, and a new action to run, render an alert box asking the user to confirm
  that (s)he would like to kill the currently running process."
  [{:keys [branch-name project-name action] :as branch}
   {:keys [new-action] :as params}]
  (let [branch-url (str "/" project-name "/branches/" branch-name)]
    [:div {:class "alert alert-warning"}
     [:p [:span {:class "text-monospace font-weight-bold"} action]
      " is still not complete. You must cancel it before running "
      [:span {:class "text-monospace font-weight-bold"} new-action]
      ". Do you really want to do this?"]
     [:span [:a {:class "btn btn-secondary btn-sm" :href branch-url} "No, do nothing"]]
     [:span {:class "col-sm-1"}]
     [:span [:a {:class "btn btn-danger btn-sm"
                 :href (-> branch-url
                           (str "?" (-> params
                                        (dissoc :confirm-kill)
                                        (assoc :force-kill 1)
                                        (params-to-query-str))))}
             "Yes, go ahead"]]]))

(defn- view-exists?
  "Check whether the path corresponding to the view is in the filesystem under the branch"
  [{:keys [branch-name project-name action] :as branch}
   view-path]
  (-> (get-workspace-dir project-name branch-name)
      (str "/" view-path)
      (io/file)
      (.exists)))

(defn- prompt-to-kill-for-view
  "Given some branch data, and the path for a view that needs to be rebuilt, render an alert box
  asking the user to confirm that (s)he would like to kill the currently running process."
  [{:keys [branch-name project-name action] :as branch}
   {:keys [view-path] :as params}]
  (let [branch-url (str "/" project-name "/branches/" branch-name)
        view-url (str "/" project-name "/branches/" branch-name "/views/" view-path)]
    [:div {:class "alert alert-warning"}
     [:p [:span {:class "text-monospace font-weight-bold"} action]
      " is still not complete. You must cancel it before updating "
      [:span {:class "text-monospace font-weight-bold"} view-path]
      ". Do you really want to do this?"]
     [:span [:a {:class "btn btn-secondary btn-sm" :href branch-url} "No, do nothing"]]
     [:span {:class "col-sm-1"}]
     [:span [:a {:class "btn btn-danger btn-sm" :href (str view-url "?force-kill=1")}
             "Yes, go ahead"]]
     (when (view-exists? branch view-path)
       [:span
        [:span {:class "col-sm-1"}]
        [:span [:a {:class "btn btn-warning btn-sm" :href (str view-url "?force-old=1")}
                "View the stale version instead"]]])]))

(defn- prompt-to-update-view
  "Given some branch data, and the path for a view that needs to be rebuilt, render an alert box
  asking the user to confirm that (s)he would really like to rebuild the view."
  [{:keys [branch-name project-name action] :as branch}
   view-path]
  (let [branch-url (str "/" project-name "/branches/" branch-name)
        view-url (str "/" project-name "/branches/" branch-name "/views/" view-path)]
    [:div
     [:div {:class "alert alert-warning"}
      [:p [:span {:class "font-weight-bold text-monospace"} view-path]
       " is not up to date. What would you like to do?"]

      [:span [:a {:class "btn btn-secondary btn-sm" :href branch-url} "Do nothing"]]
      [:span {:class "col-sm-1"}]
      [:span [:a {:class "btn btn-danger btn-sm" :href (str view-url "?force-update=1")}
              "Rebuild the view"]]
      (when (view-exists? branch view-path)
        [:span
         [:span {:class "col-sm-1"}]
         [:span [:a {:class "btn btn-warning btn-sm" :href (str view-url "?force-old=1")}
                 "View the stale version"]]])]]))

(defn- notify-missing-view
  "Given some branch data, and the path for a view that is missing, render an alert box informing
  the user that it is not there."
  [{:keys [branch-name project-name] :as branch}
   view-path]
  [:div {:class "alert alert-warning"}
   [:div
    [:span
     [:span {:class "text-monospace font-weight-bold"} view-path]
     [:span " does not exist in branch "]
     [:span {:class "text-monospace font-weight-bold"} branch-name]
     [:span ". Ask someone with write access to this project to build it for you."]]]
   [:div {:class "pt-2"}
    [:a {:class "btn btn-sm btn-primary"
         :href (str "/" project-name "/branches/" branch-name)} "Dismiss"]]])

(defn- render-console
  "Given some branch data, and a number of parameters related to an action or a view, render
  the console on the branch page."
  [{:keys [branch-name project-name action start-time command exit-code console] :as branch}
   {:keys [new-action view-path confirm-update confirm-kill] :as params}]
  (letfn [(render-status-bar []
            (cond
              (and (not (nil? view-path))
                   (not (nil? confirm-kill)))
              (prompt-to-kill-for-view branch params)

              (and (not (nil? new-action))
                   (not (nil? confirm-kill)))
              (prompt-to-kill-for-action branch params)

              :else
              (render-status-bar-for-action branch)))

          (render-console-text []
            ;; Look for ANSI escape sequences in the console text. (see:
            ;; https://en.wikipedia.org/wiki/ANSI_escape_code#Escape_sequences). If any are found,
            ;; then launch the python program `ansi2html` in the shell to convert them all to HTML.
            ;; Either way wrap the console text in a pre-formatted block and return it.
            (let [escape-index (when console (->> 0x1B (char) (string/index-of console)))
                  ansi-commands? (and escape-index (->> escape-index (inc) (nth console) (int)
                                                        (#(and (>= % 0x40) (<= % 0x5F)))))
                  render-pre-block (fn [arg]
                                     [:pre {:class "bg-light p-2"} [:samp {:class "shell"} arg]])]
              (if-not ansi-commands?
                (render-pre-block console)
                (let [process (do (log/debug "Colourizing console using ansi2html ...")
                                  (sh/proc "bash" "-c" (str "exec ansi2html -i < console.txt")
                                           :dir (get-temp-dir project-name branch-name)))
                      ansi2html-exit-code (future (sh/exit-code process))
                      colourized-console (if (not= @ansi2html-exit-code 0)
                                           (do (log/error "Unable to colourize console.")
                                               console)
                                           (->> (sh/stream-to-string process :out)
                                                (hickory/parse-fragment)
                                                (map hickory/as-hiccup)))]
                  (render-pre-block colourized-console)))))]

    [:div {:id "console"}
     (if (nil? exit-code)
       ;; If no process is running or has been run, then ask the user to kick one off:
       [:p "Press a button above to execute an action."]
       ;; Otherwise render the info for the action that was run last:
       (let [readable-start (->> start-time (java.time.Instant/ofEpochMilli) (str))]
         [:div
          [:p {:class "alert alert-info"} (str "Action " action " started at ")
           [:span {:class "date"} readable-start] " ("
           [:span {:class "since"} readable-start] ")"]]))

     ;; The status bar and command:
     (render-status-bar)
     (if-not (empty? command)
       [:pre {:class "bg-light p-2"} [:samp {:class "shell"} (str "$ " command)]]
       [:div])

     ;; The actual console text:
     (render-console-text)

     ;; If an action is running and the console has a large number of lines in it, render the status
     ;; bar again:
     (when-not (nil? exit-code)
       (when (<= 35 (->> console
                         (filter #(= \newline  %))
                         (count)))
         (render-status-bar)))]))

(defn- render-version-control-section
  "Render the Version Control section on the page for a branch"
  [{:keys [branch-name project-name] :as branch}
   {:keys [params]
    {:keys [confirm-push get-commit-msg get-commit-amend-msg next-task pr-added]} :params
    :as request}]
  (let [get-last-commit-msg #(let [process (sh/proc "git" "show" "-s" "--format=%s"
                                                    :dir (get-workspace-dir project-name
                                                                            branch-name))
                                   exit-code (future (sh/exit-code process))]
                               (if-not (= 0 @exit-code)
                                 (do (log/error "Unable to retrieve previous commit message:"
                                                (sh/stream-to-string process :err))
                                     nil)
                                 (sh/stream-to-string process :out)))
        get-commits-to-push #(let [process (sh/proc "git" "log" "--branches" "--not" "--remotes"
                                                    "--reverse" "--format=%s"
                                                    :dir (get-workspace-dir project-name
                                                                            branch-name))
                                   exit-code (future (sh/exit-code process))]
                               (if-not (= 0 @exit-code)
                                 (do (log/error "Unable to retrieve commit list:"
                                                (sh/stream-to-string process :err))
                                     nil)
                                 (-> process
                                     (sh/stream-to-string :out)
                                     ((fn [output] (when (not-empty output)
                                                     (string/split-lines output)))))))
        this-url (str "/" project-name "/branches/" branch-name)]

    [:div {:class "col-sm-6"}
     [:h3 "Version Control"]
     (cond
       ;; If a commit was requested, render a dialog to get the commit message:
       (not (nil? get-commit-msg))
       [:div {:class "alert alert-warning m-1"}
        [:form {:action this-url :method "get"}
         [:div {:class "form-group row"}
          [:div [:label {:for "commit-msg" :class "m-1 ml-3 mr-3"} "Enter a commit message"]]
          [:div [:input {:id "commit-msg" :name "commit-msg" :type "text"}]]]
         [:button {:type "submit" :class "btn btn-sm btn-warning mr-2"} "Commit"]
         [:a {:class "btn btn-sm btn-secondary" :href this-url} "Cancel"]]]

       ;; If an amendment to the last commit was requested, render a dialog to get the new commit
       ;; message:
       (not (nil? get-commit-amend-msg))
       [:div {:class "alert alert-warning m-1"}
        [:form {:action this-url :method "get"}
         [:div {:class "form-group row"}
          [:div [:label {:for "commit-amend-msg" :class "m-1 ml-3 mr-3"}
                 "Enter a new commit message"]]
          [:div [:input {:id "commit-amend-msg" :name "commit-amend-msg" :type "text"
                         ;; Use the previous commit message as the default value:
                         :onClick "this.select();" :value (get-last-commit-msg)}]]]
         [:button {:type "submit" :class "btn btn-sm btn-warning mr-2"} "Amend"]
         [:a {:class "btn btn-sm btn-secondary" :href this-url} "Cancel"]]]

       ;; If a push was requested, render a dialog showing the list of local commits that would be
       ;; pushed, and ask the user to confirm:
       (not (nil? confirm-push))
       (let [commits-to-push (get-commits-to-push)]
         (if (-> commits-to-push (count) (> 0))
           ;; If there are commits to push, show them:
           [:div {:class "alert alert-danger m-1"}
            [:div {:class "font-weight-bold"} "The following commits will be pushed:"]
            [:ol
             (->> commits-to-push (map #(do [:li %])))]
            [:a {:href this-url :class "btn btn-sm btn-secondary"} "Cancel"]
            [:a {:href (str this-url "?new-action=git-push&next-task=create-pr")
                 :class "ml-2 btn btn-sm btn-danger"} "Push commits"]]
           ;; Otherwise tell the user that there aren't any:
           [:div {:class "alert alert-warning"}
            [:div {:class "mb-3"} "There are no commits to push"]
            [:a {:href this-url :class "btn btn-sm btn-warning"} "Dismiss"]]))

       ;; The request includes a "next-task" parameter which indicates that a PR should be created:
       (and (= next-task "create-pr")
            (-> branch :action (= "git-push"))
            (-> branch :exit-code (deref) (= 0)))
       (let [current-pr (->> @data/remote-branches
                             (#(get % (keyword project-name)))
                             (filter #(= branch-name (:name %)))
                             (first)
                             :pull-request)]
         (if (nil? current-pr)
           ;; If the current branch doesn't already have a PR associated, provide the user with
           ;; the option to create one:
           [:div {:class "alert alert-success m-1"}
            [:div {:class "mb-3"} "Commits pushed successfully."]
            [:form {:action this-url :method "get"}
             [:div {:class "form-group row"}
              [:div
               [:input {:id "pr-to-add" :name "pr-to-add" :type "text" :class "ml-3 mr-2"
                        :required true :onClick "this.select();" :value (get-last-commit-msg)}]]
              [:button {:type "submit" :class "btn btn-sm btn-primary mr-2"}
               "Create a pull request"]
              [:a {:class "btn btn-sm btn-secondary" :href this-url} "Dismiss"]]]]
           ;; Otherwise display a link to the current PR:
           [:div {:class "alert alert-success m-1"}
            [:div {:class "mb-3"} "Commits pushed successfully. A "
             [:a {:href current-pr} "pull request is already open"] " on this branch."]
            [:div [:a {:href this-url :class "btn btn-sm btn-secondary mt-1"} "Dismiss"]]]))

       ;; A an attempt to create a PR has been made. If pr-added is an empty string then the attempt
       ;; was unsuccessful, otherwise it will contain the URL of the PR.
       (not (nil? pr-added))
       (if-not (empty? pr-added)
         (do
           ;; Kick off a refresh of the remote branches but don't await for it since we already have
           ;; the URL of the new PR in pr-added:
           (send-off data/remote-branches
                     data/refresh-remote-branches-for-project project-name request)
           [:div {:class "alert alert-success"}
            [:div "PR created successfully. To view it click " [:a {:href pr-added} "here."]]
            [:a {:href this-url :class "btn btn-sm btn-secondary mt-1"} "Dismiss"]])
         [:div {:class "alert alert-warning"}
          [:div "Your PR could not be created. Contact an administrator for assistance."]
          [:a {:href this-url :class "btn btn-sm btn-secondary mt-1"} "Dismiss"]]))

     ;; The git action buttons:
     [:table {:class "table table-borderless table-sm"}
      [:tr
       [:td [:a {:href (str this-url "?new-action=git-status")
                 :class "btn btn-sm btn-success btn-block"} "Status"]]
       [:td "Show which files have changed since the last commit"]]
      [:tr
       [:td [:a {:href (str this-url "?new-action=git-diff")
                 :class "btn btn-sm btn-success btn-block"} "Diff"]]
       [:td "Show changes to tracked files since the last commit"]]
      [:tr
       [:td [:a {:href (str this-url "?new-action=git-fetch")
                 :class "btn btn-sm btn-success btn-block"} "Fetch"]]
       [:td "Fetch the latest changes from GitHub"]]
      [:tr
       [:td [:a {:href (str this-url "?new-action=git-pull")
                 :class "btn btn-sm btn-warning btn-block"} "Pull"]]
       [:td "Update this branch with the latest changes from GitHub"]]
      [:tr
       [:td [:a {:href (str this-url "?get-commit-msg=1")
                 :class "btn btn-sm btn-warning btn-block"} "Commit"]]
       [:td "Commit your changes locally"]]
      [:tr
       [:td [:a {:href (str this-url "?get-commit-amend-msg=1")
                 :class "btn btn-sm btn-warning btn-block"} "Amend"]]
       [:td "Update your last commit with new changes"]]
      [:tr
       [:td [:a {:href (str this-url "?confirm-push=1")
                 :class "btn btn-sm btn-danger btn-block"} "Push"]]
       [:td "Push your latest local commit(s) to GitHub"]]]]))

(defn- render-branch-page
  "Given some branch data, and a number of parameters related to an action or a view, construct the
  page corresponding to a branch."
  [{:keys [branch-name project-name Makefile] :as branch}
   {:keys [params]
    {:keys [view-path missing-view confirm-update commit-msg commit-amend-msg pr-to-add]} :params
    :as request}]
  (let [this-url (str "/" project-name "/branches/" branch-name)]
    (cond
      ;; A brand new commit has been requested (and confirmed):
      (and (not (read-only? request))
           (not (nil? commit-msg))
           (-> commit-msg (string/trim) (not-empty)))
      (let [encoded-commit-msg (-> commit-msg (string/replace #"\"" "'") (codec/url-encode))]
        (log/info "User" (-> request :session :user :login) "adding local commit to branch"
                  branch-name "in project" project-name "with commit message:" commit-msg)
        (-> this-url
            (str "?new-action=git-commit&new-action-param=" encoded-commit-msg)
            (redirect)))

      ;; An amendment to the last commit has been requested (and confirmed):
      (and (not (read-only? request))
           (not (nil? commit-amend-msg))
           (-> commit-amend-msg (string/trim) (not-empty)))
      (let [encoded-commit-amend-msg (-> commit-amend-msg
                                         (string/replace #"\"" "'")
                                         (codec/url-encode))]
        (log/info "User" (-> request :session :user :login) "amending local commit to branch"
                  branch-name "in project" project-name "with commit message:" commit-amend-msg)
        (-> this-url
            (str "?new-action=git-amend&new-action-param=" encoded-commit-amend-msg)
            (redirect)))

      ;; The creation of a pull request has been requested. Here we simply call the function in gh/
      ;; and add its return value to the URL for redirection. Any problems are handled elsewhere.
      (and (not (read-only? request))
           (not (nil? pr-to-add)))
      (->> pr-to-add
           (gh/create-pr project-name branch-name
                         (-> request :session :user :login)
                         (-> request :oauth2/access-tokens :github :token))
           (codec/url-encode)
           (str this-url "?pr-added=")
           (redirect))

      ;; Otherwise process the request as normal:
      :else
      (html-response
       request
       {:title (-> config :projects (get project-name) :project-title (str " -- " branch-name))
        :heading [:div
                  [:a {:href "/"} "DROID"]
                  " / "
                  [:a {:href (str "/" project-name)} project-name]
                  " / "
                  branch-name]
        :content [:div
                  [:div
                   [:p {:class "mt-n2"} (branch-status-summary project-name branch-name)]

                   [:hr {:class "line1"}]

                   [:div {:class "row"}
                    [:div {:class "col-sm-6"}
                     [:h3 "Workflow"]
                     ;; If the missing-view parameter is present, then the user with read-only access
                     ;; is trying to look at a view that doesn't exist:
                     (cond
                       (not (nil? missing-view))
                       (notify-missing-view branch view-path)

                       (not (nil? confirm-update))
                       (prompt-to-update-view branch view-path))

                     ;; Render the Workflow HTML from the Makefile:
                     (or (:html Makefile)
                         [:ul [:li "No workflow found"]])]

                    ;; Render the Version Control section if the user has sufficient permission:
                    (when-not (read-only? request)
                      (render-version-control-section branch request))]]

                  [:hr {:class "line1"}]

                  [:div
                   [:h3 "Console"]
                   (render-console branch params)]]}))))

(defn view-file
  "View a file in the workspace if it is in the list of allowed views for the branch. If the file is
  out of date, then ask whether the current version should be served or whether it should be
  rebuilt."
  [{:keys [params]
    {:keys [project-name branch-name view-path confirm-update force-old force-update
            confirm-kill force-kill missing-view]} :params,
    :as request}]
  (log/debug "Processing request in view-file with params:" params)
  (letfn [(up-to-date? []
            ;; Run `make -q` (in the foreground) in the shell to see if the view is up to date:
            (let [process (sh/proc "make" "-q" view-path
                                   :dir (get-workspace-dir project-name branch-name))
                  exit-code (future (sh/exit-code process))]
              (or (= @exit-code 0) false)))

          (update-view [branch]
            ;; Run `make` (in the background) to rebuild the view, with output
            ;; directed to the branch's console.txt file.
            (log/info "Rebuilding view" view-path "from branch" branch-name "of project"
                      project-name "for user" (-> request :session :user :login))
            (let [process (sh/proc "bash" "-c"
                                   (str "exec make " view-path
                                        " > ../../temp/" branch-name "/console.txt "
                                        " 2>&1")
                                   :dir (get-workspace-dir project-name branch-name))
                  exit-code (future (sh/exit-code process))]
              (assoc branch
                     :action view-path
                     :command (str "make " view-path)
                     :process process
                     :start-time (System/currentTimeMillis)
                     :cancelled? false
                     :exit-code (future (sh/exit-code process)))))

          (deliver-view []
            ;; Serve the view from the filesystem:
            (-> (get-workspace-dir project-name branch-name)
                (str view-path)
                (file-response)
                ;; Views must not be cached by the client browser:
                (assoc :headers {"Cache-Control" "no-store"})))]

    (let [branch-key (keyword branch-name)
          project-key (keyword project-name)
          branch-agent (->> @data/local-branches project-key branch-key)
          branch-url (str "/" project-name "/branches/" branch-name)
          this-url (str branch-url "/views/" view-path)
          ;; Kick off a job to refresh the branch information, wait for it to complete, and then
          ;; finally retrieve the views from the branch's Makefile. Only a request to retrieve
          ;; one of these allowed views will be honoured:
          allowed-views (do (send-off branch-agent data/refresh-local-branch)
                            (await branch-agent)
                            (-> @branch-agent :Makefile :views))]

      ;; Note that below we do not call (await) after calling (send-off), because below we
      ;; always then redirect back to the view page, which results in another call to this function,
      ;; which always calls await when it starts (see above).
      (cond
        ;; If the view-path isn't in the set of allowed views, then render a 404:
        (not (some #(= view-path %) allowed-views))
        (render-404 request)

        ;; If the 'missing-view' parameter is present, then render the page for the branch. It will
        ;; be handled during rendering and a notification area will be displayed on the page
        ;; informing the user (who has read-only access) that the view does not exist:
        (not (nil? missing-view))
        (render-branch-page @branch-agent request)

        ;; If the user has read-only access, then if the view exists deliver it whether or not it is
        ;; up to date. If the view does not exist, then redirect back to the page with the
        ;; missing-view parameter set.
        (read-only? request)
        (if (view-exists? @branch-agent view-path)
          (deliver-view)
          (redirect (-> this-url (str "?missing-view=") (str view-path))))

        ;; Render the current version of the view if either the 'force-old' parameter is present, or
        ;; if the current version of the view is already up-to-date:
        (or (not (nil? force-old))
            (up-to-date?))
        (deliver-view)

        ;; If there is a currently running process (indicated by the presence of an unrealised
        ;; exit code on the branch), then what needs to be done will be determined by the
        ;; presence of further query parameters sent in the request.
        (and (->> @branch-agent :process (nil?) (not))
             (->> @branch-agent :exit-code (realized?) (not)))
        (cond
          ;; If the force-kill parameter has been sent, kill the process, update the view,
          ;; and redirect the user back to the view again:
          (not (nil? force-kill))
          (do
            (send-off branch-agent kill-process (-> request :session :user))
            (send-off branch-agent update-view)
            (redirect this-url))

          ;; If the confirm-kill parameter has been sent, then simply render the page for the
          ;; branch. The confirm-kill flag will be recognised during rendering and a prompt
          ;; will be displayed to request that the user confirm his action:
          (not (nil? confirm-kill))
          (render-branch-page @branch-agent request)

          ;; If the action currently running is an update of the very view we are requesting,
          ;; then do nothing and redirect back to the branch page:
          (->> @branch-agent :action (= view-path))
          (redirect branch-url)

          ;; Otherwise redirect back to the page with the confirm-kill flag set:
          :else
          (redirect (str this-url "?confirm-kill=1#console")))

        ;; If the 'force-update' parameter is present, immediately (re)build the view in the
        ;; background and then redirect to the branch's page where the user can view the build
        ;; process's output in the console:
        (not (nil? force-update))
        (do
          (send-off branch-agent update-view)
          ;; Here we aren't (await)ing for the process to finish, but for the branch to update,
          ;; which in this case means adding a reference to the currently running build process:
          (await branch-agent)
          (redirect branch-url))

        ;; If the 'confirm-update' parameter is present, simply render the page for the branch. The
        ;; confirm-update flag will be recognised during rendering and a prompt will be displayed
        ;; asking the user to confirm her action:
        (not (nil? confirm-update))
        (render-branch-page @branch-agent request)

        ;; Otherwise redirect back to this page, setting the confirm-update flag to ask the user if
        ;; she would really like to rebuild the file:
        :else
        (redirect (str this-url "?confirm-update=1"))))))

(defn hit-branch
  "Render a branch, possibly performing an action on it in the process. Note that this function will
  call the branch's agent to potentially modify the branch."
  [{:keys [params]
    {:keys [project-name branch-name new-action new-action-param confirm-kill force-kill]} :params
    :as request}]
  (letfn [(launch-process [branch]
            (log/info "Starting action" new-action "on branch" branch-name "of project"
                      project-name "for user" (-> request :session :user :login)
                      (when new-action-param (str "with parameter " new-action-param)))
            (let [command (if (some #(= % new-action) (->> gh/git-actions (keys) (map name)))
                            ;; If the action is a git action then lookup its corresponding command
                            ;; in the git-actions map. If the action returned is a function, then
                            ;; pass it the new-action-param and the current uder to generate the
                            ;; command string, otherwise use it as is.
                            (-> gh/git-actions
                                (get (keyword new-action))
                                (#(if (function? %)
                                    (% {:param new-action-param, :user (-> request :session :user)})
                                    (cond
                                      (= % "git-push")
                                      (do (data/store-creds project-name branch-name request)
                                          %)

                                      :else
                                      %))))
                            ;; Otherwise prefix it with "make ":
                            (str "make " new-action))
                  process (when-not (nil? command)
                            (sh/proc "bash" "-c"
                                     ;; `exec` is needed here to prevent the make process from
                                     ;; detaching from the parent (since that would make it
                                     ;; difficult to destroy later).
                                     (str
                                      "exec " command
                                      " > ../../temp/" branch-name "/console.txt"
                                      " 2>&1")
                                     :dir (get-workspace-dir project-name branch-name)))]
              ;; Sleep briefly:
              (Thread/sleep 1000)
              (if (nil? command)
                ;; If the command is nil just return the branch back unchanged:
                branch
                ;; Otherwise add a pointer to the spawned process to the branch as
                ;; well as the command name and other meta-information:
                (assoc branch
                       :action new-action
                       :command command
                       :process process
                       :start-time (System/currentTimeMillis)
                       :cancelled? false
                       :exit-code (future (sh/exit-code process))))))]

    (log/debug "Processing request in hit-branch with params:" params)
    (let [branch-key (keyword branch-name)
          project-key (keyword project-name)
          branch-agent (->> @data/local-branches project-key branch-key)
          last-exit-code (:exit-code @branch-agent)
          this-url (str "/" project-name "/branches/" branch-name)]

      ;; Send off a branch refresh and then (await) for it (and for any previous modifications to
      ;; the branch) to finish:
      (send-off branch-agent data/refresh-local-branch)
      (await branch-agent)

      ;; If the associated process is a git action, then wait for it to finish, which is
      ;; accomplished by dereferencing its exit code:
      (when (->> gh/git-actions (keys) (map name) (some #(= % (:action @branch-agent))))
        (-> @branch-agent :exit-code (deref)))

      ;; Note that below we do not call (await) after calling (send-off), because below we
      ;; always redirect back to the branch page, which results in another call to this function,
      ;; which always calls await when it starts (see above).
      (cond
        ;; This first condition will not normally be hit, since the action buttons should all
        ;; be greyed out for read-only users. But this could nonetheless happen if the user tries
        ;; to manually enter the action url into the browser's address bar, so we guard against that
        ;; here:
        (and (not (nil? new-action))
             (read-only? request))
        (do
          (log/warn "User" (-> request :session :user :login) "with read-only access attempted to"
                    "start action" new-action "and was prevented from doing so.")
          (render-branch-page @branch-agent request))

        ;; If this is a cancel action, kill the existing process and redirect to the branch page:
        (= new-action "cancel-DROID-process")
        (do
          (send-off branch-agent kill-process (-> request :session :user))
          (redirect this-url))

        ;; If there is no action to be performed, or the action is unrecognised, then just render
        ;; the page using the existing branch data:
        (and (not-any? #(= % new-action) (->> @branch-agent :Makefile :general-actions))
             (not-any? #(= % new-action) (->> gh/git-actions (keys) (map name))))
        (do
          (when-not (nil? new-action)
            (log/warn "Unrecognized action:" new-action))
          (render-branch-page @branch-agent request))

        ;; If there is a currently running process (indicated by the presence of an unrealised
        ;; exit code on the branch), then what needs to be done will be determined by the
        ;; presence of further query parameters sent in the request.
        (and (not (nil? last-exit-code))
             (not (realized? last-exit-code)))
        (cond
          ;; If the force-kill parameter has been sent, then simply kill the old process and
          ;; relaunch the new one:
          (not (nil? force-kill))
          (do
            (send-off branch-agent kill-process (-> request :session :user))
            (send-off branch-agent launch-process)
            (-> this-url
                ;; Remove the force kill and new action parameters:
                (str "?" (-> params
                             (dissoc :force-kill :new-action :new-action-param)
                             (params-to-query-str)))
                (redirect)))

          ;; If the confirm-kill parameter has been sent, then simply render the page for the
          ;; branch. The confirm-kill flag will be recognised during rendering and a prompt
          ;; will be displayed to request that the user confirm his action:
          (not (nil? confirm-kill))
          (render-branch-page @branch-agent request)

          ;; If neither the force-kill nor the confirm-kill parameter has been sent, then
          ;; send the action again (by redirecting to the branch page with the action parameter set)
          ;; and include the confirm-kill parameter which will be used during rendering to display
          ;; a prompt.
          :else
          (-> this-url
              (str "?" (-> params (assoc :confirm-kill 1) (params-to-query-str)))
              (str "#console")
              (redirect)))

        ;; If there is an action to be performed and there is no currently running process, then
        ;; simply launch the new process and redirect back to the page:
        :else
        (do
          (send-off branch-agent launch-process)
          (-> this-url
              ;; Remove the new action params so that the user will not be able to launch the action
              ;; again by hitting his browser's refresh button by mistake:
              (str "?" (-> params (dissoc :new-action :new-action-param) (params-to-query-str)))
              (redirect)))))))

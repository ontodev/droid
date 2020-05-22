(ns droid.html
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [hiccup.page :refer [html5]]
            [me.raynes.conch.low-level :as sh]
            [droid.config :refer [config]]
            [droid.data :as data]
            [droid.make :as make]
            [droid.dir :refer [get-workspace-dir]]
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
      [:h1 [:a {:href "/"} heading]]
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
  "Given the name of a project and branch, output a summary string of the branch's commit status
  as compared with its remote."
  [project-name branch-name]
  (let [branch-agent (-> @data/local-branches
                         (get (keyword project-name))
                         (get (keyword branch-name)))]
    (send-off branch-agent data/refresh-local-branch)
    (await branch-agent)
    (if-not (-> @branch-agent :git-status :remote)
      "no remote"
      (str (-> @branch-agent :git-status :ahead) " ahead, "
           (-> @branch-agent :git-status :behind) " behind '"
           (-> @branch-agent :git-status :remote) "'"
           (when (-> @branch-agent :git-status :uncommitted?)
             ", with uncommitted changes")))))

(defn- render-project-branches
  "Given the name of a project, render a list of its branches, with links. If the restricted-access?
  flag is set to true, do not display any links that would result in changes to the workspace."
  [project-name restricted-access?]
  (let [local-branch-names (->> project-name
                                (keyword)
                                (get @data/local-branches)
                                (keys)
                                (sort)
                                (map name))
        project-url (str "/" project-name)]
    [:ul
     (when-not restricted-access?
       [:li [:a {:href (str project-url "?create=1") :class "badge badge-success"
                 :data-toggle "tooltip" :title "Create a new local branch"} "Create new"]])
     (for [local-branch-name local-branch-names]
       [:li
        (when-not restricted-access?
          [:span
           [:a {:href (str project-url "?to-delete=" local-branch-name)
                :data-toggle "tooltip" :title "Delete this branch"
                :class "badge badge-danger"} "Delete"]
           [:span "&nbsp;"]])
        [:a {:href (str project-url "/branches/" local-branch-name)}
         local-branch-name] (str " (" (branch-status-summary project-name local-branch-name)  ")")])

     (when-not restricted-access?
       (for [remote-branch (-> @data/remote-branches
                               (get (keyword project-name)))]
         (when (not-any? #(= (:name remote-branch) %) local-branch-names)
           [:li
            [:a {:href (str project-url "?to-checkout=" (:name remote-branch))
                 :data-toggle "tooltip" :title "Checkout a local copy of this branch"
                 :class "badge badge-info"} "Checkout"]
            [:span "&nbsp;"] (:name remote-branch)])))]))

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
  [{{:keys [just-logged-out reset really-reset]} :params,
    {{:keys [login]} :user} :session,
    :as request}]
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
        :heading "DROID"
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
                    (when (and (site-admin?) (nil? reset))
                      [:div {:class "pb-3"}
                       [:a {:class "btn btn-sm btn-danger" :href "/?reset=1"
                            :data-toggle "tooltip"
                            :title "Clear branch data for all projects"}
                        "Reset branch data"]])
                    [:ul {:class "list-unstyled"}
                     (for [project (-> config :projects)]
                       [:li [:a {:href (->> project (key) (str "/"))}
                             (->> project (val) :project-title)]
                        [:span "&nbsp;"]
                        (->> project (val) :project-description)])]]]]}))))

(defn render-project
  "Render the home page for a project"
  [{{:keys [project-name refresh to-delete to-really-delete to-checkout
            create invalid-name-error to-create branch-from]} :params,
    :as request}]
  (let [this-url (str "/" project-name)]
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
                  ;; Create the branch, then refresh the local branch list so that it shows up on
                  ;; the page:
                  (send-off data/local-branches data/create-local-branch project-name
                            branch-name branch-from)
                  (send-off data/local-branches data/refresh-local-branches [project-name])
                  (await data/local-branches)
                  (redirect this-url))))]

      ;; Perform an action based on the parameters present in the request:
      (cond
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

        ;; Otherwise just render the page.
        :else
        (let [project (-> config :projects (get project-name))]
          (if (nil? project)
            (render-404 request)
            (html-response
             request
             {:title (->> project :project-title (str "DROID for "))
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
                             ;; Select list on available local branches to serve as the
                             ;; branching-off point:
                             [:div {:class "col-sm-3"}
                              [:div
                               [:label {:for "branch-from" :class "mb-n1 text-secondary"}
                                "Branch from"]]
                              [:select {:id "branch-from" :name "branch-from"
                                        :class "form-control form-control-sm"}
                               (for [branch-name (-> @data/local-branches
                                                     (get (keyword project-name))
                                                     (keys)
                                                     (#(map name %))
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

                        ;; The main content of the project page:
                        [:h3 "Branches"]
                        [:div {:class "pb-2"}
                         [:a {:class "btn btn-sm btn-primary"
                              :href (str this-url "?refresh=1")
                              :data-toggle "tooltip"
                              :title "Refresh the list of available local and remote branches"}
                          "Refresh"]]
                        (->> request (read-only?) (render-project-branches project-name))]})))))))

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
   new-action]
  (let [branch-url (str "/" project-name "/branches/" branch-name)]
    [:div {:class "alert alert-warning"}
     [:p [:span {:class "text-monospace font-weight-bold"} action]
      " is still not complete. You must cancel it before running "
      [:span {:class "text-monospace font-weight-bold"} new-action]
      ". Do you really want to do this?"]
     [:span [:a {:class "btn btn-secondary btn-sm" :href branch-url} "No, do nothing"]]
     [:span {:class "col-sm-1"}]
     [:span [:a {:class "btn btn-danger btn-sm"
                 :href (str branch-url "?new-action=" new-action "&force-kill=1")}
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
   view-path]
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
  [{:keys [action start-time command exit-code console] :as branch}
   {:keys [new-action view-path confirm-update confirm-kill] :as params}]
  (letfn [(render-status-bar []
            (cond
              (and (not (nil? view-path))
                   (not (nil? confirm-kill)))
              (prompt-to-kill-for-view branch view-path)

              (and (not (nil? new-action))
                   (not (nil? confirm-kill)))
              (prompt-to-kill-for-action branch new-action)

              :else
              (render-status-bar-for-action branch)))]

    ;; Render the part of the branch corresponding to the console:
    [:div {:id "console"}
     (if (nil? exit-code)
       ;; If no process is running or has been run, then ask the user to kick one off:
       [:p "Press a button above to execute an action."]
       ;; Otherwise render the info for the action, the status bar, and the literal command
       ;; corresponding to it:
       (let [readable-start (->> start-time (java.time.Instant/ofEpochMilli) (str))]
         [:div
          [:p {:class "alert alert-info"} (str "Action " action " started at ")
           [:span {:class "date"} readable-start] " ("
           [:span {:class "since"} readable-start] ")"]]))

     (render-status-bar)
     (if-not (empty? command)
       [:pre [:code {:class "shell"} (str "$ " command)]]
       [:div])

     ;; Render the contents of the console itself:
     [:pre [:code {:class "shell"} console]]

     ;; If an action is running and the console has a large number of lines in it, render the status
     ;; bar again:
     (when-not (nil? exit-code)
       (when (<= 35 (->> console
                         (filter #(= \newline  %))
                         (count)))
         (render-status-bar)))]))

(defn- render-branch-page
  "Given some branch data, and a number of parameters related to an action or a view, construct the
  page corresponding to a branch."
  [{:keys [branch-name project-name Makefile process] :as branch}
   {:keys [params]
    {:keys [new-action view-path missing-view confirm-kill confirm-update]} :params
    :as request}]
  (html-response
   request
   {:title (-> config :projects (get project-name) :project-title
               (str " -- " branch-name))
    :heading (str "DROID for "
                  (-> config :projects (get project-name) :project-title))
    :content [:div
              [:p (-> config :projects (get project-name) :project-description)]
              [:div
               [:h2 [:a {:href (str "/" project-name)} (str "Branch: " branch-name)]]
               [:small [:p {:class "mt-n2"}
                        (str " (" (branch-status-summary project-name branch-name)  ")")]]

               [:h3 "Workflow"]
               ;; If the missing-view parameter is present, then the user with read-only access is
               ;; trying to look at a view that doesn't exist:
               (cond
                 (not (nil? missing-view))
                 (notify-missing-view branch view-path)

                 (not (nil? confirm-update))
                 (prompt-to-update-view branch view-path))

               ;; Render the Workflow HTML from the Makefile:
               (or (:html Makefile)
                   [:ul [:li "No workflow found"]])

               [:h3 "Console"]
               (render-console branch params)]]}))

(defn view-file
  "View a file in the workspace if it is in the list of allowed views for the branch. If the file is
  out of date, then ask whether the current version should be served or whether it should be
  rebuilt."
  [{{:keys [project-name branch-name view-path confirm-update force-old force-update
            confirm-kill force-kill missing-view]} :params,
    :as request}]
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
  [{{:keys [project-name branch-name new-action confirm-kill force-kill]} :params
    :as request}]
  (letfn [(launch-process [branch]
            (log/info "Starting action" new-action "on branch" branch-name "of project"
                      project-name "for user" (-> request :session :user :login))
            (let [process (sh/proc "bash" "-c"
                                   ;; `exec` is needed here to prevent the make process from
                                   ;; detaching from the parent (since that would make it difficult
                                   ;; to destroy later).
                                   (str
                                    "exec make " new-action
                                    " > ../../temp/" branch-name "/console.txt"
                                    " 2>&1")
                                   :dir (get-workspace-dir project-name branch-name))]
              ;; After spawning the process, add a pointer to it to the branch as
              ;; well as its meta-information:
              (Thread/sleep 1000)
              (assoc branch
                     :action new-action
                     :command (str "make " new-action)
                     :process process
                     :start-time (System/currentTimeMillis)
                     :cancelled? false
                     :exit-code (future (sh/exit-code process)))))]

    (let [branch-key (keyword branch-name)
          project-key (keyword project-name)
          branch-agent (->> @data/local-branches project-key branch-key)
          last-exit-code (:exit-code @branch-agent)
          this-url (str "/" project-name "/branches/" branch-name)]

      ;; Send off a branch refresh and then (await) for it (and for any previous jobs) to finish:
      (send-off branch-agent data/refresh-local-branch)
      (await branch-agent)

      ;; Note that below, whenever an action is performed on a branch, we then redirect back to
      ;; the branch page. The main reason for this is to get rid of the '?new-action=...' part of
      ;; the URL in the browser's address bar. This is important because otherwise the user could
      ;; then re-launch the action by mistake simply by hitting her browser's refresh button.

      ;; Note also that below we do not call (await) after calling (send-off), because below we
      ;; always redirect back to the branch page, which results in another call to this function,
      ;; which always calls await when it starts (see above).
      (cond
        ;; This first condition should not normally be reached, since the action buttons should all
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
        (not-any? #(= % new-action) (->> @branch-agent :Makefile :general-actions))
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
            (redirect this-url))

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
          (redirect (str this-url "?new-action=" new-action "&confirm-kill=1#console")))

        ;; If there is an action to be performed and there is no currently running process, then
        ;; simply launch the new process:
        :else
        (do
          (send-off branch-agent launch-process)
          (redirect this-url))))))

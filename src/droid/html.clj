(ns droid.html
  (:require [clojure.java.io :as io]
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

(defn- login-status
  "Render the user's login status."
  [request]
  (let [user (-> request :session :user)
        user-link [:a {:target "__blank" :href (:html_url user)} (:name user)]]
    (cond
      (:authorized user)
      [:div "Logged in as " user-link]

      (-> user (nil?) (not))
      [:div "You are logged in as " user-link
       " but you are not authorized to access this site."]

      :else
      [:div [:a {:href "/oauth2/github"} "Please log in with GitHub"]])))

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
                   "});")]])})

(defn- kill-process [branch]
  "Kill the running process associated with the given branch and return the branch to the caller."
  (let [p (->> (data/refresh-branch branch) :process)]
    (when-not (nil? p)
      (sh/destroy p)
      (assoc branch :cancelled? true))))

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
  [request]
  (html-response
   request
   {:title "DROID"
    :heading "DROID"
    :content [:div
              [:p "DROID Reminds us that Ordinary Individuals can be Developers"]
              [:p (login-status request)]
              (when (-> request :session :user :authorized)
                [:div
                 [:hr {:class "line1"}]
                 [:div [:h3 "Available Projects"]
                  [:ul
                   (for [project (-> config :projects)]
                     [:li [:a {:href (->> project (key) (str "/"))}
                           (->> project (val) :project-title)]])]]])]}))

(defn render-project
  "Render the home page for a project"
  [{:keys [session]
    {:keys [project-name]} :params
    :as request}]
  (let [project (-> config :projects (get project-name))]
    (if (nil? project)
      (render-404 request)
      (html-response
       request
       {:title (->> project :project-title (str "DROID for "))
        :content [:div
                  [:p (->> project :project-description)]
                  [:p (login-status request)]
                  (when (-> session :user :authorized)
                    [:div
                     [:hr {:class "line1"}]
                     [:div [:h3 "Branches"]
                      [:ul
                       (for [branch-name (->> project-name
                                              (keyword)
                                              (get data/branches)
                                              (keys)
                                              (sort)
                                              (map name))]
                         [:li [:a {:href (str project-name "/branches/" branch-name)}
                               branch-name]])]]])]}))))

(defn view-file!
  "View a file in the workspace if it is in the list of allowed views for the branch. If the file is
  out of date, then ask whether the current version should be served or whether it should be
  rebuilt."
  [{{:keys [project-name branch-name view-path force-old force-kill force-update status-msg]}
    :params, :as request}]
  (letfn [(view-exists? []
            ;; Check whether the path corresponding to the view is in the filesystem:
            (-> (get-workspace-dir project-name branch-name)
                (str "/" view-path)
                (io/file)
                (.exists)))

          (up-to-date? []
            ;; Run `make -q` (in the foreground) in the shell to see if the view is up to date:
            (let [process (sh/proc "make" "-q" view-path
                                   :dir (get-workspace-dir project-name branch-name))
                  exit-code (future (sh/exit-code process))]
              (or (= @exit-code 0) false)))

          (update-view [branch]
            ;; Run `make` (in the background) to rebuild the view, with output
            ;; directed to the branch's console.txt file.
            (let [process (sh/proc "bash" "-c"
                                   (str "exec make " view-path
                                        " > ../../temp/" branch-name "/console.txt "
                                        " 2>&1")
                                   :dir (get-workspace-dir project-name branch-name))
                  exit-code (future (sh/exit-code process))]
              (assoc (data/refresh-branch branch)
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
                (assoc :headers {"Cache-Control" "no-store"})))

          (prompt-to-update []
            ;; Ask the user whether she would like to update the view, or see the the
            ;; current version of the view instead (if it exists):
            (html-response
             request
             {:content
              [:div
               [:div {:class "alert alert-info"}
                [:span {:class "font-weight-bold"}
                 [:span {:class "text-monospace"} view-path]
                 " is not up to date."]]
               [:h5 "What now?"]
               [:div
                [:span [:a {:class "btn btn-secondary btn-sm"
                            :href (str "/" project-name "/branches/" branch-name)}
                        "Go back to " branch-name]]
                [:span {:class "col-sm-1"}]
                [:span [:a {:class "btn btn-danger btn-sm" :href "?force-update=1"}
                        "Rebuild the view"]]
                [:span {:class "col-sm-1"}]
                (when (view-exists?)
                  [:span [:a {:class "btn btn-warning btn-sm" :href "?force-old=1"}
                          "View the current version"]])]
               ;; If the parameters include a status message to display, do so here:
               (when-not (nil? status-msg)
                 [:div [:div [:br]]
                  [:div {:class "alert alert-secondary"} status-msg]])]}))

          (prompt-to-kill [{:keys [start-time run-time command console] :as branch}]
            ;; Only one user process per branch should run. If there is one already running, ask the
            ;; user whether he would like to kill it:
            (let [readable-start (->> start-time (java.time.Instant/ofEpochMilli) (str))]
              (html-response
               request
               {:content
                [:div
                 [:div {:class "alert alert-info"}
                  [:span {:class "font-weight-bold"}
                   [:span {:class "text-monospace"} view-path] " is not up to date."]
                  [:br]
                  "To update it you must first kill the currently running process, "
                  "which has been running since "
                  [:span {:class "date"} readable-start] " ("
                  [:span {:class "since"} readable-start] "). "]
                 [:h5 "What now?"]
                 [:div
                  [:span [:a {:class "btn btn-secondary btn-sm"
                              :href (str "/" project-name "/branches/" branch-name)}
                          "Go back to " branch-name]]
                  [:span {:class "col-sm-1"}]
                  [:span [:a {:class "btn btn-danger btn-sm"
                              :href (str "/" project-name "/branches/" branch-name
                                         "/views/" view-path "?force-kill=1")}
                          "Kill the process"]]
                  [:span {:class "col-sm-1"}]
                  (when (view-exists?)
                    [:span [:a {:class "btn btn-warning btn-sm" :href "?force-old=1"}
                            "View the current version"]])]
                 [:hr {:class "line1"}]
                 [:div
                  [:h3 "Console"]
                  [:pre [:code {:class "shell"} (str "$ " command)]]
                  [:pre [:code {:class "shell"} console]]]]})))]

    (let [branch-key (keyword branch-name)
          project-key (keyword project-name)
          branch-agent (->> data/branches project-key branch-key)
          ;; Kick off a job to refresh the branch information, wait for it to complete, and then
          ;; finally retrieve the views from the branch's Makefile. Only a request to retrieve
          ;; one of these allowed views will be honoured:
          allowed-views (do (send-off branch-agent data/refresh-branch)
                            (await branch-agent)
                            (-> @branch-agent :Makefile :views))]
      (cond
        ;; If the view-path isn't in the set of allowed views, then render a 404:
        (not (some #(= view-path %) allowed-views))
        (render-404 request)

        ;; Render the current version of the view if either the 'force-old' parameter is present, or
        ;; if the current version of the view is already up-to-date:
        (or (not (nil? force-old))
            (up-to-date?))
        (deliver-view)

        ;; If the 'force-kill' parameter has been passed, then immediately kill any process that is
        ;; currently running, and reload the page, including a status message to indicate the kill:
        (not (nil? force-kill))
        (do (send-off branch-agent kill-process)
            (await branch-agent)
            (redirect (->> "The process has been terminated."
                           (codec/url-encode)
                           (str "/" project-name "/branches/" branch-name "/views/" view-path
                                "?status-msg="))))

        ;; If there is a process running, ask the user to confirm whether she'd like to kill it:
        (and (->> @branch-agent :process (nil?) (not))
             (->> @branch-agent :exit-code (realized?) (not)))
        (prompt-to-kill @branch-agent)

        ;; If the 'force-update' parameter is present, immediately (re)build the view in the
        ;; background and then redirect to the branch's page where the user can view the build
        ;; process's output in the console:
        (not (nil? force-update))
        (do
          (send-off branch-agent update-view)
          ;; Here we are not awaiting the process to finish, but waiting for the branch to be updated,
          ;; including adding to it a reference to the currently running build process:
          (await branch-agent)
          (redirect (str "/" project-name "/branches/" branch-name)))

        ;; Otherwise ask the user to confirm that he'd really like to update the file:
        :else
        (prompt-to-update)))))

(defn hit-branch!
  "Render a branch, possibly performing an action on it in the process. Note that this function will
  call the branch's agent to potentially modify the branch."
  [{{:keys [project-name branch-name action force-kill]} :params
    :as request}]
  (letfn [(render-branch-console
            [{:keys [action start-time run-time command console exit-code cancelled?]
              :as branch}]
            ;; Render the part of the branch corresponding to the console:
            [:div
             (let [readable-start (->> start-time (java.time.Instant/ofEpochMilli) (str))]
               [:p {:class "alert alert-info"} (str "Action " action " started at ")
                [:span {:class "date"} readable-start] " ("
                [:span {:class "since"} readable-start] ")"])

             [:pre [:code {:class "shell"} (str "$ " command)]]
             [:pre [:code {:class "shell"} console]]

             (cond
               cancelled?
               [:p {:class "alert alert-warning"} "Process cancelled"]

               (nil? exit-code)
               [:div]

               (not (realized? exit-code))
               [:p {:class "alert alert-warning"}
                (str "Processes still running after "
                     (-> run-time (/ 1000) (float) (Math/ceil) (int)) " seconds.")
                [:span {:class "col-sm-2"} [:a {:class "btn btn-success btn-sm"
                                                :href (str "/" project-name "/branches/"
                                                           branch-name)}
                                            "Refresh"]]
                [:span {:class "col-sm-2"} [:a {:class "btn btn-danger btn-sm"
                                                :href "?action=cancel"} "Cancel"]]]

               (= 0 @exit-code)
               [:p {:class "alert alert-success"} "Success"]

               :else
               [:p {:class "alert alert-danger"} (str "ERROR: Exit code " @exit-code)])])

          (render-branch-outer-page [{:keys [branch-name Makefile process] :as branch}]
            ;; Render the page for a branch:
            (html-response
             request
             {:title (-> config :projects (get project-name) :project-title
                         (str " -- " branch-name))
              :heading (str "DROID for "
                            (-> config :projects (get project-name) :project-title))
              :content [:div
                        [:p (login-status request)]
                        [:div
                         [:hr {:class "line1"}]

                         [:h2 [:a {:href (str "/" project-name)} (str "Branch: " branch-name)]]

                         [:h3 "Workflow"]
                         (or (:html Makefile)
                             [:ul [:li "Not found"]])

                         [:h3 "Console"]
                         (if (nil? process)
                           [:p "Press a button above to execute an action."]
                           (render-branch-console branch))]]}))

          (launch-process [branch]
            (let [process (sh/proc "bash" "-c"
                                   ;; `exec` is needed here to prevent the make process from
                                   ;; detaching from the parent (that ;; will make it difficult
                                   ;; to destroy later).
                                   (str
                                    "exec make " action
                                    " > ../../temp/" branch-name "/console.txt"
                                    " 2>&1")
                                   :dir (get-workspace-dir project-name branch-name))]
              ;; After spawning the process, add a pointer to it to the branch as
              ;; well as its meta-information:
              (Thread/sleep 1000)
              (assoc branch
                     :action action
                     :command (str "make " action)
                     :process process
                     :start-time (System/currentTimeMillis)
                     :cancelled? false
                     :exit-code (future (sh/exit-code process)))))

          (act-on-branch [branch]
            (let [last-exit-code (->> branch :exit-code)]
              ;; Perform the requested action:
              (cond
                (= action "cancel")
                (if (and (not (nil? last-exit-code))
                         (not (realized? last-exit-code)))
                  (kill-process branch)
                  branch)

                ;; If the specified action is recognized then launch a corresponding process, killing
                ;; any other running process first:
                (some #(= % action) (->> branch :Makefile :general-actions))
                (do
                  (if (and (not (nil? last-exit-code))
                           (not (realized? last-exit-code)))
                    (->> branch (kill-process) (launch-process))
                    (->> branch (launch-process))))

                :else
                (do
                  (when (not (nil? action))
                    (log/warn "Unrecognized action:" action))
                  branch))))

          (prompt-to-kill [{:keys [start-time run-time command console] :as branch}]
            ;; Only one user process per branch should run. If there is one already running, ask the
            ;; user whether he would like to kill it:
            (let [readable-start (->> start-time (java.time.Instant/ofEpochMilli) (str))]
              (html-response
               request
               {:content
                [:div
                 [:div {:class "alert alert-info"}
                  "A process, begun at "
                  [:span {:class "date"} readable-start] " ("
                  [:span {:class "since"} readable-start] "), "
                  "is already in progress. You must cancel it before running action "
                  [:span {:class "text-monospace font-weight-bold"} action] "."]
                 [:div
                  [:span [:a {:class "btn btn-secondary btn-sm"
                              :href (str "/" project-name "/branches/" branch-name)}
                          "Go back to " branch-name]]
                  [:span {:class "col-sm-1"}]
                  [:span [:a {:class "btn btn-danger btn-sm"
                              :href (str "/" project-name "/branches/" branch-name
                                         "?action=" action "&force-kill=1")}
                          "Cancel process and run action"]]
                  [:span {:class "col-sm-1"}]]
                 [:hr {:class "line1"}]
                 [:div
                  [:h3 "Console"]
                  [:pre [:code {:class "shell"} (str "$ " command)]]
                  [:pre [:code {:class "shell"} console]]]]})))]

    (let [branch-key (keyword branch-name)
          project-key (keyword project-name)
          branch-agent (->> data/branches project-key branch-key)
          last-exit-code (:exit-code @branch-agent)]
      (send-off branch-agent data/refresh-branch)
      (await branch-agent)

      ;; If there is an action (other than cancel) and the force-kill parameter is not present, and
      ;; if there is a process associated with the branch that is still running, then ask the user
      ;; whether to kill it or not.
      (if (and (not (nil? action))
               (not (= action "cancel"))
               (nil? force-kill)
               (not (nil? last-exit-code))
               (not (realized? last-exit-code)))
        (prompt-to-kill @branch-agent)
        ;; Otherwise just continue on as normal:
        (do
          ;; Perform any needed actions on the branch and wait for it to be done:
          (send-off branch-agent act-on-branch)
          (await branch-agent)

          ;; If we performed an action, then we now redirect to the branch page (the main reason for
          ;; this is to get rid of the '?action=...' part of the URL in the address bar, which is
          ;; desirable because we don't want to kick off the action again just because the user hits
          ;; her browser's refresh button):
          (if-not (nil? action)
            (redirect (str "/" project-name "/branches/" branch-name))
            (render-branch-outer-page @branch-agent)))))))

(ns droid.html
  (:require [hiccup.page :refer [html5]]
            [me.raynes.conch.low-level :as sh]
            [droid.config :refer [config]]
            [droid.data :as data]
            [droid.log :as log]))


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
         title (->> config :project-name (str "DROID for "))
         heading title}}]
  {:status 200
   :session session
   :headers default-html-headers
   :body
   (html5
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport"
             :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
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
                   "$('.since').each(function() {" ; replace GMT dates with driendly time period
                   "  $(this).text(moment($(this).text()).fromNow());"
                   "});")]])})


(defn- render-branch-console
  "Render the part of the branch corresponding to the console"
  [branch branch-name]
  [:div
   (let [readable-start (->> branch :start-time (java.time.Instant/ofEpochMilli) (str))]
     [:p {:class "alert alert-info"} (str "Action " (:action branch) " started at ")
      [:span {:class "date"} readable-start] " (" [:span {:class "since"} readable-start] ")"])

   [:pre [:code {:class "shell"} (str "$ " (:command branch))]]
   [:pre [:code {:class "shell"} (:console branch)]]

   (let [exit-code (:exit-code branch)]
     (cond
       (:cancelled branch)
       [:p {:class "alert alert-warning"} "Process cancelled"]

       (nil? exit-code)
       [:div]

       (not (realized? exit-code))
       [:p {:class "alert alert-warning"}
        (str "Processes still running after "
             (-> branch :run-time (/ 1000) (float) (Math/ceil) (int)) " seconds.")
        [:span {:class "col-sm-2"} [:a {:class "btn btn-success btn-sm"
                                        :href (str "/branches/" branch-name)} "Refresh"]]
        [:span {:class "col-sm-2"} [:a {:class "btn btn-danger btn-sm"
                                        :href "?action=cancel"} "Cancel"]]]

       (= 0 @exit-code)
       [:p {:class "alert alert-success"} "Success"]

       :else
       [:p {:class "alert alert-danger"} (str "ERROR: Exit code " @exit-code)]))])


(defn- render-branch
  "Render the page for a branch"
  [request branch-name branch]
  (html-response
   request
   {:title (-> config :project-name (str " -- " branch-name))
    :heading (->> config :project-name (str "DROID for "))
    :content [:div
              [:p (login-status request)]
              [:div
               [:hr {:class "line1"}]

               [:h2 (str "Branch: " branch-name)]

               [:h3 "Workflow"]
               (or (->> branch :Makefile :html)
                   [:ul [:li "Not found"]])

               [:h3 "Console"]
               (if (->> branch :process (nil?))
                 [:p "Press a button above to execute an action."]
                 (render-branch-console branch branch-name))]]}))


(defn act-on-branch!
  "Possibly perform an action a branch before rendering the page for the branch."
  [{{branch-name :branch-name, action :action, :as params} :params,
    :as request}]
  (let [branch (->> branch-name (keyword) (get data/branches))]
    (locking branch
      (let [get-branch-contents #(data/refresh-branch! branch-name)
            last-exit-code (->> (get-branch-contents) :exit-code)
            kill-process #(let [p (->> (get-branch-contents) :process)]
                            (when-not (nil? p)
                              (sh/destroy p)
                              (swap! branch assoc :cancelled true)))
            launch-process #(let [process (sh/proc "bash" "-c"
                                                   ;; exec is needed here to prevent the make
                                                   ;; process from detaching from the parent (which
                                                   ;; will make it difficult to destroy later).
                                                   (str "exec make " action
                                                        " > ../../temp/" branch-name "/console.txt"
                                                        " 2>&1")
                                                   :dir (str "workspace/" branch-name))]
                              ;; After spawning the process, add a pointer to it to the branch as
                              ;; well as its meta-information:
                              (swap! branch assoc
                                     :action action
                                     :command (str "make " action)
                                     :process process
                                     :start-time (System/currentTimeMillis)
                                     :cancelled false
                                     :exit-code (future (sh/exit-code process))))]
        (cond
          (= action "cancel")
          (when (and (not (nil? last-exit-code))
                     (not (realized? last-exit-code)))
            (kill-process))

          ;; If the specified action is a recognized one then launch a process corresponding to it:
          (some #(= % action) (->> (get-branch-contents) :Makefile :actions))
          (do
            (when (and (not (nil? last-exit-code))
                       (not (realized? last-exit-code)))
              (kill-process))
            (launch-process)
            (Thread/sleep 1000))

          (not (nil? action))
          (log/warn "Unrecognized action:" action))

        (render-branch request branch-name (get-branch-contents))))))


(defn index
  "Render the index page"
  [request]
  (html-response
   request
   {:title (->> config :project-name (str "DROID for "))
    :content [:div
              [:p (:project-description config)]
              [:p (login-status request)]
              (when (-> request :session :user :authorized)
                [:div
                 [:hr {:class "line1"}]
                 [:div [:h3 "Branches" ]
                  [:ul
                   (for [branch (->> data/branches (keys) (sort) (map name))]
                     [:li [:a {:href (str "/branches/" branch)} branch]])]]])]}))

(ns droid.html
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [function?]]
            [decorate.core :refer [decorate defdecorator]]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5]]
            [hickory.core :as hickory]
            [lambdaisland.uri :as uri]
            [me.raynes.conch.low-level :as sh]
            [ring.util.codec :as codec]
            [ring.util.http-status :as status]
            [ring.util.response :refer [file-response redirect]]
            [droid.branches :as branches]
            [droid.config :refer [get-config get-docker-config]]
            [droid.command :as cmd]
            [droid.fileutils :refer [get-workspace-dir get-make-dir get-temp-dir]]
            [droid.github :as gh]
            [droid.log :as log]
            [droid.make :as make]
            [droid.secrets :refer [secrets]]))

(def default-html-headers
  {"Content-Type" "text/html"})

(defn- container-rebuilding?
  "Returns true if the given branch is rebuilding a docker container"
  [project-name branch-name]
  (let [this-branch (when branch-name
                      (-> @branches/local-branches
                          (get (keyword project-name))
                          (get (keyword branch-name))
                          (#(and % (deref %)))))
        action (:action this-branch)
        exit-code (:exit-code this-branch)]
    (and this-branch
         exit-code
         (= action "create-docker-image-and-container")
         (not (realized? exit-code)))))

(defn- site-admin?
  "Returns true if the given user is a site administrator. This function always returns true when
  DROID is running in local mode."
  [{{{:keys [login]} :user} :session}]
  (or (get-config :local-mode)
      (some #(= login %) (get-config :site-admin-github-ids))))

(defn- read-only?
  "Returns true if the given user has read-only access to the site.
   Access will be read-only in the following cases:
   - The user is logged out
   - The user does not have write or admin permission on the requested project,
     and is not a site admin.
   - The container for the given branch is currently being rebuilt."
  ([project-name branch-name]
   (container-rebuilding? project-name branch-name))
  ([{{:keys [project-name branch-name]} :params
     {{:keys [login project-permissions]} :user} :session,
     :as request}]
   (let [this-project-permissions (->> project-name (keyword) (get project-permissions))]
     (or (container-rebuilding? project-name branch-name)
         (nil? login)
         (and (not= this-project-permissions "write")
              (not= this-project-permissions "admin")
              (not (site-admin? request)))))))

(defn local-mode-enabled?
  "Returns true if :local-mode is set to true and there is a personal access token configured in
  secrets, and false otherwise."
  []
  (and (contains? secrets :personal-access-token) (get-config :local-mode)))

(defn- login-status
  "Render the user's login status."
  [{{:keys [project-name]} :params,
    {:keys [user]} :session,
    :as request}]
  (let [navbar-attrs {:class "p-0 navbar navbar-light bg-transparent"}]
    (if (:authenticated user)
      ;; If the user has been authenticated, render a navbar with login info and (when not in
      ;; local mode) a logout link:
      [:nav navbar-attrs
       [:small "Logged in as " [:a {:target "__blank" :href (:html_url user)} (or (:name user)
                                                                                  (:login user))]
        (cond
          ;; If the user is viewing a project page and has read-only access, indicate this:
          (and (not (nil? project-name))
               (read-only? request)) " (read-only access)"
          ;; If DROID is running local mode indicate this:
          (local-mode-enabled?) " (local mode)")]
       ;; Only render the logout link if not in local mode:
       (when-not (local-mode-enabled?)
         [:small {:class "ml-auto"} [:a {:href "/logout"} "Logout"]])]
      ;; Otherwise the navbar will only have a login link:
      [:nav navbar-attrs
       [:small [:a {:href "/oauth2/github"} "Login via GitHub"]]])))

(defn- params-to-query-str
  "Given a map of parameters, construct a string suitable for use in a GET request"
  [params]
  ;; This function is useful for passing parameters through in a redirect without having to know
  ;; what they are. It is also used to prepare the query string when running a CGI program.
  (->> params
       (map #(-> %
                 (key)
                 (name)
                 (codec/url-encode)
                 ;; Interpolate PREV_DIR/ as ../ if a view-path is present:
                 (str "=" (-> % (val) (string/replace #"PREV_DIR/" "../") (codec/url-encode)))))
       (string/join "&")))

(defn- html-response
  "Given a request map and a response map, return the response as an HTML page."
  [{:keys [session params] :as request}
   {:keys [status headers title heading script content auto-refresh]
    {:keys [project-name branch-name interval]} :auto-refresh,
    :as response
    :or {status 200
         headers default-html-headers
         title "DROID"
         heading title}}]
  {:status status
   :session session
   :headers headers
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
     ;; Users with read-only access are not be able to click on restricted-access links:
     (when (read-only? request) [:style ".restricted-access { pointer-events: none; }"])
     [:title title]
     (when script [:script script])]
    [:body (when (get-config :html-body-colors) {:class (get-config :html-body-colors)})
     [:div {:id "content" :class "container p-3"}
      (login-status request)
      [:hr {:class "line1"}]
      [:h1 heading]
      content]
     ;; jQuery (required for some Bootstrap features):
     [:script {:src "https://code.jquery.com/jquery-3.4.1.min.js"}]
               ;;:integrity "sha384-J6qa4849blE2+poT4WnyKhv5vZF5SrPo0iEjwBvKU7imGFAV0wwj1yYfoRSJoZ+n"
               ;;:crossorigin "anonymous"}]
     ;; Popper (required for some Bootstrap features):
     [:script {:src "https://cdn.jsdelivr.net/npm/popper.js@1.16.0/dist/umd/popper.min.js"
               :integrity "sha384-Q6E9RHvbIyZFJoft+2mJbHaEWldlvI9IOYy5n3zV9zzTtmI3UksdQRVvoxMfooAo"
               :crossorigin "anonymous"}]
     ;; Bootstrap:
     [:script {:src "https://stackpath.bootstrapcdn.com/bootstrap/4.4.1/js/bootstrap.min.js"
               :integrity "sha384-wfSDF2E50Y2D1uUdj0O3uMBJnjuUD4Ih7YwaYd1iqfktj0Uod8GCExl3Og8ifwB6"
               :crossorigin "anonymous"}]
     ;; Handy time and date library:
     [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/moment.js/2.24.0/moment.min.js"}]
     ;; Library for highlighting code:
     [:script {:src "//cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.16.2/build/highlight.min.js"}]
     [:script (str
               ;; If we are auto-refreshing, scroll to the bottom of the page immediately:
               (when auto-refresh
                 "window.scrollTo(0,document.body.scrollHeight);")
               ;; Highlight code at load time:
               "hljs.initHighlightingOnLoad();"
               ;; Replace GMT dates with local dates, and replace GMT dates with friendly time
               ;; period. We declare a function since we will need to use it again later during
               ;; auto-refresh:
               "function friendlifyMoments() { "
               "  $('.date').each(function() {"
               "    $(this).text(moment($(this).text()).format('YYYY-MM-DD hh:mm:ss'));"
               "  });"
               "  $('.since').each(function() {"
               "    $(this).text(moment($(this).text()).fromNow());"
               "  }); "
               "} "
               "friendlifyMoments();"
               ;; Function to use for refreshing the console:
               "var refreshInterval;"
               "function refreshConsole() { "
               "  var request = new XMLHttpRequest(); "
               "  request.onreadystatechange = function() { "
               "    if (request.readyState === 4) { "
               "      if (!request.status) { "
               "        console.error('Could not get console contents from endpoint'); "
               "        clearInterval(refreshInterval); "
               "      } else { "
               "        var consoleHtml = request.responseText; "
               "        var elems = $.parseHTML(consoleHtml); "
               "        if (elems.length === 0) { "
               "          console.error('No console contents returned from endpoint'); "
               "          clearInterval(refreshInterval); "
               "        } else { "
               "          $('#console').replaceWith(elems[0]); "
               "          friendlifyMoments(); "
               ;;         Scroll the the bottom of the page:
               "          window.scrollTo(0,document.body.scrollHeight); "
               ;;         If the "Unfollow console" span is not present, then the process is done
               ;;         and we can stop refreshing:
               "          var unfollowSpan = document.getElementById('unfollow-span'); "
               "          if (!unfollowSpan) { "
               "            clearInterval(refreshInterval); "
               "          } "
               "        } "
               "      } "
               "    } "
               "  }; "
               (format "  request.open('GET', '/%s/%s/console%s', true); "
                       project-name
                       branch-name
                       (str "?" (params-to-query-str params)))
               "  request.send(); "
               "} ")]
     [:script
      "$(document).ready(function() { "
      (if auto-refresh
        (format
         "refreshInterval = setInterval(refreshConsole, %s); " interval)
        " clearInterval(refreshInterval); ")
      "});"]])})

(defn render-4xx
  "Render a page displaying an error message"
  [request status-code error-msg]
  (html-response
   request
   {:title [:div (-> status-code
                     (str " " (-> status-code (status/get-name)))
                     (str " &mdash;"))
            [:a {:href "/"} "DROID"]]
    :content [:div#contents
              [:p error-msg]
              (let [referer (-> request :headers (get "referer"))]
                (when referer
                  [:p [:a {:href referer} "Back to previous page"]]))]
    :status status-code}))

(defn render-404
  "Render the 404 - not found page"
  [request]
  (render-4xx request 404 "The requested resource could not be found."))

(defn render-403
  "Render the 403 - forbidden page"
  [request]
  (render-4xx request 403 "Access to the requested resource is forbidden."))

(defn render-401
  "Render the 401 - unauthorized page"
  [request]
  (render-4xx request 401 "You are not authorized to view this page."))

(defn- render-close-tab
  "Renders a page with no links or any other content besides a message indicating that the user can
  close the tab. The page also includes one line of javascript code to automatically close the tab
  if the user has javascript enabled."
  [request]
  (html-response
   request
   {:title "DROID"
    :heading [:div "DROID"]
    :script "window.close();"
    :content [:div {:class "alert alert-info"} "You may now close this tab"]}))

(defn render-github-webhook-response
  "Render a response to a GitHub event hitting this endpoint. Currently just a stub"
  [request]
  "Not implemented. Click <a href=\"/\">here</a> to return to DROID.")

(defn- branch-status-summary
  "Given the names of a project and branch, output a summary string of the branch's commit status
  as compared with its remote."
  [project-name branch-name]
  (let [branch-agent (-> @branches/local-branches
                         (get (keyword project-name))
                         (get (keyword branch-name)))

        [org repo] (-> :projects (get-config) (get project-name) :github-coordinates
                       (string/split #"/"))

        render-remote (fn [remote]
                        [:a {:href (-> remote
                                       (string/split #"/")
                                       (last)
                                       (#(str "https://github.com/" org "/" repo "/tree/" %)))
                             :target "__blank"}
                         remote])]

    (send-off branch-agent branches/refresh-local-branch)
    (await branch-agent)
    (let [git-file (-> (get-workspace-dir project-name branch-name)
                       (str "/.git/FETCH_HEAD") (io/file))]
      (cond
        (-> @branch-agent :git-status :remote (not))
        [:span {:class "text-muted"} "No remote found"]

        (not (.exists git-file))
        [:span {:class "text-muted"} "Not yet fetched"]

        :else
        [:span
         (str (-> @branch-agent :git-status :ahead) " ahead, "
              (-> @branch-agent :git-status :behind) " behind ")
         (-> @branch-agent :git-status :remote (render-remote))
         (when (-> @branch-agent :git-status :uncommitted?)
           ", with uncommitted changes")
         " (" [:span {:class "since"} (-> git-file (.lastModified)
                                          (java.time.Instant/ofEpochMilli)
                                          (str))] ")"]))))

(defn- render-project-branches
  "Given the name of a project, render a list of its branches, with links. If restricted-access? is
  set to true, do not display any links to actions that would result in changes to the workspace.
  If site-admin-access? is set to true, show the parts of the page that relate to administration.
  Note that restricted-access?, if true, overrides site-admin-access?"
  [project-name restricted-access? site-admin-access?]
  (let [;; Override site-admin-access? if restricted-access? is set to true:
        site-admin-access? (and (not restricted-access?) site-admin-access?)

        local-branch-names (->> project-name
                                (keyword)
                                (get @branches/local-branches)
                                (keys)
                                (map name))

        [org repo] (-> :projects (get-config) (get project-name) :github-coordinates
                       (string/split #"/"))

        project-url (str "/" project-name)

        remote-branches (->> @branches/remote-branches
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

        container-list (if-not site-admin-access?
                         ;; If the user is not a site admin return an empty map:
                         {}
                         ;; Otherwise run docker ps -f to get all the containers for the project:
                         (let [[process
                                exit-code] (cmd/run-command
                                            ["docker" "ps" "-f" (str "name=" project-name "-*")
                                             "--format" "{{.Names}},{{.Status}}"])]
                           (if-not (= @exit-code 0)
                             ;; If there was an error, log it and return an empty map:
                             (do (log/error "Error getting container status:"
                                            (sh/stream-to-string process :err))
                                 {})
                             ;; Otherwise return a hash-map mapping the container name to its status:
                             (->> (sh/stream-to-string process :out)
                                  (string/split-lines)
                                  (map #(string/split % #","))
                                  (map #(hash-map (first %) (second %)))
                                  (apply merge)))))

        render-container-indicator (fn [branch-name]
                                     (let [status (->> (str project-name "-" branch-name)
                                                       (get container-list)
                                                       (#(if % % "not found"))
                                                       (string/lower-case))
                                           docker-disabled? (-> (get-docker-config project-name)
                                                                :disabled?)]
                                       [:span
                                        (when-not docker-disabled?
                                          [:a {:class "badge badge-warning"
                                               :href (str project-url
                                                          "?rebuild-single-container="
                                                          ;; Set the branch name in the param:
                                                          branch-name)}
                                           "Rebuild"])
                                        [:span
                                         (cond (string/includes? status "(paused)")
                                               {:class "text-primary"}

                                               (string/starts-with? status "up")
                                               {:class "text-success"}

                                               (= status "not found")
                                               {:class "text-danger font-weight-bold"})
                                         (when-not docker-disabled? "&ensp;")
                                         status]]))

        render-pr-link #(let [pr-url (:pull-request %)]
                          (when-not (nil? pr-url)
                            [:a {:href pr-url :target "__blank"}
                             "#" (-> pr-url (string/split #"/") (last))]))

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
                                     [:td (branch-status-summary project-name branch-name)]
                                     (when site-admin-access?
                                       [:td (render-container-indicator branch-name)])]))

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
                                                                "/tree/"))
                                                :target "__blank"}
                                            (:name remote-branch)]]
                                      [:td (render-pr-link remote-branch)]
                                      [:td]
                                      ;; The last column is for container-status (site admins only):
                                      (when site-admin-access? [:td])]))]

    [:table {:class "table table-sm table-striped table-borderless mt-3"}
     [:thead
      [:tr (when-not restricted-access? [:th]) [:th "Branch"] [:th "Open PR"]
       [:th "Git status"]
       ;; Site admins can see container status:
       (when site-admin-access?
         [:th (str "Containers " (when (-> project-name (get-docker-config) :disabled?)
                                   " (disabled)"))])]]
     ;; Render the local main (or master) branch first if it is present:
     (->> local-branch-names
          (filter #(or (= % "master") (= % "main")))
          (first)
          (render-local-branch-row))
     ;; Render all of the other local branches:
     (for [local-branch-name (->> local-branch-names
                                  (filter #(and (not= % "master") (not= % "main")))
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

     ;; Render the remote master/main branch if it is present and there is no local copy:
     (when (not-any? #(or (= % "master") (= % "main")) local-branch-names)
       (->> remote-branches
            (filter #(or (= (:name %) "master") (= (:name %) "main")))
            (first)
            (render-remote-branch-row)))
     ;; Render all of the other remote branches that don't have local copies:
     (for [remote-branch remote-branches]
       (when (and (not= (:name remote-branch) "master")
                  (not= (:name remote-branch) "main")
                  (not-any? #(= (:name remote-branch) %) local-branch-names))
         (render-remote-branch-row remote-branch)))]))

(defn- run-cgi
  "Run the possibly CGI-aware script located at the given path and render its response"
  [script-path path-info
   {:keys [request-method headers params form-params remote-addr server-name server-port
           body-bytes]
    {:keys [project-name branch-name]} :params,
    {{:keys [login]} :user} :session,
    :as request}]
  (if (read-only? request)
    (render-401 request)
    (let [branch-key (keyword branch-name)
          project-key (keyword project-name)
          branch-url (str "/" project-name "/branches/" branch-name)
          dirname (-> script-path (io/file) (.getParent))
          basename (-> script-path (io/file) (.getName))
          query-string (-> params (params-to-query-str))
          cgi-input-env (-> {"AUTH_TYPE" ""
                             "CONTENT_LENGTH" ""
                             "CONTENT_TYPE" ""
                             "GATEWAY_INTERFACE" "CGI/1.1"
                             "PATH_INFO" path-info
                             "PATH_TRANSLATED" ""
                             "QUERY_STRING" ""
                             "REMOTE_ADDR" remote-addr
                             "REMOTE_HOST" remote-addr
                             "REMOTE_IDENT" ""
                             "REMOTE_USER" login
                             "SCRIPT_NAME" (str "/" script-path)
                             "SERVER_NAME" server-name
                             "SERVER_PORT" (str server-port)
                             "SERVER_PROTOCOL" "HTTP/1.1"
                             "SERVER_SOFTWARE" "DROID/1.0"
                             "HTTP_ACCEPT" (-> headers (get "accept" "") (string/split #",\s*")
                                               (set)
                                               (#(if (some (fn [header] (= header "text/html")) %)
                                                   (conj % "text/html-fragment")
                                                   %))
                                               (#(string/join "," %)))
                             "HTTP_ACCEPT_ENCODING" (get headers "accept-encoding" "")
                             "HTTP_ACCEPT_LANGUAGE" (get headers "accept-language" "")
                             "HTTP_USER_AGENT" (get headers "user-agent" "")}
                            (#(cond
                                (= request-method :get)
                                (merge % {"REQUEST_METHOD" "GET"
                                          "QUERY_STRING" query-string})

                                (= request-method :post)
                                (merge % {"REQUEST_METHOD" "POST"
                                          "CONTENT_LENGTH" (-> body-bytes (alength) (str))
                                          "CONTENT_TYPE" (get headers "content-type" "")})

                                :else
                                (log/error "Unsupported request method:" request-method))))
          ;; We will send input and output from/to CGI scripts via temporary files:
          tmp-infile (str (get-temp-dir project-name branch-name)
                          "/" basename ".in." (System/currentTimeMillis))
          tmp-outfile (str (get-temp-dir project-name branch-name)
                           "/" basename ".out." (System/currentTimeMillis))
          ;; Note: To test a script on the command line, do:
          ;; export REQUEST_METHOD="POST"; \
          ;;   export CONTENT_TYPE="application/x-www-form-urlencoded"; \
          ;;   export CONTENT_LENGTH=16;echo "cgi-input-py=bar" | build/hobbit-script.py
          timeout (or (get-config :cgi-timeout) 10000)
          [process
           exit-code] (if (= request-method :post)
                        (do (with-open [w (io/output-stream tmp-infile)]
                              (.write w body-bytes))
                            (branches/run-branch-command
                             ["sh" "-c"
                              (str "./" basename " < " tmp-infile " > " tmp-outfile)
                              :dir dirname :env cgi-input-env]
                             project-name branch-name timeout))
                        (do
                          (branches/run-branch-command
                           ["sh" "-c" (str "./" basename " > " tmp-outfile)
                            :dir dirname :env cgi-input-env]
                           project-name branch-name timeout)))
          ;; We expect a blank line separating the response's header and body:
          split-response #(->> % (string/split-lines) (partition-by string/blank?))]

      (log/info "Running CGI script" script-path "for at most" timeout "milliseconds")
      (cond
        (= :timeout @exit-code)
        (let [error-msg (str "Timed out after " timeout "s waiting for CGI script: " script-path)]
          (log/error error-msg)
          (render-4xx request 408 error-msg))

        (not= 0 @exit-code)
        (let [error-output (sh/stream-to-string process :err)
              error-msg (format "CGI script '%s' returned non-zero exit-code: %d. %s"
                                script-path @exit-code (when error-output error-output))]
          (log/error error-msg)
          (render-4xx request 400 error-msg))

        :else
        (let [response-sections (-> (slurp tmp-outfile) (split-response))
              ;; Every line in the header must be of the form: <something>: <something else>
              ;; and one of the headers must be for Content-Type
              valid-header? (and (->> (first response-sections)
                                      (some #(re-matches #"^\s*Content-Type:(\s+\S+)+\s*$" %)))
                                 (->> (first response-sections)
                                      (every? #(re-matches #"^\s*\S+:(\s+\S+)+\s*$" %))))
              headers (if-not valid-header?
                        ;; If the raw header isn't valid just use the default headers defined above:
                        default-html-headers
                        ;; Otherwise, construct a map out of the header lines that looks like:
                        ;; {"header1" "header1-value", "header2", "header2-value", ...}
                        (->> (first response-sections)
                             (map string/trim)
                             (map #(string/split % #":\s+"))
                             (map #(apply hash-map %))
                             (apply merge)))]

          ;; Remove the temporary files:
          (io/delete-file tmp-infile true)
          (io/delete-file tmp-outfile true)

          (if valid-header?
            ;; When the response has a valid header, the first two sections of the response will
            ;; correspond to (1) the headers, and (2) a blank line separating the headers from the
            ;; output proper, contained in the remaining sections of the response. In this case the
            ;; output is parsed into hiccup and embedded into a div:
            (let [body (->> response-sections (drop 2) (apply concat) (vec) (string/join "\n"))]
              (if (= "text/html-fragment" (headers "Content-Type"))
                ;; If this is a HTML fragment, wrap it in DROID's fancy headers:
                (->> body (hickory/parse-fragment) (map hickory/as-hiccup) (into [:div])
                     (hash-map :headers (assoc headers "Content-Type" "text/html")
                               :content)
                     (html-response request))
                ;; Otherwise return it as is:
                {:status (let [status (headers "Status")]
                           (if-not status
                             (log/info "CGI script returned no status. Assuming 200.")
                             (log/info "CGI script returned status:" status))
                           (try
                             (-> (or status "200") (string/split #"\s+") (first) (Integer/parseInt))
                             (catch java.lang.NumberFormatException e
                               (log/warn "Unable to parse integer status from:" status
                                         "- assuming 200.")
                               200)))
                 :headers headers
                 :session (:session request)
                 :body body}))
            ;; If the response does not have a valid header, then all of it is treated as valid
            ;; output. In this case we write this output to the console, modify the values for
            ;; `command` and `action` in the branch, and then redirect back to the branch page:
            (let [console-path (-> (get-temp-dir project-name branch-name) (str "/console.txt"))]
              (->> response-sections
                   (apply concat)
                   (vec)
                   (string/join "\n")
                   (spit console-path))
              (-> @branches/local-branches
                  (get (keyword project-name))
                  (get (keyword branch-name))
                  (send #(assoc % :command basename :action basename)))
              (redirect branch-url))))))))

(defn just-logged-in
  "After logging into DROID a user is directed here, which in turn redirects the user either to the
  page she was previously on, or if that is unknown, to the index page."
  [{:keys [headers] :as request}]
  (let [{host "host", referer "referer"} headers
        pattern (re-pattern (str "https?://" host "/(.+)"))
        rel-url (-> (and referer host (re-matches pattern referer))
                    (second)
                    (#(when % (string/replace % #"\?.+$" "")))
                    (not-empty)
                    (or "/"))]
    (redirect (or rel-url "/"))))

(defn- render-rebuild-containers-dialog
  "Render a dialog to ask the user if she would like to rebuild containers. Use the given base-url
  for forward links"
  [base-url]
  [:div {:class "alert alert-warning"}
   "Please confirm that you want to rebuild all containers. Note that if newer versions of the "
   "images they depend on exist, those new image versions will be pulled/rebuilt before rebuilding "
   "containers."
   [:div {:class "pt-2"}
    [:a {:class "btn btn-sm btn-primary" :href base-url} "Cancel"]
    [:span "&nbsp;"]
    [:a {:class "btn btn-sm btn-warning" :href (str base-url "?really-rebuild-containers=1")}
     "Confirm"]]])

(defn render-index
  "Render the index page"
  [{:keys [params server-name server-port]
    {:keys [just-logged-out rebuild-containers really-rebuild-containers rebuild-launched
            reset really-reset url url-redirect-err]} :params,
    {{:keys [login]} :user} :session,
    :as request}]
  (letfn [(process-url-redirect [redirect-from]
            ;; Given a URL to redirect from, map it to a page on the DROID server, possibly
            ;; directing DROID to create or checkout a branch in the process.
            (let [{:keys [scheme host path]} (uri/parse redirect-from)
                  path-parts (and path (-> path (string/replace #"(^/|/$)" "") (string/split #"/")))
                  [redirect-key redirect-val] (-> (take-last 2 path-parts) (vec))
                  [owner repo] (-> (take 2 path-parts) (vec))
                  github-coords (str owner "/" repo)
                  project-name (->> (get-config :projects)
                                    (seq)
                                    (filter #(= (-> % (second) :github-coordinates)
                                                github-coords))
                                    (#(do
                                        (when (> (count %) 1)
                                          (log/warn "There is more than one project with the github"
                                                    "coordinates:" (str github-coords "; choosing")
                                                    "project:" (-> % (first) (first))))
                                        (-> % (first) (first)))))
                  ;; The initially projected path replaces the owner/repo component of the path with
                  ;; the project name. Further replacements may need to be made depending on
                  ;; redirect-key and redirect-val. If no project name can be determined, then
                  ;; projected-path is left as nil.
                  projected-path (when project-name
                                   (-> path (string/replace-first (re-pattern github-coords)
                                                                  project-name)))
                  usage (str "Valid redirection URLs must be in one of the following forms:"
                             "<ul>"
                             " <li>https://github.com/ORG/REPOSITORY</li>"
                             " <li> https://github.com/ORG/REPOSITORY/tree/BRANCH</li>"
                             " <li> https://github.com/ORG/REPOSITORY/issues/ISSUE_NUMBER</li>"
                             " <li> https://github.com/ORG/REPOSITORY/pull/PR_NUMBER</li>"
                             "</ul>")
                  ;; For some of the actions we need to take, it will be necessary to first refresh
                  ;; local and/or remote branches since we can't assume they're up to date
                  ;; (the refresh decorators don't apply to the index page), so we define these
                  ;; handy functions:
                  refresh-locals #(do (send-off branches/local-branches
                                                branches/refresh-local-branches [project-name])
                                      (await branches/local-branches))
                  refresh-remotes #(do (send-off branches/remote-branches
                                                 branches/refresh-remote-branches-for-project
                                                 project-name request)
                                       (await branches/remote-branches))
                  refresh-all #(do (send-off branches/local-branches
                                             branches/refresh-local-branches [project-name])
                                   (send-off branches/remote-branches
                                             branches/refresh-remote-branches-for-project
                                             project-name request)
                                   (await branches/local-branches branches/remote-branches))]

              (log/debug "In process-url-redirect. Redirecting from" redirect-from)
              (cond
                (or (not= scheme "https") (not= host "github.com"))
                (->> usage
                     (str "Cannot redirect from: " redirect-from
                          ". Only paths beginning in https://github.com may be redirected. ")
                     (codec/url-encode)
                     (str "/?url-redirect-err="))

                (nil? project-name)
                (->> (str "Unable to determine project name from: " redirect-from ". Have the "
                          "GitHub owner and repository been specified correctly?")
                     (codec/url-encode)
                     (str "/?url-redirect-err="))

                (= redirect-key "tree")
                (do (refresh-locals)
                    (let [branch-name redirect-val]
                      (cond
                        ;; If a branch is already checked out, redirect to it:
                        (branches/local-branch-exists? project-name branch-name)
                        (-> projected-path
                            (string/replace-first #"/tree/" "/branches/")
                            (string/replace #"/$" ""))

                        ;; If there is a remote branch with that name, check it out:
                        (branches/remote-branch-exists? project-name branch-name)
                        (str "/" project-name "?to-checkout=" branch-name)

                        ;; Else suggest that the user create a new branch:
                        :else
                        (str "/" project-name "?create=1&suggested-name=" branch-name))))

                (= redirect-key "issues")
                (do (refresh-all)
                    (let [branch-name (str "fix-" redirect-val)]
                      (cond
                        ;; If branch is already checked out, redirect to it:
                        (branches/local-branch-exists? project-name branch-name)
                        (str "/" project-name "/branches/" branch-name)

                        ;; If there is a remote branch with that name, check it out:
                        (branches/remote-branch-exists? project-name branch-name)
                        (str "/" project-name "?to-checkout=" branch-name)

                        ;; Else suggest that the user create a new branch:
                        :else
                        (str "/" project-name "?create=1&suggested-name="
                             branch-name))))

                (= redirect-key "pull")
                (do (refresh-all)
                    (let [branch-name (->> (keyword project-name)
                                           (get @branches/remote-branches)
                                           ;; Get the name of the branch whose pull request matches
                                           ;; the incoming URL:
                                           (filter #(= redirect-from (:pull-request %)))
                                           (first)
                                           :name)]
                      (if branch-name
                        (if (branches/local-branch-exists? project-name branch-name)
                          ;; If the branch is already checked out, redirect to it:
                          (str "/" project-name "/branches/" branch-name)
                          ;; Otherwise, check it out:
                          (str "/" project-name "?to-checkout=" branch-name))
                        (->> (str "Cannot redirect from " redirect-from
                                  ". No branch corresponding to pull request #"
                                  redirect-val " exists.")
                             (codec/url-encode)
                             (str "/?url-redirect-err=")))))

                ;; If there is nothing after the repo (i.e., https://github.com/ORG/REPO), just
                ;; redirect to the corresponding project page:
                (= [owner repo] [redirect-key redirect-val])
                (let [redirect-to (-> projected-path (string/replace #"/$" ""))]
                  (log/debug "In process-url-redirect. Redirecting from" redirect-from "to" redirect-to)
                  redirect-to)

                ;; Miscellaneous error case:
                :else
                (->> usage
                     (str "Invalid redirection URL: " redirect-from ". ")
                     (codec/url-encode)
                     (str "/?url-redirect-err=")))))]
    (log/debug "Processing request in index with params:" params)
    (cond
      ;; If the user is a site-admin and has confirmed a reset action, do it now and redirect back
      ;; to this page:
      (and (site-admin? request) (not (nil? really-reset)))
      (do
        (log/info "Site administrator" login "requested a global reset of branch data")
        ;; We refresh the local branches before resetting to make sure that the metadata has been
        ;; persisted to the database:
        (log/debug "Refreshing local branches before reset")
        (send-off branches/local-branches
                  branches/refresh-local-branches (-> :projects (get-config) (keys)))
        (send-off branches/local-branches branches/reset-all-local-branches)
        (await branches/local-branches)
        (redirect "/"))

      (and (site-admin? request) (not (nil? really-rebuild-containers)))
      (do
        (doseq [project-name (->> :projects (get-config) (keys) (map name))]
          ;; We send the rebuild jobs through container-serializer so that they will run in the
          ;; background one after another:
          (send-off branches/container-serializer
                    branches/rebuild-images-and-containers project-name))
        (redirect "/?rebuild-launched=1"))

      (not (nil? url))
      (-> (process-url-redirect url) (redirect))

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
                   (when-not (nil? url-redirect-err)
                     [:div {:class "alert alert-danger"}
                      url-redirect-err])
                   [:div
                    [:h3 "Available Projects"]
                    [:table
                     {:class "table table-borderless table-sm"}
                     (for [project (get-config :projects)]
                       [:tr
                        [:td
                         [:a
                          {:style "font-weight: bold"
                           :href (->> project (key) (str "/"))}
                          (->> project (val) :project-title)]]
                        [:td (->> project (val) :project-description)]
                        [:td
                         [:a
                          {:href (str "https://github.com/"
                                      (-> project val :github-coordinates))}
                          (-> project val :github-coordinates)]]])]
                    [:div {:class "mb-3 mt-3"}
                     [:h3 "GitHub Redirection"]
                     [:form {:action "/" :method "get"}
                      [:div {:class "form-group row"}
                       [:div [:label {:for "url" :class "m-1 ml-3 mr-3"} "Enter a URL:"]]
                       [:div [:input {:id "url" :name "url" :type "text" :maxlength "200"
                                      :onClick "this.select();"}]]
                       [:button {:type "submit" :class "btn btn-sm btn-primary ml-2 mr-2"}
                        "Submit"]]]]

                    (when (site-admin? request)
                      [:div
                       [:h3 "Administration"]
                       [:div {:class "row pb-3 pt-2 pl-3"}
                        [:a {:class "btn btn-sm btn-danger mr-2" :href "/?reset=1"
                             :data-toggle "tooltip"
                             :title "Clear branch data for all managed projects"}
                         "Reset branch data"]
                        [:a {:class "btn btn-sm btn-warning" :href "/?rebuild-containers=1"
                             :data-toggle "tooltip"
                             :title "(Re)build all containers for all managed projects"}
                         "Rebuild all containers"]]
                       (when (not (nil? reset))
                         [:div {:class "alert alert-danger"}
                          "Are you sure you want to reset branch data for all projects? Note that "
                          "doing so will kill any running processes and clear all console data."
                          [:div {:class "pt-2"}
                           [:a {:class "btn btn-sm btn-primary" :href "/"} "No, cancel"]
                           [:span "&nbsp;"]
                           [:a {:class "btn btn-sm btn-danger" :href "/?really-reset=1"}
                            "Yes, continue"]]])
                       (when (not (nil? rebuild-containers))
                         (render-rebuild-containers-dialog "/"))
                       (when (not (nil? rebuild-launched))
                         [:div {:class "alert alert-success"}
                          "Container(s) are being rebuilt. This could take a few minutes "
                          "to complete."
                          [:div [:a {:class "btn btn-sm btn-primary mt-2" :href "/"}
                                 "Dismiss"]]])])]]]}))))

(defn render-project
  "Render the home page for a project"
  [{:keys [params session]
    {:keys [project-name refresh to-delete to-really-delete delete-remote make-clean
            to-checkout create suggested-name invalid-name-error to-create branch-from
            rebuild-single-container really-rebuild-single-container
            rebuild-containers really-rebuild-containers rebuild-launched]} :params,
    {{:keys [login]} :user} :session,
    :as request}]
  (log/debug "Processing request in render-project with params:" params)
  (let [this-url (str "/" project-name)
        project (-> :projects (get-config) (get project-name))]
    (letfn [(refname-valid? [refname]
              ;; Uses git's command line interface to determine whether the given refname is legal.
              (let [[process exit-code] (cmd/run-command
                                         ["git" "check-ref-format" "--allow-onelevel" refname])]
                (or (= @exit-code 0) false)))

            (suggest-next-branch []
              ;; Suggests a name for a new branch based on the remote branches that currently exist:
              (let [pattern (-> (str "^" login "-patch-(\\d+)$") (re-pattern))
                    prev-patches (->> project-name (keyword) (get @branches/remote-branches)
                                      (map #(:name %)) (filter #(re-matches pattern %))
                                      (map #(string/replace % pattern "$1"))
                                      (map #(Integer/parseInt %)))]
                (if (empty? prev-patches)
                  (str login "-patch-1")
                  (->> prev-patches (apply max) (+ 1) (str login "-patch-")))))

            (create-branch [project-name branch-name]
              ;; Creates a new branch with the given name in the given project's workspace

              ;; Begin by refreshing local and remote branches since we must reference them below:
              (send-off branches/local-branches branches/refresh-local-branches [project-name])
              (send-off branches/remote-branches
                        branches/refresh-remote-branches-for-project project-name request)
              (await branches/local-branches branches/remote-branches)

              (cond
                (not (refname-valid? branch-name))
                (-> this-url
                    (str "?create=1&invalid-name-error=")
                    (str (-> "Invalid name: "
                             (str "&quot;" branch-name "&quot;")
                             (codec/url-encode)))
                    (redirect))

                (or (branches/remote-branch-exists? project-name branch-name)
                    (branches/local-branch-exists? project-name branch-name))
                (-> this-url
                    (str "?create=1&invalid-name-error=")
                    (str (-> "Already exists: "
                             (str "&quot;" branch-name "&quot;")
                             (codec/url-encode)))
                    (redirect))

                :else
                (do
                  ;; Create the branch, then refresh the local branch collection so that it shows up
                  ;; on the page, and also refresh the remote branches since the new local branch
                  ;; will have been pushed to the remote upon creation:
                  (log/info "Creation of a new branch:" branch-name "in project" project-name
                            "initiated by" login)
                  (send-off branches/local-branches branches/create-local-branch project-name
                            branch-name branch-from request)
                  (await branches/local-branches)
                  (send-off branches/remote-branches
                            branches/refresh-remote-branches-for-project project-name request)
                  (await branches/remote-branches)
                  (redirect (-> this-url (str "/branches/" branch-name))))))]

      ;; Perform an action based on the parameters present in the request:
      (cond
        ;; No project found with the given name:
        (nil? project)
        (render-404 request)

        ;; Delete a local branch:
        (and (not (nil? to-really-delete))
             (not (read-only? request)))
        (do
          (log/info "Deletion of branch" to-really-delete "from" project-name "initiated by" login)
          (send-off branches/local-branches branches/delete-local-branch project-name
                    to-really-delete make-clean)
          (when delete-remote
            (send-off branches/remote-branches
                      branches/refresh-remote-branches-for-project project-name request)
            (send-off branches/remote-branches branches/delete-remote-branch project-name
                      to-really-delete request)
            (await branches/remote-branches))
          (await branches/local-branches)
          (redirect this-url))

        ;; Checkout a remote branch into the local project workspace:
        (and (not (nil? to-checkout))
             (not (read-only? request)))
        (do
          (log/info "Checkout of remote branch" to-checkout "into workspace for" project-name
                    "initiated by" login)
          (send-off branches/local-branches
                    branches/checkout-remote-branch-to-local project-name to-checkout)
          (await branches/local-branches)
          (redirect (-> this-url (str "/branches/" to-checkout))))

        ;; Create a new branch:
        (and (not (nil? to-create))
             (not (read-only? request)))
        (create-branch project-name to-create)

        ;; Rebuild the project's containers:
        (and (site-admin? request) (not (nil? really-rebuild-containers)))
        (do
          ;; We send the rebuild job through container-serializer so that it will run in the
          ;; background:
          (send-off branches/container-serializer
                    branches/rebuild-images-and-containers project-name)
          (redirect (str this-url "?rebuild-launched=1")))

        ;; Rebuild a specific container:
        (and (site-admin? request) (not (nil? really-rebuild-single-container)))
        (let [;; The name of the branch is stored in the really-rebuild-single-container param:
              branch-name really-rebuild-single-container
              branch-agent (-> @branches/local-branches (get (keyword project-name))
                               (get (keyword branch-name)))]
          (send-off branch-agent branches/rebuild-branch-container)
          (redirect (str this-url "?rebuild-launched=1")))

        ;; Refresh local and remote branches:
        (not (nil? refresh))
        (do
          (send-off branches/local-branches branches/refresh-local-branches [project-name])
          (send-off branches/remote-branches
                    branches/refresh-remote-branches-for-project project-name request)
          (await branches/local-branches branches/remote-branches)
          (redirect this-url))

        ;; Otherwise just render the page
        :else
        (html-response
         request
         {:title (->> project :project-title (str "DROID for "))
          :heading [:div
                    [:a {:href "/"} "DROID"]
                    " / "
                    (-> project :project-title)]
          :content [:div
                    (let [gh-url (->> project :github-coordinates (str "github.com/"))]
                      [:p [:a {:href (str "https://" gh-url) :target "__blank"} gh-url]])
                    [:p (->> project :project-description)]

                    ;; Display this alert and question when to-delete parameter is present:
                    (when (and (not (nil? to-delete))
                               (not (read-only? request)))
                      [:div {:class "alert alert-danger"}
                       [:form {:action this-url :method "get"}
                        [:span {:class "ml-1"} "Are you sure you want to delete the branch "]
                        [:span {:class "text-monospace font-weight-bold"} to-delete] "?"
                        [:div {:class "pt-2 ml-1"}
                         [:a {:class "btn btn-sm btn-primary" :href this-url} "No, cancel"]
                         [:span "&nbsp;"]
                         [:input {:type "hidden" :id "to-really-delete" :name "to-really-delete"
                                  :value to-delete}]
                         [:button {:class "btn btn-sm btn-danger" :type "submit"}
                          "Yes, continue"]]
                        [:div {:class "row ml-1"}
                         [:div {:class "form-check pt-2 mr-3"}
                          [:input {:class "form-check-input" :type "checkbox" :value "1"
                                   :id "delete-remote" :name "delete-remote"}]
                          [:label {:class "form-check-label" :for "delete-remote"}
                           "Also delete remote branch"]]
                         ;; If the Makefile has a 'clean' target, give the user the option to run
                         ;; `make clean` after deleting the branch:
                         (when (->> (keyword project-name) (get @branches/local-branches)
                                    (#(get % (keyword to-delete))) (deref) :Makefile :targets
                                    (some #(= % "clean")))
                           [:div {:class "form-check pt-2"}
                            [:input {:class "form-check-input" :type "checkbox" :value "1"
                                     :id "make-clean" :name "make-clean"}]
                            [:label {:class "form-check-label" :for "make-clean"}
                             "Run `make clean` before deleting"]])]]])

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
                                                  (get @branches/remote-branches)
                                                  (map #(get % :name))
                                                  (sort))]
                             ;; The master branch is selected by default:
                             [:option
                              (merge {:value branch-name} (when (or (= branch-name "master")
                                                                    (= branch-name "main"))
                                                            {:selected "selected"}))
                              branch-name])]]
                         ;; Input box for inputting the desired new branch name:
                         [:div {:class "col-sm-3"}
                          [:div
                           [:label {:for "to-create" :class "mb-n1 text-secondary"}
                            "Branch name"]]
                          [:div
                           [:input {:id "to-create" :name "to-create" :type "text"
                                    :value (or suggested-name (suggest-next-branch))
                                    :onClick "this.select();"}]]
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

                    ;; Display this alert when the rebuild-containers parameter is present and the
                    ;; user is a site admin:
                    (when (and (site-admin? request) (not (nil? rebuild-containers)))
                      (render-rebuild-containers-dialog this-url))

                    ;; Display this alert when the rebuild-single-container parameter is present
                    ;; and the user is a site admin:
                    (when (and (site-admin? request) (not (nil? rebuild-single-container)))
                      [:div {:class "alert alert-warning"}
                       (str "Really rebuild container for " rebuild-single-container "? Note that "
                            "if a newer version of the image it depends on exists, it will be "
                            "pulled/rebuilt before rebuilding the container.")
                       [:div {:class "pt-2"}
                        [:a {:class "btn btn-sm btn-primary" :href this-url} "Cancel"]
                        [:span "&nbsp;"]
                        [:a {:class "btn btn-sm btn-warning"
                             :href (str this-url "?really-rebuild-single-container="
                                        ;; The branch name is in the rebuild-single-container param:
                                        rebuild-single-container)}
                         "Confirm"]]])

                    ;; Display this alert when a rebuild of container(s) has been launched:
                    (when (not (nil? rebuild-launched))
                      [:div {:class "alert alert-success"}
                       "Container(s) are being rebuilt. This could take a few minutes "
                       "to complete."
                       [:div [:a {:class "btn btn-sm btn-primary mt-2" :href this-url}
                              "Dismiss"]]])

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
                        "Create new"])
                     (when (and (site-admin? request)
                                (-> (get-docker-config project-name) :disabled? (not)))
                       [:a {:class "btn btn-sm btn-warning ml-2"
                            :href (str this-url "?rebuild-containers=1")
                            :data-toggle "tooltip"
                            :title "Rebuild all containers for this project"}
                        "Rebuild all containers"])]
                    (when (-> @branches/remote-branches (get (keyword project-name)) (count)
                              (>= gh/max-remotes))
                      [:div {:class "alert alert-warning"}
                       "Warning: Your GitHub repository includes " gh/max-remotes " or more "
                       "branches, which is more than DROID can display."])
                    (render-project-branches project-name
                                             (read-only? request)
                                             (site-admin? request))]})))))

(defn- render-status-bar-for-action
  "Given some branch data, render the status bar for the currently running process."
  [{:keys [branch-name project-name run-time exit-code process cancelled console] :as branch}
   {:keys [follow] :as params}]
  (let [branch-url (str "/" project-name "/branches/" branch-name)
        follow-span [:span {:id "follow-span" :class "col-sm-2"}
                     [:a {:class "btn btn-success btn-sm"
                          :href (str branch-url
                                     "?" (-> params (assoc :follow 1) (params-to-query-str)))}
                      "Follow console"]]
        unfollow-span [:span {:id "unfollow-span" :class "col-sm-2"}
                       [:a {:class "btn btn-success btn-sm"
                            :href (str branch-url
                                       "?" (-> params (dissoc :follow) (params-to-query-str)))}
                        "Unfollow console"]]]
    (cond
      ;; The last process was cancelled:
      cancelled
      [:p {:class "alert alert-warning"} "Process cancelled"]

      ;; No process is running (or has run):
      (nil? exit-code)
      (if-not (empty? console)
        ;; If there is console output display a warning, otherwise nothing:
        [:p {:class "alert alert-warning"}
         "Exit status of last command unknown. The server may have restarted before it could "
         "complete."]
        [:div])

      ;; A process is still running:
      (not (realized? exit-code))
      [:p {:class "alert alert-warning"}
       (str "Processes still running after "
            (-> run-time (/ 1000) (float) (Math/ceil) (int)) " seconds.")
       [:span {:class "col-sm-2"} [:a {:class "btn btn-success btn-sm"
                                       :href (str branch-url "?" (params-to-query-str params))}
                                   "Refresh"]]
       [:span {:class "col-sm-2"} [:a {:class (str "btn btn-danger btn-sm"
                                                   ;; Disable the cancel button if the user has
                                                   ;; read-only access:
                                                   (when (read-only? project-name branch-name)
                                                     " disabled"))
                                       :href (str branch-url "?new-action=cancel-DROID-process")}
                                   "Cancel"]]
       (if follow unfollow-span follow-span)]

      ;; The last process completed successfully:
      (= 0 @exit-code)
      [:p {:class "alert alert-success mr-3"} "Success"
       (when follow unfollow-span)]

      ;; The last process completed unsuccessfully:
      :else
      [:p {:class "alert alert-danger mr-3"} (str "ERROR: Exit code " @exit-code)
       (when (and process (realized? exit-code))
         ;; If there was a docker error (e.g., missing container), then it won't be written
         ;; to the console, but it will be present in the process's error stream. The
         ;; console contents themselves will be old, so we overwrite them with the error:
         (let [error-output (sh/stream-to-string process :err)]
           (str " " error-output)))
       (when follow unfollow-span)])))

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
  (-> (get-make-dir project-name branch-name)
      (str "/" view-path)
      (io/file)
      (.exists)))

(defn- prompt-to-kill-for-view
  "Given some branch data, and the path for a view that needs to be rebuilt, render an alert box
  asking the user to confirm that (s)he would like to kill the currently running process."
  [{:keys [branch-name project-name action] :as branch}
   {:keys [view-path path-info] :as params}]
  (let [branch-url (str "/" project-name "/branches/" branch-name)
        view-url (str "/" project-name "/branches/" branch-name "/views/" view-path)
        decoded-view-path (when view-path
                            (-> view-path
                                (string/replace #"PREV_DIR\/" "../")
                                ;; If the view is a call to a CGI script that has associated path
                                ;; information, do not show this in the dialog that is presented to
                                ;; the user:
                                (#(subs % 0 (- (count %) (count path-info))))))]
    [:div {:class "alert alert-warning"}
     [:p [:span {:class "text-monospace font-weight-bold"} action]
      " is still not complete. You must cancel it before updating or running "
      [:span {:class "text-monospace font-weight-bold"} decoded-view-path]
      ". Do you really want to do this?"]
     [:span [:a {:class "btn btn-secondary btn-sm" :href (str branch-url "?cancel-kill-for-view=1")}
             "No, do nothing"]]
     [:span {:class "col-sm-1"}]
     [:span [:a {:class "btn btn-danger btn-sm" :href (-> view-url
                                                          (str "?" (-> params
                                                                       (dissoc :confirm-kill)
                                                                       (assoc :force-kill 1)
                                                                       (params-to-query-str))))}
             "Yes, go ahead"]]
     ;; If the view exists and it is not executable, provide the option to view a stale version:
     (when (and (->> branch :Makefile :exec-views (not-any? #(= decoded-view-path %)))
                (view-exists? branch decoded-view-path))
       [:span
        [:span {:class "col-sm-1"}]
        [:span [:a {:class "btn btn-warning btn-sm" :href (str view-url "?force-old=1")}
                "View the stale version instead"]]])]))

(defn- prompt-to-update-view
  "Given some branch data, and the path for a view that needs to be rebuilt, render an alert box
  asking the user to confirm that (s)he would really like to rebuild the view."
  [{:keys [branch-name project-name action] :as branch}
   {:keys [view-path path-info] :as params}]
  (let [branch-url (str "/" project-name "/branches/" branch-name)
        view-url (str "/" project-name "/branches/" branch-name "/views/" view-path)
        decoded-view-path (when view-path
                            (-> view-path
                                (string/replace #"PREV_DIR\/" "../")
                                ;; If the view is a call to a CGI script that has associated path
                                ;; information, do not show this in the dialog that is presented to
                                ;; the user:
                                (#(subs % 0 (- (count %) (count path-info))))))]
    [:div
     [:div {:class "alert alert-warning"}
      [:p [:span {:class "font-weight-bold text-monospace"} decoded-view-path]
       " is not up to date. What would you like to do?"]

      [:span [:a {:class "btn btn-secondary btn-sm" :href (str branch-url "?cancel-update-view=1")}
              "Do nothing"]]
      [:span {:class "col-sm-1"}]
      [:span [:a {:class "btn btn-danger btn-sm"
                  :href (-> view-url
                            (str "?" (-> params
                                         (dissoc :confirm-update)
                                         (assoc :force-update 1)
                                         (params-to-query-str))))}
              "Rebuild the view"]]
      (when (view-exists? branch decoded-view-path)
        [:span
         [:span {:class "col-sm-1"}]
         [:span [:a (-> {:class "btn btn-warning btn-sm"}
                        (#(if (->> branch :Makefile :exec-views
                                   (not-any? (fn [v] (= decoded-view-path v))))
                            ;; If this isn't an executable view simply add the force-old flag to the
                            ;; query string:
                            (assoc % :href (str view-url "?force-old=1"))
                            ;; Otherwise we need to pass the original parameters through:
                            (assoc % :href (-> view-url
                                               (str "?" (-> params
                                                            (dissoc :confirm-update)
                                                            (assoc :force-old 1)
                                                            (params-to-query-str))))))))
                 "View the stale version"]]])]]))

(defn- notify-missing-view
  "Given some branch data, and the path for a view that is missing, render an alert box informing
  the user that it is not there."
  [{:keys [branch-name project-name] :as branch}
   view-path]
  (let [decoded-view-path (when view-path (string/replace view-path #"PREV_DIR\/" "../"))]
    [:div {:class "alert alert-warning"}
     [:div
      [:span
       [:span {:class "text-monospace font-weight-bold"} decoded-view-path]
       [:span " does not exist in branch "]
       [:span {:class "text-monospace font-weight-bold"} branch-name]
       (if (container-rebuilding? project-name branch-name)
         [:span ". Cannot build it while the branch's container is being rebuilt. Try again later."]
         [:span ". Ask someone with write access to this project to build it for you."])]]
     [:div {:class "pt-2"}
      [:a {:class "btn btn-sm btn-primary"
           :href (str "/" project-name "/branches/" branch-name "?close-tab=1")} "Dismiss"]]]))

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
              (render-status-bar-for-action branch params)))

          (render-console-text []
            ;; Look for ANSI escape sequences in the updated console text. (see:
            ;; https://en.wikipedia.org/wiki/ANSI_escape_code#Escape_sequences). If any are found,
            ;; then launch the python program `aha` in the shell to convert them all to HTML.
            ;; Either way wrap the console text in a pre-formatted block and return it.
            (let [pwd (get-temp-dir project-name branch-name)
                  console-updated? (when console
                                     ;; Note that .lastModified returns 0 if a file doesn't exist.
                                     (>= (-> pwd (str "/console.txt") (io/file) (.lastModified))
                                         (-> pwd (str "/console.html") (io/file) (.lastModified))))
                  escape-index (when console-updated? (->> 0x1B (char) (string/index-of console)))
                  ansi-commands? (when console-updated?
                                   (and escape-index (->> escape-index (inc) (nth console) (int)
                                                          (#(and (>= % 0x40) (<= % 0x5F))))))
                  render-pre-block (fn [arg]
                                     [:pre {:class "bg-light p-2"} [:samp {:class "shell"} arg]])]
              (cond
                ;; If the console has been updated since the last time we generated a colourised
                ;; HTML file, and if it contains ANSI, then colourise it if "aha" is installed:
                (and console-updated? ansi-commands? (cmd/program-exists? "aha"))
                (let [[process
                       a2h-exit-code] (do (log/debug "Colourizing console using aha ...")
                                          (cmd/run-command
                                           ["sh" "-c"
                                            (str "aha --no-header < console.txt > console.html")
                                            :dir pwd]))
                      colourized-console (if (not= @a2h-exit-code 0)
                                           (do (log/error "Unable to colourize console.")
                                               console)
                                           (slurp (str pwd "/console.html")))]
                  (render-pre-block colourized-console))

                ;; Otherwise if a colourised version of the console already exists, and it is not
                ;; older than the raw file, serve it:
                (and (not console-updated?) (-> pwd (str "/console.html") (io/file) (.exists)))
                (-> pwd (str "/console.html") (slurp) (render-pre-block))

                ;; Otherwise render the raw file, filtering out the ANSI commands:
                :else
                (-> console
                    (string/replace #"\u001b\[.*?m" "")
                    (string/replace "<" "&lt;")
                    (string/replace ">" "&gt;")
                    (render-pre-block)))))]

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

(defn refresh-console
  "Renders the HTML fragment (i.e., not a whole page) corresponding to the console portion of the
  branch page. Useful for automatic refreshing by javascript."
  [{:keys [params]
    {:keys [project-name branch-name]} :params,
    :as request}]
  (let [branch-url (str "/" project-name "/branches/" branch-name)
        branch-key (keyword branch-name)
        project-key (keyword project-name)
        branch-agent (->> @branches/local-branches project-key branch-key)]
    (send-off branch-agent branches/refresh-local-branch)
    (await branch-agent)
    (if (-> @branch-agent :exit-code (realized?))
      (html (render-console @branch-agent (dissoc params :follow)))
      (html (render-console @branch-agent (assoc params :follow "1"))))))

(defn- render-version-control-section
  "Render the Version Control section on the page for a branch"
  [{:keys [branch-name project-name] :as branch}
   {:keys [params]
    {:keys [confirm-push confirm-reset-hard get-commit-msg get-commit-amend-msg next-task
            pr-added]} :params
    :as request}]
  (let [get-last-commit-msg #(let [[process exit-code]
                                   (branches/run-branch-command
                                    ["git" "show" "-s" "--format=%s"
                                     :dir (get-workspace-dir project-name branch-name)]
                                    project-name branch-name)]
                               (if-not (= 0 @exit-code)
                                 (do (log/error "Unable to retrieve previous commit message:"
                                                (sh/stream-to-string process :err))
                                     nil)
                                 (sh/stream-to-string process :out)))

        get-commits-to-push #(let [[process exit-code]
                                   (branches/run-branch-command
                                    ["git" "log" "--branches" "--not" "--remotes"
                                     "--reverse" "--format=%s"
                                     :dir (get-workspace-dir project-name branch-name)]
                                    project-name branch-name)]
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
          [:div [:input {:id "commit-msg" :name "commit-msg" :type "text" :maxlength "500"}]]]
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
                         :maxlength "500" :onClick "this.select();" :value (get-last-commit-msg)}]]]
         [:button {:type "submit" :class "btn btn-sm btn-warning mr-2"} "Amend"]
         [:a {:class "btn btn-sm btn-secondary" :href this-url} "Cancel"]]]

       ;; If a reset --hard was requested, confirm that the user really wants to do it:
       (not (nil? confirm-reset-hard))
       [:div {:class "alert alert-danger m-1"}
        [:div {:class "font-weight-bold mb-2"}
         "Reset will delete all changes since the last commit. "
         "This cannot be undone. Are you sure?"]
        [:a {:href this-url :class "btn btn-sm btn-secondary"} "Cancel"]
        [:a {:href (str this-url "?new-action=git-reset-hard") :class "ml-2 btn btn-sm btn-danger"}
         "Reset branch"]]

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
       (let [current-pr
             (do
               ;; Refresh the remote branches to make sure their PR info is up-to-date before
               ;; retrieving the PR info for the branch:
               (send-off branches/remote-branches
                         branches/refresh-remote-branches-for-project project-name request)
               (await branches/remote-branches)
               (->> @branches/remote-branches
                    (#(get % (keyword project-name)))
                    (filter #(= branch-name (:name %)))
                    (first)
                    :pull-request))]
         (cond
           ;; If this is the master/main branch, then PRs aren't relevant:
           (or (= branch-name "master") (= branch-name "main"))
           [:div]

           ;; If the current branch doesn't already have a PR associated, provide the user with
           ;; the option to create one:
           (nil? current-pr)
           [:div {:class "alert alert-success m-1"}
            [:div {:class "mb-3"} "Commits pushed successfully."]
            [:form {:action this-url :method "get"}
             [:div {:class "form-group row"}
              [:div
               [:input {:id "pr-to-add" :name "pr-to-add" :type "text" :class "ml-3 mr-2"
                        :required true :onClick "this.select();" :value (get-last-commit-msg)}]]
              [:button {:type "submit" :class "btn btn-sm btn-primary mr-2"}
               "Create a pull request"]
              [:a {:class "btn btn-sm btn-secondary" :href this-url} "Dismiss"]]
             [:div {:class "form-check"}
              [:input {:class "form-check-input" :type "checkbox" :value "1" :id "draft-mode"
                       :name "draft-mode"}]
              [:label {:class "form-check-label" :for "draft-mode"} "Create as draft"]]]]

           ;; Otherwise display a link to the current PR:
           :else
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
           (send-off branches/remote-branches
                     branches/refresh-remote-branches-for-project project-name request)
           [:div {:class "alert alert-success"}
            [:div "PR created successfully. To view it click " [:a {:href pr-added
                                                                    :target "__blank"} "here."]]
            [:a {:href this-url :class "btn btn-sm btn-secondary mt-1"} "Dismiss"]])
         [:div {:class "alert alert-warning"}
          [:div "Your PR could not be created. Contact an administrator for assistance."]
          [:a {:href this-url :class "btn btn-sm btn-secondary mt-1"} "Dismiss"]]))

     ;; The git action buttons:
     [:table {:class "table table-borderless table-sm"}
      [:tr
       [:td [:a {:href (str this-url (-> gh/git-actions :git-status :html-param))
                 :class (-> gh/git-actions :git-status :html-class (str " btn-block"))}
             (-> gh/git-actions :git-status :html-btn-label)]]
       [:td "Show which files have changed since the last commit"]]
      [:tr
       [:td [:a {:href (str this-url (-> gh/git-actions :git-diff :html-param))
                 :class (-> gh/git-actions :git-diff :html-class (str " btn-block"))}
             (-> gh/git-actions :git-diff :html-btn-label)]]
       [:td "Show changes to tracked files since the last commit"]]
      [:tr
       [:td [:a {:href (str this-url (-> gh/git-actions :git-fetch :html-param))
                 :class (-> gh/git-actions :git-fetch :html-class (str " btn-block"))}
             (-> gh/git-actions :git-fetch :html-btn-label)]]
       [:td "Fetch the latest changes from GitHub"]]
      [:tr
       [:td [:a {:href (str this-url (-> gh/git-actions :git-pull :html-param))
                 :class (-> gh/git-actions :git-pull :html-class (str " btn-block"))}
             (-> gh/git-actions :git-pull :html-btn-label)]]
       [:td "Update this branch with the latest changes from GitHub"]]
      [:tr
       [:td [:a {:href (str this-url (-> gh/git-actions :git-commit :html-param))
                 :class (-> gh/git-actions :git-commit :html-class (str " btn-block"))}
             (-> gh/git-actions :git-commit :html-btn-label)]]
       [:td "Commit your changes locally"]]
      [:tr
       [:td [:a {:href (str this-url (-> gh/git-actions :git-amend :html-param))
                 :class (-> gh/git-actions :git-amend :html-class (str " btn-block"))}
             (-> gh/git-actions :git-amend :html-btn-label)]]
       [:td "Update your last commit with new changes"]]
      [:tr
       [:td [:a {:href (str this-url (-> gh/git-actions :git-push :html-param))
                 :class (-> gh/git-actions :git-push :html-class (str " btn-block"))}
             (-> gh/git-actions :git-push :html-btn-label)]]
       [:td "Push your latest local commit(s) to GitHub"]]
      [:tr
       [:td [:a {:href (str this-url (-> gh/git-actions :git-reset-hard :html-param))
                 :class (-> gh/git-actions :git-reset-hard :html-class (str " btn-block"))}
             (-> gh/git-actions :git-reset-hard :html-btn-label)]]
       [:td "Reset this branch to the last commit"]]]]))

(defn- render-branch-page
  "Given some branch data, and a number of parameters related to an action or a view, construct the
  page corresponding to a branch."
  [{:keys [branch-name project-name Makefile] :as branch}
   {:keys [params]
    {:keys [view-path missing-view confirm-kill confirm-update updating-view path-info
            process-killed follow commit-msg commit-amend-msg pr-to-add draft-mode]} :params
    :as request}]
  (let [this-url (str "/" project-name "/branches/" branch-name)]
    (cond
      ;; A brand new commit has been requested (and confirmed):
      (and (not (read-only? request))
           (not (nil? commit-msg))
           (not (string/blank? commit-msg)))
      (let [encoded-commit-msg (-> commit-msg (string/replace #"\"" "'") (codec/url-encode))]
        (log/info "User" (-> request :session :user :login) "adding local commit to branch"
                  branch-name "in project" project-name "with commit message:" commit-msg)
        (-> this-url
            (str "?new-action=git-commit&new-action-param=" encoded-commit-msg)
            (redirect)))

      ;; An amendment to the last commit has been requested (and confirmed):
      (and (not (read-only? request))
           (not (nil? commit-amend-msg))
           (not (string/blank? commit-amend-msg)))
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
           (gh/create-pr project-name
                         branch-name
                         (branches/get-remote-main project-name)
                         request
                         (boolean draft-mode))
           (codec/url-encode)
           (str this-url "?pr-added=")
           (redirect))

      ;; The follow flag is set, but no process is currently running. In this case redirect back
      ;; to the page with the flag unset. Note that this code will normally not need to be called
      ;; since the javascript should handle this, but this is kept as a failsafe:
      (and follow (-> branch :exit-code (realized?)))
      (-> this-url (str "?" (-> params (dissoc :follow :folval) (params-to-query-str))) (redirect))

      ;; A view has successfully finished updating. In this case redirect to the view.
      (and updating-view
           (-> branch :exit-code (realized?))
           (-> branch :exit-code (deref) (= 0)))
      ;; Note that we need to encode any instances of '../' into 'PREV_DIR/' to avoid the browser
      ;; interpolating these incorrectly:
      (->> branch :action (#(string/replace % #"\.\.\/" "PREV_DIR/"))
           ;; The `path-info` parameter may contain path information that will be needed if this
           ;; view is a call to a CGI script, so we append it to the URL that is redirected to, and
           ;; dissociate it, and the updating-view flag, from the parameter list:
           (#(str this-url "/views/" % path-info))
           (#(str % "?" (-> params (dissoc :path-info :updating-view) (params-to-query-str))))
           (redirect))

      ;; Otherwise process the request as normal:
      :else
      (html-response
       request
       {:title (-> :projects (get-config) (get project-name) :project-title
                   (str " -- " branch-name))
        ;; Set auto-refresh if the follow flag is set:
        :auto-refresh (when (boolean follow)
                        {:project-name project-name, :branch-name branch-name, :interval 5000})
        :heading [:div
                  [:a {:href "/"} "DROID"]
                  " / "
                  [:a {:href (str "/" project-name)} project-name]
                  " / "
                  branch-name]
        :content [:div
                  [:div
                   [:p {:class "mt-n2"}
                    (branch-status-summary project-name branch-name)
                    (let [pr-url (->> @branches/remote-branches
                                      (#(get % (keyword project-name)))
                                      (filter #(= branch-name (:name %)))
                                      (first)
                                      :pull-request)]
                      (when pr-url
                        [:span " &ndash; open pull request "
                         [:a {:href pr-url :target "__blank"}
                          "#" (-> pr-url (string/split #"/") (last))]]))]

                   [:hr {:class "line1"}]

                   (when (container-rebuilding? project-name branch-name)
                     [:p {:class "alert alert-primary"}
                      "The container for this branch is currently being built. Access "
                      "will be read-only until it is complete."])
                   (when process-killed
                     [:p {:class "alert alert-warning"}
                      "Process killed. If the process was being monitored in a different tab, "
                      "you should now close that tab."])

                   (if (or confirm-update confirm-kill updating-view missing-view)
                     ;; If the confirm-update, confirm-kill, or updating-view flags have been set,
                     ;; then in the former case, render an update prompt. In the latter two cases,
                     ;; the parameters will be handled by the render-console function:
                     (cond confirm-update (prompt-to-update-view branch params)
                           missing-view (notify-missing-view branch view-path)
                           :else (render-console branch params))
                     ;; Otherwise render the branch page as normal:
                     [:div
                      [:div {:class "row"}
                       [:div {:class "col-sm-6"}
                        [:h3 "Workflow"]
                        ;; Render the Workflow HTML from the Makefile:
                        (or (:html Makefile)
                            [:ul [:li "No workflow found"]])]

                       ;; Render the Version Control section if the user has write permission:
                       (when-not (read-only? request)
                         (render-version-control-section branch request))]
                      ;; Render the console:
                      [:hr {:class "line1"}]
                      [:div
                       [:h3 "Console"]
                       (render-console branch params)]])]]}))))

(defn view-file
  "View a file in the workspace if it is in the list of allowed views for the branch. If the file is
  out of date, then ask whether the current version should be served or whether it should be
  rebuilt."
  [{:keys [params]
    {:keys [project-name branch-name view-path confirm-update force-old force-update
            confirm-kill force-kill missing-view]} :params,
    :as request}]
  (log/debug "Processing request in view-file with params:" params)
  (let [branch-key (keyword branch-name)
        project-key (keyword project-name)
        branch-agent (->> @branches/local-branches project-key branch-key)
        branch-url (str "/" project-name "/branches/" branch-name)
        this-url (str branch-url "/views/" view-path)
        ;; In some cases we need DROID to be able to navigate to parent directories, but because
        ;; most browsers' will 'smartly' remove '../' elements in the path, we need to circumvent
        ;; this by replacing those strings with 'PREV_DIR/'. When we come to fetch the view, we then
        ;; need to replace 'PREV_DIR' with '../' again in order to determine the filesystem path:
        decoded-view-path (when view-path (string/replace view-path #"PREV_DIR\/" "../"))
        ;; Kick off a job to refresh the branch information, wait for it to complete, and then
        ;; finally retrieve the views from the branch's Makefile. Only a request to retrieve
        ;; one of these allowed views will be honoured:
        [allowed-file-views
         allowed-dir-views
         allowed-exec-views] (do (send-off branch-agent branches/refresh-local-branch)
                                 (await branch-agent)
                                 (-> @branch-agent
                                     :Makefile
                                     ;; Generate a vector of hash sets for each type of view:
                                     (#(-> (:file-views %)
                                           (hash-set)
                                           (vec)
                                           (conj (:dir-views %))
                                           (conj (:exec-views %))))))
        ;; If this is an executable view and the view-path includes a component that needs to be
        ;; interpreted as path information (i.e., that should be passed via the PATH_INFO
        ;; environment variable to a CGI script), then extract it and store it separately from the
        ;; actual path to the view:
        [decoded-view-path path-info] (let [exec-view (->> allowed-exec-views
                                                           (filter #(string/starts-with?
                                                                     decoded-view-path
                                                                     (str % "/")))
                                                           (first))]
                                        (if-not exec-view
                                          [decoded-view-path ""]
                                          [exec-view (->> exec-view (count)
                                                          (subs decoded-view-path))]))
        makefile-name (-> @branch-agent :Makefile :name)
        ;; Runs `make -q` to see if the view is up to date:
        up-to-date? #(let [[process exit-code] (branches/run-branch-command
                                                ["make" "-f" makefile-name "-q" decoded-view-path
                                                 :dir (get-make-dir project-name branch-name)]
                                                project-name branch-name)]
                       (or (= @exit-code 0) false))

        ;; Runs `make` (in the background) to rebuild the view, with output directed to the branch's
        ;; console.txt file:
        update-view (fn [branch]
                      (log/info "Rebuilding view" decoded-view-path "from branch" branch-name
                                "of project" project-name "for user"
                                (-> request :session :user :login))
                      (let [[process exit-code]
                            (branches/run-branch-command
                             ["sh" "-c"
                              ;; `exec` is needed here to prevent the make process from
                              ;; detaching from the parent (since that would make it
                              ;; difficult to destroy later).
                              (str "exec make -f " makefile-name " " decoded-view-path
                                   " > " (get-temp-dir project-name branch-name)
                                   "/console.txt 2>&1")
                              :dir (get-make-dir project-name branch-name)]
                             project-name branch-name)]
                        (assoc branch
                               :action decoded-view-path
                               :command (str "make -f " makefile-name " " decoded-view-path)
                               :process process
                               :start-time (System/currentTimeMillis)
                               :cancelled false
                               :exit-code (future (sh/exit-code process)))))

        ;; Delivers an executable, possibly CGI-aware, script:
        deliver-exec-view #(let [script-path (-> (get-make-dir project-name branch-name)
                                                 (str "/" decoded-view-path))]
                             (cond (not (branches/path-executable?
                                         script-path project-name branch-name))
                                   (let [error-msg (str script-path " is not executable")]
                                     (log/error error-msg)
                                     (render-4xx request 400 error-msg))

                                   (-> script-path (slurp) (string/starts-with? "#!") (not))
                                   (let [error-msg (str script-path " does not start with '#!'")]
                                     (log/error error-msg)
                                     (render-4xx request 400 error-msg))

                                   :else
                                   (run-cgi script-path path-info request)))

        ;; Serves the view from the filesystem:
        deliver-file-view (fn []
                            (-> (get-make-dir project-name branch-name)
                                (str "/" decoded-view-path)
                                (file-response)
                                ;; Views must not be cached by the client browser:
                                (#(assoc % :headers (-> %
                                                        :headers
                                                        (merge {"Cache-Control" "no-store"}))))))

        ;; Used for checking whether the requested view is in the workspace directory:
        canonical-ws-dir (-> (get-workspace-dir project-name branch-name) (io/file)
                             (.getCanonicalPath))
        canonical-view-path (-> (get-make-dir project-name branch-name) (str "/" decoded-view-path)
                                (io/file) (.getCanonicalPath))]

    ;; Note that below we do not call (await) after calling (send-off), because below we
    ;; always then redirect back to the view page, which results in another call to this function,
    ;; which always calls await when it starts (see above).
    (cond
      ;; If the view-path is not in the workspace directory, render a 403 forbidden
      (-> (str canonical-view-path "/") (string/starts-with? canonical-ws-dir) (not))
      (do
        (log/warn "Attempt to access directory:" canonical-view-path "(outside workspace directory)"
                  "from page" branch-name "of project" project-name
                  (let [login (-> request :session :user :login)] (when login (str "by " login))))
        (render-403 request))

      ;; If the decoded-view-path isn't in the sets of allowed file and exec views, and it isn't
      ;; inside one of the allowed directory views, then render a 404:
      (and (not-any? #(= decoded-view-path %) allowed-file-views)
           (not-any? #(= decoded-view-path %) allowed-exec-views)
           (not-any? #(-> decoded-view-path (string/starts-with? %)) allowed-dir-views))
      (render-404 request)

      ;; If the view is a directory, assume that the user wants a file called index.html inside
      ;; that directory:
      (and decoded-view-path (-> decoded-view-path (string/ends-with? "/")))
      (redirect (-> this-url (str "index.html")))

      ;; If the 'missing-view' parameter is present (which can only happen if the user has
      ;; read-only access), then render the page for the branch. The 'missing-view parameter will
      ;; then be handled during rendering and a notification area will be displayed on the page
      ;; informing the user that the view does not exist:
      (not (nil? missing-view))
      (render-branch-page @branch-agent request)

      ;; If the user has read-only access, then send him to Unauthorized if the view is an
      ;; executable. Otherwise, if the view exists deliver it whether or not it is stale. If the
      ;; view does not exist, redirect back to this page with the missing-view parameter set.
      (read-only? request)
      (cond
        (some #(= decoded-view-path %) allowed-exec-views)
        (render-401 request)

        (view-exists? @branch-agent decoded-view-path)
        (deliver-file-view)

        :else
        ;; Note that we use the non-decoded viewpath since this is a URL:
        (redirect (-> this-url (str "?missing-view=") (str view-path))))

      ;; If the view isn't an executable, then render the current version of the view if either the
      ;; 'force-old' parameter is present, or if the view is already up-to-date:
      (and (not-any? #(= decoded-view-path %) allowed-exec-views)
           (or (not (nil? force-old))
               (up-to-date?)))
      (deliver-file-view)

      ;; If there is a currently running process (indicated by the presence of an unrealised
      ;; exit code on the branch), then what needs to be done will be determined by the
      ;; presence of further query parameters sent in the request.
      (and (->> @branch-agent :process (nil?) (not))
           (->> @branch-agent :exit-code (realized?) (not)))
      (cond
        ;; If the force-kill parameter has been sent, kill the process and redirect the user back to
        ;; the view:
        (not (nil? force-kill))
        (do
          (send-off branch-agent branches/kill-process (-> request :session :user))
          (redirect (str this-url "?" (-> params
                                          (dissoc :force-kill)
                                          (assoc :process-killed 1)
                                          (params-to-query-str)))))

        ;; If the confirm-kill parameter has been sent, then simply render the page for the
        ;; branch. The confirm-kill flag will be recognised during rendering and a prompt
        ;; will be displayed to request that the user confirm his action:
        (not (nil? confirm-kill))
        (render-branch-page @branch-agent request)

        ;; If the action currently running is an update of the very view we are requesting,
        ;; then do nothing and redirect back to the branch page:
        (->> @branch-agent :action (= decoded-view-path))
        (redirect (str branch-url "?" (params-to-query-str params)))

        ;; Otherwise redirect back to the page with the confirm-kill flag set:
        :else
        (redirect (str this-url "?" (-> params
                                        (assoc :confirm-kill 1)
                                        (assoc :path-info path-info)
                                        (params-to-query-str))
                       "#console")))

      ;; If the view is an executable, then render the current version of the view if either the
      ;; 'force-old' parameter is present, or if the view is already up-to-date:
      (and (some #(= decoded-view-path %) allowed-exec-views)
           (or (not (nil? force-old))
               (up-to-date?)))
      (deliver-exec-view)

      ;; If the 'force-update' parameter is present, immediately (re)build the view in the
      ;; background and then redirect to the branch's page where the user can view the build
      ;; process's output in the console.
      (not (nil? force-update))
      (do
        (send-off branch-agent update-view)
        ;; Here we aren't (await)ing for the process to finish, but for the branch to update,
        ;; which in this case means adding a reference to the currently running build process:
        (await branch-agent)
        (redirect (-> branch-url (str "?" (-> params
                                              (dissoc :force-update)
                                              (assoc :updating-view 1)
                                              (params-to-query-str))))))

      ;; If the 'confirm-update' parameter is present, simply render the page for the branch. The
      ;; confirm-update flag will be recognised during rendering and a prompt will be displayed
      ;; asking the user to confirm her action:
      (not (nil? confirm-update))
      (render-branch-page @branch-agent request)

      ;; Otherwise redirect back to this page, setting the confirm-update flag to ask the user if
      ;; she would really like to rebuild the file. We also pass any existing parameters through,
      ;; as well as any path information, since they will be needed if this is an executable view:
      :else
      (redirect
       (-> this-url (str "?" (-> params
                                 (assoc :confirm-update 1)
                                 (assoc :path-info path-info)
                                 (params-to-query-str))))))))

(defn hit-branch
  "Render a branch, possibly performing an action on it in the process. Note that this function will
  call the branch's agent to potentially modify the branch."
  [{:keys [params]
    {:keys [project-name branch-name new-action new-action-param confirm-kill force-kill
            updating-view cancel-kill-for-view cancel-update-view close-tab]} :params
    :as request}]
  (letfn [(launch-process [branch]
            (log/info "Starting action" new-action "on branch" branch-name "of project"
                      project-name "for user" (-> request :session :user :login)
                      (when new-action-param (str "with parameter " new-action-param)))
            (let [command (if (some #(= % new-action) (->> gh/git-actions (keys) (map name)))
                            ;; If the action is a git action then lookup its corresponding command
                            ;; in the git-actions map. If the value retrieved is a function, then
                            ;; pass it the appropriate parameters. Otherwise, return it as is.
                            (-> gh/git-actions
                                (get (keyword new-action))
                                (get :command)
                                (#(if (function? %)
                                    (% {:msg new-action-param, :user (-> request :session :user)})
                                    %)))
                            ;; Otherwise prefix it with "make -f <makefile-name>":
                            (str "make -f " (-> branch :Makefile :name) " " new-action))
                  [process exit-code] (when-not (nil? command)
                                        (branches/run-branch-command
                                         ["sh" "-c"
                                          ;; `exec` is needed here to prevent the make process from
                                          ;; detaching from the parent (since that would make it
                                          ;; difficult to destroy later).
                                          (str
                                           "exec " command
                                           " > " (get-temp-dir project-name branch-name)
                                           "/console.txt 2>&1")
                                          :dir (get-make-dir project-name branch-name)]
                                         project-name branch-name))]
              ;; Add a small sleep to allow enough time for the process to begin to write its
              ;; output to the console:
              (Thread/sleep 500)
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
                       :cancelled false
                       :exit-code exit-code))))

          (launch-process-with-creds [branch-agent]
            ;; Stores credentials to a temporary file before passing the new-action specified in the
            ;; request to the given branch agent. Once the action is done the creds are removed.
            (send-off branches/local-branches branches/store-creds project-name branch-name
                      request)
            (await branches/local-branches)
            (send-off branch-agent launch-process)
            ;; In addition to awaiting for the branch agent, we must ensure that the process is
            ;; complete before removing the credentials file:
            (await branch-agent)
            (-> @branch-agent :exit-code (deref))
            (send-off branches/local-branches branches/remove-creds project-name branch-name))]

    (log/debug "Processing request in hit-branch with params:" params)
    (let [branch-key (keyword branch-name)
          project-key (keyword project-name)
          branch-agent (->> @branches/local-branches project-key branch-key)
          last-exit-code (when branch-agent (:exit-code @branch-agent))
          this-url (str "/" project-name "/branches/" branch-name)]

      (when branch-agent
        ;; Send off a branch refresh and then (await) for it (and for any previous modifications to
        ;; the branch) to finish:
        (send-off branch-agent branches/refresh-local-branch)
        (await branch-agent)

        ;; If the associated process is a git action, then wait for it to finish, which is
        ;; accomplished by dereferencing its exit code:
        (when (and (->> gh/git-actions (keys) (map name) (some #(= % (:action @branch-agent))))
                   (-> @branch-agent :exit-code))
          (-> @branch-agent :exit-code (deref))))

      ;; Note that below we do not call (await) after calling (send-off), because below we
      ;; always redirect back to the branch page, which results in another call to this function,
      ;; which always calls await when it starts (see above).
      (cond
        ;; The given branch does not exist:
        (nil? branch-agent)
        (render-404 request)

        ;; This condition will not normally be hit, since the action buttons should all
        ;; be greyed out for read-only users. But this could nonetheless happen if the user tries
        ;; to manually enter the action url into the browser's address bar, so we guard against that
        ;; here:
        (and (not (nil? new-action))
             (read-only? request))
        (render-401 request)

        ;; If this is a cancel process action, kill the existing process. If the cancelled process
        ;; was an update of a view, then render the "close tab" page; otherwise redirect to the
        ;; branch page:
        (= new-action "cancel-DROID-process")
        (do
          (send-off branch-agent branches/kill-process (-> request :session :user))
          (if updating-view
            (render-close-tab request)
            (redirect this-url)))

        ;; If this is a cancel action related to a view (i.e., if when asked to confirm whether she
        ;; really wants to update the view, or kill an existing process so as to update the view,
        ;; the user changes her mind and clicks on the "do nothing" button), or if we see the
        ;; "close-tab" parameter, then just render the "close this tab" page:
        (or cancel-update-view cancel-kill-for-view close-tab)
        (render-close-tab request)

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
          ;; relaunch the new one. If the action is a git push, store the user credentials first
          ;; and then remove them afterwards.
          (not (nil? force-kill))
          (do
            (send-off branch-agent branches/kill-process (-> request :session :user))
            (if (= new-action "git-push")
              (launch-process-with-creds branch-agent)
              (send-off branch-agent launch-process))
            (-> this-url
                ;; Remove the force kill and new action parameters:
                (str "?" (-> params
                             (dissoc :force-kill :new-action :new-action-param)
                             (assoc :process-killed 1)
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
        ;; simply launch the new process and redirect back to the page. If the action is a git push,
        ;; store the user credentials first and then remove them afterwards.
        :else
        (do
          (if (= new-action "git-push")
            (launch-process-with-creds branch-agent)
            (send-off branch-agent launch-process))
          (-> this-url
              ;; Remove the new action params so that the user will not be able to launch the action
              ;; again by hitting his browser's refresh button by mistake:
              (str "?" (-> params (dissoc :new-action :new-action-param) (params-to-query-str)))
              (redirect)))))))

;; Decorator for accesses to branches, views, and the project page: Check to see if the remote
;; branch list is empty, and if it is, run a refresh:
(defdecorator refresh-remotes-when-empty
  [f] [& args]
  (let [[{:keys [params]
          {:keys [project-name]} :params, {{:keys [login]} :user} :session
          :as request}
         & rest] args
        remote-branches-contents (->> project-name (keyword) (get @branches/remote-branches))]

    ;; If the collection of remote branches is empty then refresh it:
    (when (and (not (nil? project-name))
               (->> :projects (get-config) (keys) (some #(= % project-name)))
               (or (nil? remote-branches-contents) (empty? remote-branches-contents)))
      (log/debug "Remote branches are empty. Refreshing ...")
      (send-off branches/remote-branches
                branches/refresh-remote-branches-for-project project-name request)
      (await branches/remote-branches))
    ;; Now we can call the function:
    (apply f args)))

(decorate render-project refresh-remotes-when-empty)
(decorate view-file refresh-remotes-when-empty)
(decorate hit-branch refresh-remotes-when-empty)

(ns droid.handler
  (:require [cheshire.core :as cheshire]
            [clojure.java.io :as io]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [environ.core :refer [env]]
            [org.httpkit.client :as http]
            [ring.middleware.defaults :refer [secure-site-defaults site-defaults wrap-defaults]]
            [ring.middleware.oauth2 :refer [wrap-oauth2]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.util.request :refer [body-string]]
            [ring.util.response :refer [redirect]]
            [droid.config :refer [get-config]]
            [droid.db :as db]
            [droid.github :as gh]
            [droid.log :as log]
            [droid.html :as html]))

(def secrets
  "Secret IDs and passcodes, loaded from environment variables."
  ;; Note that the env package maps an environment variable named ENV_VAR into the keyword
  ;; :env-var, so below :github-client-id is associated with GITHUB_CLIENT_ID and similarly for
  ;; :github-client-secret.
  (->> [:github-client-id :github-client-secret]
       (map #(let [val (env %)]
               (if (nil? val)
                 ;; Raise an error if the environment variable isn't found:
                 (-> % (str " not set") (log/fail))
                 ;; Otherwise return a hashmap with one entry:
                 {% val})))
       ;; Merge the hashmaps corresponding to each environment variable into one hashmap:
       (apply merge)))

(defn- wrap-raw-body-rdr
  "Create a Reader corresponding to the raw request body and add it to the request."
  [handler]
  ;; Note that this code is adapted from: https://stackoverflow.com/a/37397991
  (fn [request]
    (let [raw-body (body-string request)]
      (->> raw-body (char-array) (io/reader) (assoc request :raw-body-rdr) (handler)))))

(defn- wrap-authenticated
  "If the request is to logout, then reset the session and redirect to the index page. Otherwise,
  just handle the request."
  [handler]
  (fn [request]
    (if (= "/logout" (:uri request))
      (do
        (log/info "Logging out" (-> request :session :user :login))
        (assoc (redirect "/?just-logged-out=1") :session {}))
      (handler request))))

(defn- wrap-user
  "Add user and GitHub OAuth2 information to the request."
  [handler]
  (fn [request]
    (handler
     (cond
       ;; If the user is already authenticated, just send the request back unchanged:
       (-> request :session :user :authenticated)
       request

       ;; If the user isn't authenticated, but there's a GitHub token, fetch the user information
       ;; from GitHub:
       (-> request :oauth2/access-tokens :github)
       (let [token (-> request :oauth2/access-tokens :github :token)
             {:keys [status headers body error] :as resp} @(http/get "https://api.github.com/user"
                                                                     {:headers
                                                                      {"Authorization"
                                                                       (str "token " token)}})]
         (if error
           (do
             (log/error "Failed to get user information from GitHub: " status headers body error)
             (dissoc request :oauth2/access-tokens))
           (let [user (cheshire/parse-string body true)
                 project-permissions (-> user :login (gh/get-project-permissions token))]
             (log/info "Logging in" (:login user) "with permissions" project-permissions)
             (-> request
                 (assoc-in [:session :user] user)
                 (assoc-in [:session :user :authenticated] true)
                 ;; Add the user's permissions for each project managed by the instance:
                 (assoc-in [:session :user :project-permissions] project-permissions)))))

       ;; If the user isn't authenticated and there isn't a github token, do nothing:
       :else
       request))))

(defroutes app-routes
  (GET "/" [] html/render-index)
  (GET "/just_logged_in" [] html/just-logged-in)
  (POST "/github_webhook" [] html/render-github-webook-response)
  (GET "/:project-name" [] html/render-project)
  (GET "/:project-name/branches/:branch-name/views/:view-path{.+}" [] html/view-file)
  (POST "/:project-name/branches/:branch-name/views/:view-path{.+}" [] html/view-file)
  (GET "/:project-name/branches/:branch-name" [] html/hit-branch)
  ;; all other, return 404
  (route/not-found html/render-404))

(defn- create-app
  "Initialize a web server"
  []
  (-> app-routes
      wrap-authenticated
      wrap-user
      (wrap-oauth2
       {:github
        {:authorize-uri    "https://github.com/login/oauth/authorize"
         :access-token-uri "https://github.com/login/oauth/access_token"
         :client-id        (:github-client-id secrets)
         :client-secret    (:github-client-secret secrets)
         :basic-auth?      true
         :scopes           ["user:email public_repo"]
         :launch-uri       "/oauth2/github"
         :redirect-uri     "/oauth2/github/callback"
         :landing-uri      "/just_logged_in"}})
      (wrap-session
       {:store db/session-store})
      (wrap-defaults
       (let [secure-site? (get-config :secure-site)]
         (-> (if secure-site? secure-site-defaults site-defaults)
             (assoc :proxy true)
             (assoc-in [:security :anti-forgery] false)
             (assoc-in [:session :cookie-attrs :same-site] :lax)
             (assoc-in [:session :store] (cookie-store {:key (:cookie-store-key secrets)})))))
      (wrap-raw-body-rdr)))

(def app
  "The web app"
  (create-app))

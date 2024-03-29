(ns droid.handler
  (:require [cheshire.core :as cheshire]
            [clj-time.core :refer [now plus seconds before?]]
            [clj-time.format :as f]
            [clojure.java.io :as io]
            [clojure.walk :refer [keywordize-keys]]
            [co.deps.ring-etag-middleware :as etag]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [org.httpkit.client :as http]
            [ring.middleware.defaults :refer [secure-site-defaults site-defaults wrap-defaults]]
            [ring.middleware.not-modified :as not-modified]
            [ring.middleware.oauth2 :refer [wrap-oauth2]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.util.codec :as codec]
            [ring.util.request :refer [body-string]]
            [ring.util.response :refer [redirect]]
            [taoensso.nippy :refer [*thaw-serializable-allowlist*]]
            [droid.config :refer [get-config]]
            [droid.db :as db]
            [droid.github :as gh]
            [droid.log :as log]
            [droid.html :as html]
            [droid.secrets :refer [secrets]])
  (:import (java.io ByteArrayInputStream
                    ByteArrayOutputStream)))

;; The class `org.joda.time.DateTime`, which is used by GitHub to store a user access token's
;; expiration time, is not by default one of the classes that are permitted by `nippy` to use
;; Java's Serializable interface; so we add it explicitly here.
;; See: https://github.com/ptaoussanis/nippy/issues/130
(alter-var-root #'*thaw-serializable-allowlist*
                (fn [-] (-> *thaw-serializable-allowlist* (into #{"org.joda.time.DateTime"}))))

(def ^:private ua-token-store
  "Atom used to hold GitHub user access tokens"
  ;; The reason this is needed is that some branch actions end in redirects. Any changes to the
  ;; user's session that are made during the handling of the request, although propagated
  ;; forward to any future brand new requests, are not propagated to the redirect. To handle such
  ;; cases, we store the last known valid token set for a session into this atom. Then, when we are
  ;; handling a given request, if there is a token in the token store corresponding to the given,
  ;; session, we use it in lieu of the token contained in the request. Note that we do not need
  ;; to persist the token store to disk. Unless we are very unlucky and the server crashes in the
  ;; middle of a redirect, there should always be a valid token in the user's session, and user
  ;; sessions are already being stored in db/session-store, which is persisted to disk.
  (atom {}))

(defn- wrap-body-bytes
  "Copy the bytes of the request body to :body-bytes."
  [handler]
  ;; Note that this code is adapted from: https://stackoverflow.com/a/20570399
  (fn [request]
    (let [buffer (ByteArrayOutputStream.)
          _ (when (:body request) (io/copy (:body request) buffer))
          bytes (.toByteArray buffer)]
      (handler
       (assoc
        request
        :body (ByteArrayInputStream. bytes)
        :body-bytes bytes)))))

(defn- wrap-authenticated
  "If the request is to logout, then reset the session and redirect to the index page. Otherwise,
  just handle the request."
  [handler]
  (fn [request]
    (if (= "/logout" (:uri request))
      (do
        (log/info "Logging out" (-> request :session :user :login))
        (->> request :session/key (keyword) (swap! ua-token-store dissoc))
        (assoc (redirect "/?just-logged-out=1") :session {}))
      (handler request))))

(defn- get-latest-ua-tokens
  "Given a session identifier, information about the user associated with that session, and
  information about the token associated with that session, determine whether the token is still
  valid or if it is expired. If it is still valid, add it, and its associated refresh token and
  expiration time to the token store, and return these unchanged to the caller. In the case where it
  is expired, use the refresh token to fetch a new token, expiration time, and refresh token from
  GitHub, store these in the token store, and return them back to the caller."
  [{session-key :session/key
    {{:keys [login authenticated]} :user} :session,
    {{:keys [token expires refresh-token] :as incoming-token-map} :github} :oauth2/access-tokens}]
  (let [stored-token-map (->> session-key (keyword) (get @ua-token-store))
        {stored-token :token,
         stored-expires :expires,
         stored-refresh-token :refresh-token} stored-token-map
        current-time (now)]
    (cond
      ;; The user isn't logged in. In this case just send back the incoming tokens unchanged:
      (not authenticated)
      incoming-token-map

      ;; The token store contains an unexpired token for the given session, which we return:
      (and stored-expires (before? current-time stored-expires))
      stored-token-map

      ;; There is no stored token, but the incoming token hasn't expired. Store it in the token
      ;; store and return it to the caller unchanged:
      (and (not stored-token-map) (before? current-time expires))
      (do (swap! ua-token-store assoc (keyword session-key) incoming-token-map)
          incoming-token-map)

      ;; Otherwise, request a new token from GitHub using the refresh token from the token store if
      ;; it exists. If it does not (which might happen after a server restart), then try to use the
      ;; incoming token instead.
      :else
      (let [rtoken (do
                     (log/info "Refreshing user access token for" login)
                     (if-not stored-refresh-token
                       (do (log/warn "No stored refresh token found. Using incoming refresh token")
                           refresh-token)
                       stored-refresh-token))]
        (let [{:keys [body status headers error] :as resp}
              @(http/post "https://github.com/login/oauth/access_token"
                          {:headers {"Content-Type" "application/x-www-form-urlencoded"}
                           :form-params {"refresh_token" rtoken
                                         "grant_type" "refresh_token"
                                         "client_id" (:github-client-id secrets)
                                         "client_secret" (:github-client-secret secrets)}})]
          (when (or (< status 200) (> status 299))
            (throw
             (Exception.
              (str "Failed to request refresh token from GitHub. Status code: " (:status resp)))))

          (let [{:keys [error error_uri error_description],
                 new-token :access_token,
                 new-validity :expires_in,
                 new-rtoken :refresh_token} (-> body (slurp) (codec/form-decode) (keywordize-keys))
                new-expires (when-not error
                              (->> new-validity (Integer/parseInt) (seconds) (plus current-time)))]
            (if error
              (do
                (log/error (str "Refresh GitHub token request returned the error: "
                                error ": " error_description " See " error_uri))
                {:token "error" :expires new-expires :refresh-token "error"})
              (let [new-token-info {:token new-token
                                    :expires new-expires
                                    :refresh-token new-rtoken}]
                (swap! ua-token-store assoc (keyword session-key) new-token-info)
                new-token-info))))))))

(defn- wrap-user
  "Add user and GitHub OAuth2 information to the request."
  [handler]
  (fn [{session-key :session/key, :as request}]
    (handler
     ;; If the local-mode parameter is set in the config, and there is a personal access token in
     ;; the secrets map, use it to authenticate. Otherwise look for the token info in the request.
     ;; Note that variable `token` (not `pat-token`) is used for authentication, but if `pat-token`
     ;; exists then we overwrite any existing value of `token` with it, rendering the two identical.
     (let [pat-token (and (get-config :local-mode) (:personal-access-token secrets))
           local-mode? (not (nil? pat-token))
           ua-token-info (when-not pat-token (get-latest-ua-tokens request))
           ua-token-expires (:expires ua-token-info)
           ua-refresh-token (:refresh-token ua-token-info)
           token (or pat-token (:token ua-token-info))
           ;; Function to save token info to the user's session parameters in the given request:
           add-token-info-to-req
           #(-> %
                (assoc-in [:session :ring.middleware.oauth2/access-tokens :github :token] token)
                (assoc-in [:session :ring.middleware.oauth2/access-tokens :github :expires]
                          ua-token-expires)
                (assoc-in [:session :ring.middleware.oauth2/access-tokens :github :refresh-token]
                          ua-refresh-token))]
       (cond
         (= token "error")
         (do
           (log/error "The token is invalid. This error is unrecoverable. Logging user out!")
           (-> request (dissoc :session)))

         ;; If the user is already authenticated, just send the request back, replacing any existing
         ;; token info in the session part of the request with the token info retrieved above:
         (-> request :session :user :authenticated)
         (add-token-info-to-req request)

         ;; Otherwise, if there is a token, fetch the user info from GitHub:
         token
         (let [{:keys [status headers body error] :as resp}
               @(http/get "https://api.github.com/user" {:headers {"Authorization"
                                                                   (str "token " token)}})]
           (if error
             (do
               (log/error "Failed to get user information from GitHub. Received error:" error
                          (str " (Status: " status ")."))
               (-> request :session (dissoc :ring.middleware.oauth2/access-tokens)))
             (let [user (cheshire/parse-string body true)
                   project-permissions (-> user :login (gh/get-project-permissions token))]
               (log/info "Logging in" (:login user) "with permissions" project-permissions)
               (-> request
                   (add-token-info-to-req)
                   ;; Add user info including permissions for each project managed by the instance:
                   (assoc-in [:session :user] user)
                   (assoc-in [:session :user :authenticated] true)
                   (assoc-in [:session :user :project-permissions] project-permissions)))))

         ;; If the user isn't authenticated and there isn't a github token and we are not in local
         ;; mode, send the request back unchanged:
         :else
         request)))))

(defroutes app-routes
  (GET "/" [] html/render-index)
  (GET "/just_logged_in" [] html/just-logged-in)
  (POST "/github_webhook" [] html/render-github-webhook-response)
  (GET "/:project-name" [] html/render-project)
  (GET "/:project-name/branches/:branch-name/views/:view-path{.+}" [] html/view-file)
  (POST "/:project-name/branches/:branch-name/views/:view-path{.+}" [] html/view-file)
  (GET "/:project-name/branches/:branch-name" [] html/hit-branch)
  (GET "/:project-name/:branch-name/console" [] html/refresh-console)
  ;; all other, return 404
  (route/not-found html/render-404))

(defn- create-app
  "Initialize a web server"
  []
  (-> app-routes
      (etag/wrap-file-etag)
      (not-modified/wrap-not-modified)
      wrap-authenticated
      wrap-user
      (wrap-oauth2
       {:github
        {:authorize-uri    "https://github.com/login/oauth/authorize"
         :access-token-uri "https://github.com/login/oauth/access_token"
         :client-id        (or (:github-client-id secrets) "")
         :client-secret    (or (:github-client-secret secrets) "")
         :basic-auth?      true
         :scopes           ["user:email public_repo"]
         :launch-uri       "/oauth2/github"
         :redirect-uri     "/oauth2/github/callback"
         :landing-uri      "/just_logged_in"}})
      (wrap-session
       {:store db/session-store})
      (wrap-defaults
       (let [insecure-site? (get-config :insecure-site)]
         (-> (if insecure-site? site-defaults secure-site-defaults)
             (assoc :proxy true)
             (assoc-in [:security :anti-forgery] false)
             (assoc-in [:session :cookie-attrs :same-site] :lax)
             (assoc-in [:session :store] (cookie-store {:key (:cookie-store-key secrets)})))))
      (wrap-body-bytes)))

(def app
  "The web app"
  (create-app))

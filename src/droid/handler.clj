(ns droid.handler
  (:require [compojure.core :refer [defroutes GET]]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [secure-site-defaults site-defaults wrap-defaults]]
            [ring.middleware.oauth2 :refer [wrap-oauth2]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.util.response :refer [redirect]]
            [org.httpkit.client :as http]
            [cheshire.core :as cheshire]
            [droid.config :refer [config]]
            [droid.data :as data]
            [droid.db :as db]
            [droid.log :as log]
            [droid.html :as html]))

(defn- wrap-user
  "Add user and GitHub OAuth2 information to the request."
  [handler]
  (fn [request]
    (handler
     (cond
       ;; If the user is already authorized, just send the request back unchanged:
       (-> request :session :user :authorized)
       request

       ;; If the user isn't authorized, but there's a GitHub token, fetch the user information from
       ;; GitHub:
       (-> request :oauth2/access-tokens :github)
       (let [token (-> request :oauth2/access-tokens :github :token)
             {:keys [status headers body error] :as resp} @(http/get "https://api.github.com/user"
                                                                     {:headers
                                                                      {"Authorization"
                                                                       (str "token " token)}})
             user (cheshire/parse-string body true)]
         (if error
           (do
             (log/error "Failed to get user information from GitHib: " status headers body error)
             (dissoc request :oauth2/access-tokens))
           (-> request
               (assoc-in [:session :user] user)
               (assoc-in [:session :user :authorized] true))))

       ;; If the user isn't authorized and there isn't a github token, do nothing:
       :else
       request))))

(defroutes app-routes
  (GET "/" [] html/index)
  (GET "/:project-name" [] html/render-project)
  (GET "/:project-name/branches/:branch-name/views/:view-path{.+}" [] html/view-file!)
  (GET "/:project-name/branches/:branch-name" [] html/hit-branch!)
  ;; all other, return 404
  (route/not-found html/render-404))

(defn- create-app
  "Initialize a web server"
  []
  (-> app-routes
      wrap-user
      (wrap-oauth2
       {:github
        {:authorize-uri    "https://github.com/login/oauth/authorize"
         :access-token-uri "https://github.com/login/oauth/access_token"
         :client-id        (:github-client-id data/secrets)
         :client-secret    (:github-client-secret data/secrets)
         :basic-auth?      true
         :scopes           ["user:email public_repo"]
         :launch-uri       "/oauth2/github"
         :redirect-uri     "/oauth2/github/callback"
         :landing-uri      "/"}})
      (wrap-session
       {:store db/store})
      (wrap-defaults
       (let [op-env (:op-env config)
             secure-site? (-> config
                              :secure-site
                              (get op-env))]
         (-> (if secure-site? secure-site-defaults site-defaults)
             (assoc :proxy true)
             (assoc-in [:security :anti-forgery] false)
             (assoc-in [:session :cookie-attrs :same-site] :lax)
             (assoc-in [:session :store] (cookie-store {:key (:cookie-store-key data/secrets)})))))))

(def app
  "The web app"
  (create-app))

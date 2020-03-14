(ns droid.handler
  (:require [compojure.core :refer [defroutes GET]]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [secure-site-defaults site-defaults wrap-defaults]]
            [ring.middleware.oauth2 :refer [wrap-oauth2]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.util.response :refer [redirect]]
            [org.httpkit.client :as http]
            [cheshire.core :as cheshire]
            [droid.config :refer [config]]
            [droid.data :as data]
            [droid.log :as log]
            [droid.html :as html]))


(defn- wrap-authorized
  "If the request is for the index page, then send it through unchanged. If it is for any other page
  then only send it through if it is authorized, otherwise redirect it to the index page."
  [handler]
  (fn [request]
    (cond (= "/" (:uri request))
          (handler request)

          (-> request :session :user :authorized)
          (handler request)

          :else
          (redirect "/"))))


(defn- wrap-user
  "Add user and GitHub OAuth2 information to the request."
  [handler]
  (fn [request]
    (handler
     (cond
       ;; If the user is already authorized, just send the request on through:
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
             (println "Failed to get user information from GitHib: " status headers body error)
             (dissoc request :oauth2/access-tokens))
           (-> request
               (assoc-in [:session :user] user)
               (assoc-in [:session :user :authorized]
                         (contains? (-> config
                                        :authorized-github-ids
                                        (get (:op-env config)))
                                    (:login user))))))

       ;; If the user isn't authorized and there isn't a github token, do nothing:
       :else
       request))))


(defroutes app-routes
  (GET "/" [] html/index)
  (GET "/branches/:branch-name" [] html/act-on-branch!)
  (route/not-found "Not Found"))


(defn- create-app
  "Initialize a web server"
  []
  (-> app-routes
      wrap-authorized
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
      (wrap-defaults
       (-> (if (get (:secure-site config) (:op-env config)) secure-site-defaults site-defaults)
           (assoc :proxy true)
           (assoc-in [:security :anti-forgery] false)
           (assoc-in [:session :cookie-attrs :same-site] :lax)
           (assoc-in [:session :store] (cookie-store {:key (:cookie-store-key data/secrets)}))))))


;; Initialize the web app:
(def app (create-app))


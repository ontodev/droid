(defproject droid "0.1.0-SNAPSHOT"
  :description "DROID Reminds us that Ordinary Individuals can be Developers"
  :url "https://github.com/ontodev/droid"
  :license {:name "BSD 3-Clause License"
            :url "https://opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [org.clojure/tools.cli "1.0.206"]
                 [cheshire "5.10.1"]
                 [clj-jwt "0.1.1"]
                 [co.deps/ring-etag-middleware "0.2.1"]
                 [com.taoensso/nippy "3.1.1"]
                 [compojure "1.6.2"]
                 [decorate "0.1.3"]
                 [environ "1.2.0"]
                 [hiccup "1.0.5"]
                 [hickory "0.7.1"]
                 [http-kit "2.5.3"]
                 [jdbc-ring-session "1.4.2"]
                 [lambdaisland/uri "1.4.70"]
                 [markdown-to-hiccup "0.6.2"]
                 [me.raynes/conch "0.8.0"]
                 [metosin/ring-http-response "0.9.2"]
                 [com.h2database/h2 "1.4.200"]
                 [ring/ring-defaults "0.3.3"]
                 [ring-oauth2 "0.1.5"]
                 [tentacles "0.5.1"]]
  ;; Increase the timeout value when loading the repl via Leiningen. We need this in a dev setting
  ;; because it is possible that we will have to build multiple docker images at startup and this
  ;; may take awhile. The default is 600000 (10 minutes), which we increase here:
  :repl-options {:timeout 600000}
  :plugins [[lein-ring "0.12.5"]]
  :ring {:handler droid.handler/app}
  :main ^:skip-aot droid.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})

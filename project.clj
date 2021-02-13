(defproject droid "0.1.0-SNAPSHOT"
  :description "DROID Reminds us that Ordinary Individuals can be Developers"
  :url "https://github.com/ontodev/droid"
  :license {:name "BSD 3-Clause License"
            :url "https://opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/java.jdbc "0.7.11"]
                 [org.clojure/tools.cli "1.0.194"]
                 [clj-jwt "0.1.1"]
                 [co.deps/ring-etag-middleware "0.2.1"]
                 [compojure "1.6.1"]
                 [decorate "0.1.3"]
                 [environ "1.1.0"]
                 [hiccup "1.0.5"]
                 [hickory "0.7.1"]
                 [http-kit "2.3.0"]
                 [jdbc-ring-session "1.3"]
                 [markdown-to-hiccup "0.6.2"]
                 [me.raynes/conch "0.8.0"]
                 [metosin/ring-http-response "0.9.1"]
                 [com.h2database/h2 "1.4.200"]
                 [ring/ring-defaults "0.3.2"]
                 [ring-oauth2 "0.1.4"]
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

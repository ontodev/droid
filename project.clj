(defproject droid "0.1.0-SNAPSHOT"
  :description "DROID Reminds us that Ordinary Individuals can be Developers"
  :url "https://github.com/ontodev/droid"
  :license {:name "BSD 3-Clause License"
            :url "https://opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/java.jdbc "0.7.11"]
                 [compojure "1.6.1"]
                 [environ "1.1.0"]
                 [hiccup "1.0.5"]
                 [http-kit "2.3.0"]
                 [jdbc-ring-session "1.3"]
                 [markdown-to-hiccup "0.6.2"]
                 [me.raynes/conch "0.8.0"]
                 [com.h2database/h2 "1.4.200"]
                 [ring/ring-defaults "0.3.2"]
                 [ring-oauth2 "0.1.4"]
                 [tentacles "0.5.1"]]
  :main ^:skip-aot droid.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})

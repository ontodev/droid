(ns droid.core-test
  (:require [clojure.test :refer [is deftest testing]]
            [droid.core :refer :all]
            [droid.data :refer [branches refresh-branch]]))

(deftest test-parse-makefile
  (let [branch-agent (->> branches :test :master)]
    (send-off branch-agent refresh-branch)
    (await branch-agent)
    (testing "Makefile parsing test"
      (let [makefile (->> @branch-agent :Makefile)]
        (is (= (:branch-name makefile) "master"))
        (is (= (:name makefile) "Makefile"))
        (is (= (:targets makefile) #{"clean" "update" "build/update.txt"}))
        (is (= (:actions makefile) #{"clean" "update"}))
        (is (= (:phony-targets makefile) #{"clean" "update"}))
        (is (= (:views makefile) #{"build/update.txt"}))
        (is (= (:markdown makefile)
               "1. Review the [Knocean Practises Document](https://github.com/knocean/practises)\n2. Run [Clean](clean) to clean the contents of the build/ directory.\n3. Run [Update](update) to update the contents of the build/ directory.\n4. View the results:\n    - [Updated Build](build/update.txt)"))
        (is (= (:html makefile)
               [:div {}
                [:ol {}
                 [:li {}
                  "Review the " [:a {:href "https://github.com/knocean/practises",
                                     :target "__blank"} "Knocean Practises Document"]]
                 [:li {}
                  "Run " [:a {:href "?action=clean", :class "btn btn-primary btn-sm"}
                          "Clean"] " to clean the contents of the build/ directory."]
                 [:li {}
                  "Run " [:a {:href "?action=update", :class "btn btn-primary btn-sm"}
                          "Update"] " to update the contents of the build/ directory."]
                 [:li {}
                  "View the results:"
                  [:ul {}
                   [:li {}
                    [:a {:href "master/views/build/update.txt", :target "__blank"}
                     "Updated Build"]]]]]]))))))

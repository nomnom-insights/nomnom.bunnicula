(ns bunnicula.utils-test
  (:require
    [bunnicula.utils :as bu]
    [clojure.test :refer [deftest thrown-with-msg? is testing]]))


(deftest time-limited-test
  (testing "result of body gets return when not timeout"
    (is (= "result" (bu/time-limited 100 "timeout" (do (Thread/sleep 50) "result")))))
  (testing "timeout-response gets return when timeout"
    (is (= "timeout" (bu/time-limited 50 "timeout" (do (Thread/sleep 100) (/ 0)))))) ; test code is not evaluated!
  (testing "exception bubbles up"
    (is (thrown-with-msg?  Exception #"Divide by zero" (bu/time-limited 2000 "timeout" (/ 0))))))

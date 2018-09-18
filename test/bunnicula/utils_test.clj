(ns bunnicula.utils-test
  (:require [bunnicula.utils :refer :all]
            [clojure.test :refer :all]))

(deftest time-limited-test
  (testing "result of body gets return when not timeout"
    (is (= "result" (time-limited 100 "timeout" (do (Thread/sleep 50) "result")))))
  (testing "timeout-response gets return when timeout"
    (is (= "timeout" (time-limited 50 "timeout" (do (Thread/sleep 100) (/ 0)))))) ; test code is not evaluated!
  (testing "exception bubbles up"
    (is (thrown-with-msg?  Exception #"Divide by zero" (time-limited 2000 "timeout" (/ 0))))))

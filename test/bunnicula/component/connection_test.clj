(ns bunnicula.component.connection-test
  (:require
    [bunnicula.component.connection :as conn]
    [clojure.test :refer [deftest is testing]]
    [com.stuartsierra.component :as component]))


(def rabbit-config
  {:url (or (System/getenv "RABBIT_URL")
            "amqp://rabbit:password@127.0.0.1:5672")
   :vhost (or (System/getenv "RABBIT_VHOST")
              "%2Fmain")
   :connection-name "test"})


(deftest connection-test
  (testing "connection-test"
    (let [c (atom (conn/create rabbit-config))]
      (is (nil? (:connection @c)))
      (swap! c component/start)
      (is (some? (:connection @c)))
      (swap! c component/stop)
      (is (nil? (:connection @c))))))

(ns bunnicula.component.publisher-test
  (:require [clojure.test :refer :all]
            [bunnicula.component.publisher :refer :all]
            [com.stuartsierra.component :as component]
            [bunnicula.component.connection :as connection]
            [bunnicula.protocol :as protocol]))

(def rabbit-config
  {:url (or (System/getenv "RABBIT_URL")
               "amqp://rabbit:password@127.0.0.1:5672")
   :vhost (or (System/getenv "RABBIT_VHOST")
              "%2Fmain")
   :name "test-connection"})

(def test-system
  (component/system-map
    :rmq-connection (connection/create rabbit-config)
    :publisher (component/using
                 ;; will use default-exchange ""
                 (create {})
                 [:rmq-connection])))

(deftest publisher-test
  (testing "publisher-test"
    (let [system (atom test-system)]
      (is (nil? (:channel (:publisher @system))))
      (swap! system component/start)
      (is (some? (:channel (:publisher @system))))
      ;; should not explode (proper testing in consumer where queue setup)
      (protocol/publish (:publisher @system) "test-queue" "message")
      (protocol/publish (:publisher @system) "" "test-queue" "message" {})
      (swap! system component/stop)
      (is (nil? (:channel (:publisher @system)))))))
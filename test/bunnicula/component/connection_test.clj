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


(deftest connection-parser
  (testing "regular connection string"
    (is (= {:connection-name "rabbit"
            :host "127.0.0.1"
            :password "password"
            :port 5672
            :secure? false
            :username "rabbit"
            :vhost "/foo"}
           (conn/extract-server-config {:url "amqp://rabbit:password@127.0.0.1:5672"
                                        :vhost "/foo"}))))
  (testing "ssl connection string"
    (is (= {:connection-name "rabbit"
            :host "127.0.0.1"
            :password "password"
            :port 5672
            :secure? true
            :username "rabbit"
            :vhost "/foo"}
           (conn/extract-server-config {:url "amqps://rabbit:password@127.0.0.1:5672"
                                        :vhost "/foo"}))))
  (testing "a map"
    (is (= {:connection-name "bananas"
            :host "foobar"
            :password "fruit"
            :port 20000
            :secure? true
            :username "bananas"
            :vhost ""}
           (conn/extract-server-config {:host "foobar"
                                        :port 20000
                                        :secure? true
                                        :username "bananas"
                                        :password "fruit"
                                        :vhost ""}))))
  (testing "round trip"
    (is (= "amqps://usr:pass@some-host:2345/test%2Fbar"
           (-> {:url "amqps://usr:pass@some-host:2345"
                :vhost "test/bar"}
               conn/extract-server-config
               conn/connection-url)))))


(deftest connection-test
  (testing "connection-test"
    (let [c (atom (conn/create rabbit-config))]
      (is (nil? (:connection @c)))
      (swap! c component/start)
      (is (some? (:connection @c)))
      (swap! c component/stop)
      (is (nil? (:connection @c))))))

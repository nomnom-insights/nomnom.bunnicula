(ns bunnicula.component.consumer-with-retry-test
  (:require
    [bunnicula.component.connection :as connection]
    [bunnicula.component.consumer-with-retry :as consumer]
    [bunnicula.component.monitoring :as mon]
    [bunnicula.component.publisher :as publisher]
    [bunnicula.protocol :as protocol]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [com.stuartsierra.component :as component])
  (:import
    (com.rabbitmq.client
      Channel)))


(def rabbit-config
  {:url (or (System/getenv "RABBIT_URL")
            "amqp://rabbit:password@127.0.0.1:5672")
   :vhost (or (System/getenv "RABBIT_VHOST")
              "%2Fmain")})


(def queue-options
  {:queue-name "test.bunnicula"
   :exchange-name ""
   :timeout-seconds 0.2
   :max-retries 2
   :backoff-interval-seconds 0.1
   :consumer-threads 3})


(def test-queue-2
  (assoc queue-options :queue-name "test.bunnicula.fail"))


(def test-results (atom {}))


(defn inc-test-result [key]
  (swap! test-results
         #(update % key (fn [r] (if r (inc r) 1)))))


(defn message-handler-fn
  [_payload message _envelope consumer-system]
  (inc-test-result message)
  (case message
    "ok" (if (= (:dependency consumer-system) "I AM DEPENDENCY")
           ;; if dependency present as expected :ack message
           :bunnicula.consumer/ack
           (throw (ex-info "UNEXPECTED" {})))
    "fail" :bunnicula.consumer/error
    "reject" :bunnicula.consumer/retry
    "error" (throw (ex-info "EXPECTED" {}))
    "timeout" (Thread/sleep 300)))


;; mock of Monitoring component
(defrecord  Monitoring [counter]
  component/Lifecycle
  (start [this]
    (assoc this :counter (atom {:ok 0 :fail 0 :retry 0 :timeout 0 :error 0})))
  (stop [this]
    (assoc this :counter nil))
  protocol/Monitoring
  (with-tracking [this message-fn]
    (time (message-fn)))
  (on-success [this args]
    (swap! counter #(update % :ok inc)))
  (on-error [this args]
    (swap! counter #(update % :error inc)))
  (on-exception [this args]
    (swap! counter #(update % :fail inc)))
  (on-timeout [this args]
    (swap! counter #(update % :timeout inc)))
  (on-retry [this args]
    (swap! counter #(update % :retry inc))))


(def test-system
  (component/system-map
    :rmq-connection (connection/create rabbit-config)
    :publisher (component/using
                 (publisher/create {})
                 [:rmq-connection])
    :publisher-broken (component/using
                        (publisher/create {:serializer (fn [& _] (.getBytes "{not json}<>"))})
                        [:rmq-connection])
    :dependency "I AM DEPENDENCY"
    :mock-monitoring (->Monitoring nil)
    :mock-consumer (component/using
                     (consumer/create
                       {:options queue-options
                        :handler message-handler-fn})
                     ;; those to are required dependencies
                     {:rmq-connection :rmq-connection
                      :monitoring :mock-monitoring
                      ;; will be passed to message-handler
                      :dependency :dependency})

    :mock-consumer-2 (component/using
                       (consumer/create {:options test-queue-2
                                         :message-handler-fn message-handler-fn})
                       {:rmq-connection :rmq-connection
                        :monitoring :mock-monitoring
                      ;; will be passed to message-handler
                        :dependency :dependency})))


(def system (atom nil))


(use-fixtures :each (fn [test-fn]
                      (reset! system (component/start test-system))
                      (try
                        (test-fn)
                        (catch Exception e))
                      (swap! system component/stop)))


(deftest message-handler-test
  (let [publisher (:publisher @system)]
    (reset! test-results {})
    (testing "message-handler"
      (testing "ok"
        (protocol/publish publisher "test.bunnicula" "ok")
        (Thread/sleep 200)
        (is (= {"ok" 1} @test-results)))
      (testing "deserialization error"
        (protocol/publish (:publisher-broken @system) "test.deser.fail" "nevermind")
        (Thread/sleep 1000)
        (is (= nil (get @test-results "error"))))
      (testing "ok again"
        (protocol/publish publisher "test.bunnicula" "ok")
        (Thread/sleep 200)
        (is (= {"ok" 2} @test-results)))
      (testing "fail"
        (protocol/publish publisher "test.bunnicula" "fail")
        ;; wait long enough to ensure no retries
        (Thread/sleep 600)
        (is (= 1 (get @test-results "fail"))))
      (testing "reject"
        (protocol/publish publisher "test.bunnicula" "reject")
        ;; allow all retries to be processed
        (Thread/sleep 600)
        (is (= 3 (get @test-results "reject"))))
      (testing "error"
        (protocol/publish publisher "test.bunnicula" "error")
        ;; allow all retries to be processed
        (Thread/sleep 600)
        (is (= 3 (get @test-results "error"))))
      (testing "timeout"
        (protocol/publish publisher "test.bunnicula" "timeout")
        (Thread/sleep 1000)
        (is (= 3 (get @test-results "timeout")))))))


(deftest consumer-system-test
  (testing "consumer-system-test"
    (is (= 3 (count (:consumer-channels (:mock-consumer @system)))))
    (is (instance? Channel (first (:consumer-channels (:mock-consumer @system)))))
    (is (= 3 (count (:consumer-tags (:mock-consumer @system)))))
    (is (instance? Channel (:channel (:mock-consumer @system))))
    (swap! system component/stop)
    (is (nil? (:consumer-channels (:mock-consumer @system))))
    (is (nil? (:consumer-tags (:mock-consumer @system))))
    (is (nil? (:channel (:mock-consumer @system))))))


(deftest consumer-monitoring-test
  (testing "consumer-monitoring-test"
    (testing "ok"
      (protocol/publish (:publisher @system) "test.bunnicula" "ok")
      (Thread/sleep 350)
      (is (= 1 (-> @system :mock-monitoring :counter deref :ok))))
    (testing "timeout"
      (protocol/publish (:publisher @system) "test.bunnicula" "timeout")
      (Thread/sleep 9000)
      ;; we will retry 3 times!
      (is (= 3 (-> @system :mock-monitoring :counter deref :timeout))))))

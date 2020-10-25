(ns example
  (:require
    [bunnicula.component.connection :as rmq.connection]
    [bunnicula.component.consumer-with-retry :as rmq.consumer]
    [bunnicula.component.monitoring :as consumer.monitoring]
    [bunnicula.component.publisher :as rmq.publisher]
    [bunnicula.protocol :as rmq]
    [clojure.tools.logging :as log]
    [com.stuartsierra.component :as component]))


;; connection details assume that the RabbitMQ is running, as setup in the
;; docker-compose.yml file
(defn create-system []
  {:rmq-connection (rmq.connection/create {:host "127.0.0.1"
                                           :port 5672
                                           :username "rabbit"
                                           :password "password"
                                           :connection-name "example"
                                           :vhost "/main"})
   :monitoring (consumer.monitoring/create)
   :publisher (component/using
                (rmq.publisher/create)
                [:rmq-connection])
   :simple-consumer (component/using
                      (rmq.consumer/create {:handler (fn [_ {:keys [number] :as _payload}
                                                          _ {:keys [publisher] :as _component}]
                                                       (log/infof "Got a number %s\n" number)
                                                       (Thread/sleep 1000)
                                                       (rmq/publish publisher "bunnicula.example.queue"
                                                                    {:number (rand-int 20)})
                                                       ;; if we roll 13, we fail
                                                       (if (= 13 number)
                                                         :bunnicula.consumer/retry
                                                         :bunnicula.consumer/ack))
                                            :options {:queue-name "bunnicula.example.queue"
                                                      :consumer-threads 1}})
                      [:rmq-connection :monitoring :publisher])})


(let [sys (-> (create-system)
              (component/map->SystemMap)
              (component/start))]
  (rmq/publish (:publisher sys) "bunnicula.example.queue" {:number 0}))

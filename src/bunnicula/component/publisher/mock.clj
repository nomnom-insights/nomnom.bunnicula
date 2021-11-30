(ns bunnicula.component.publisher.mock
  (:require
    [bunnicula.protocol :as protocol]
    [com.stuartsierra.component :as component]))


(defrecord Mock
  [queues]

  component/Lifecycle

  (start
    [this]
    (assoc this :queues (atom {})))


  (stop
    [this]
    (assoc this :queues nil))


  protocol/Publisher

  (publish
    [_ routing-key body]
    (swap! queues update routing-key (fn [x] (conj x body))))


  (publish
    [_ routing-key body _]
    (swap! queues update routing-key (fn [x] (conj x body))))


  (publish
    [_ exchange-name routing-key body _]
    (swap! queues update-in [exchange-name routing-key] (fn [x] (conj x body)))))


(defn create
  []
  (map->Mock {}))


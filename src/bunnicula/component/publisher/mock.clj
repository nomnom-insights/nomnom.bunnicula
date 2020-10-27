(ns bunnicula.component.publisher.mock
  (:require
    [bunnicula.protocol :as protocol]
    [com.stuartsierra.component :as component]))


(defrecord Mock [queues]
  component/Lifecycle
  (start [this]
    (assoc this :queues (atom {})))
  (stop [this]
    (assoc this :queues nil))

  protocol/Publisher
  (publish [this routing-key body]
    (swap! queues update routing-key (fn [x] (conj x body))))
  (publish [this routing-key body options]
    (swap! queues update routing-key (fn [x] (conj x body))))
  (publish [this exchange-name routing-key body options]
    (swap! queues update-in [exchange-name routing-key] (fn [x] (conj x body)))))


(defn create []
  (map->Mock {}))


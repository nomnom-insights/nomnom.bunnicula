(ns bunnicula.client.rabbitmq.consumer
  (:import
    (com.rabbitmq.client
      BasicProperties
      Channel
      Consumer
      Envelope)))


(defn- envelope-to-map
  [^Envelope envelope]
  {:routing-key  (.getRoutingKey envelope)
   :exchange     (.getExchange envelope)
   :redelivered? (.isRedeliver envelope)
   :delivery-tag (.getDeliveryTag envelope)})


(defn- properties-to-map
  [^BasicProperties properties]
  {:app-id           (.getAppId properties)
   :content-encoding (.getContentEncoding properties)
   :content-type     (.getContentType properties)
   :correlation-id   (.getCorrelationId properties)
   :delivery-mode    (.getDeliveryMode properties)
   :expiration       (.getExpiration properties)
   :headers          (.getHeaders properties)
   :message-id       (.getMessageId properties)
   :priority         (.getPriority properties)
   :reply-to         (.getReplyTo properties)
   :timestamp        (.getTimestamp properties)
   :type             (.getType properties)
   :user-id          (.getUserId properties)})


(defn- consumer-from-handler
  "Generate a consumer for the given consumable based on the given message-handler.
   `f` will be called on message envelope, properties (both parsed to clojure map)
    and raw body."
  ^Consumer
  [message-handler]
  (reify Consumer
    (handleCancel [_ _])

    (handleCancelOk [_ _])

    (handleConsumeOk [_ _])

    (handleRecoverOk [_ _])

    (handleShutdownSignal [_ _ _])

    (handleDelivery
      [_ consumer-tag envelope properties body]
      (message-handler
        (envelope-to-map envelope)
        (properties-to-map properties)
        body))))


(defn consume
  [channel qname message-handler]
  (let [consumer (consumer-from-handler message-handler)]
    (.basicConsume ^Channel channel qname consumer)))


(defn cancel
  [^Channel channel ^String consumer-tag]
  (.basicCancel channel consumer-tag))


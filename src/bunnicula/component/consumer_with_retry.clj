(ns bunnicula.component.consumer-with-retry
  (:require
    [bunnicula.client.rabbitmq.channel :as channel]
    [bunnicula.client.rabbitmq.consumer :as consumer]
    [bunnicula.consumer :as bc]
    [bunnicula.protocol :as protocol]
    [bunnicula.utils :as utils]
    [clojure.tools.logging :as log]
    [com.stuartsierra.component :as component]))


(defn- retry-queue [qname]
  (format "%s-retry" qname))


(defn- error-queue [qname]
  (format "%s-error" qname))


(defn- retry-requeue [qname]
  (format "%s-retry-requeue" qname))


(defn- get-retry-attemps [properties]
  (get (:headers properties) "retry-attempts" 0))


(defn- ack
  [{:keys [channel envelope]}]
  (channel/ack-message channel (:delivery-tag envelope)))


(defn- to-ms
  [seconds]
  (int (* 1000 seconds)))


(defn- nack
  "Remove message from queue (ACK) and push to exchange
   (will be retry or error exchange).
   Set expiry time on published message if provided
   (used by retries to automatically be reprocessed)!"
  [{:keys [channel body envelope exchange current-retry expiry-time-ms]}]
  (try
    (let [delivery-tag (:delivery-tag envelope)
          publish-options {:persistent true
                           :expiration expiry-time-ms}]
      (log/warnf "nack-message exchange=%s" exchange)
      ;; consumer channel is used for republishing to error/retry exchange
      ;; but since it same thread it is ok
      (channel/publish-message channel {:exchange exchange
                                        :body body
                                        :attempt (inc current-retry)
                                        :options publish-options})
      (channel/ack-message channel delivery-tag))
    ;; in case error happens during publishing
    ;; we don't want to loose message so we nack the message
    ;; which will requeue it!!
    (catch Exception e
      (log/error e)
      (channel/nack-message channel (:delivery-tag envelope)))))


(defn- nack-error
  [{:keys [channel body envelope queue-name retry-attempts]}]
  (nack {:channel channel
         :body body
         :envelope envelope
         :exchange (error-queue queue-name)
         :current-retry retry-attempts}))


(defn- nack-retry
  [{:keys [channel body envelope queue-name retry-attempts expiry-time-ms]}]
  (nack {:channel channel
         :body body
         :envelope envelope
         :exchange (retry-queue queue-name)
         :current-retry retry-attempts
         :expiry-time-ms expiry-time-ms}))


(defn- deserialize
  [deserializer body]
  (try
    {:parsed (deserializer body)}
    (catch Exception err
      {:deserialize-error err})))


(defn- handle-deserialize-error
  [monitoring deserialize-error {:keys [queue-name] :as message}]
  (let [exception (ex-info
                    (format "deserialization error queue=%s" queue-name)
                    {:queue-name queue-name}
                    deserialize-error)]
    (protocol/on-exception monitoring (assoc message :exception exception))
    (nack-error message)))


(defn- handle-failure
  [{:keys [retry-attempts queue-name max-retries] :as message}
   reason]
  (if (> max-retries retry-attempts)
    (do
      (log/warnf "message-failed queue=%s reason=%s retry-attempts=%s"
                 queue-name reason retry-attempts)
      (nack-retry message))
    (do
      (log/errorf "message-failed-to-many-times queue=%s reason=%s"
                  queue-name reason)
      (nack-error message))))


(defn- format-message-data
  [{:keys [channel body envelope properties parsed options]}]
  {:channel channel
   :body body
   :message parsed
   :envelope envelope
   :queue-name (:queue-name options)
   :max-retries (:max-retries options)
   :timeout-ms (to-ms (:timeout-seconds options))
   :expiry-time-ms (to-ms (:backoff-interval-seconds options))
   :properties properties
   :retry-attempts (get-retry-attemps properties)})


(defn- create-message-handler
  "Create message-handler using given channel, queue options
   and provided message-handler which implementes IConsumer protocol.
   Return funtion of 3 arguments - envelope, properties & body
   which will be used by queue consumer.
   1.) call 'on-message' method with time limit
   2.) further processing depends on response value
       - :ack / :bunnicula.consumer/ack : => ack message
       - :error / :bunnicula.consumer/error =>  pushed to error queue
       - :retry / :bunnicula.consumer/retry  =>  retry if allowed otherwise push to error queue
       - :timeout / :bunnicula.consumer/timeout =>  (return if message timeouts) => retry if allowed
         otherwise push to error queue
       In case of exception => retry if allowed otherwise push to error queue"
  [channel consumer]
  (fn [envelope properties body]
    (let [{:keys [options message-handler-fn handler monitoring deserializer]} consumer
          {:keys [parsed deserialize-error]} (deserialize deserializer body)
          ;; pass all message data required for processing in single map
          message (format-message-data {:channel channel
                                        :body body
                                        :envelope envelope
                                        :properties properties
                                        :parsed parsed
                                        :options options})]
      (if deserialize-error
        (handle-deserialize-error monitoring deserialize-error message)
        (try
          (let [res (protocol/with-tracking
                      monitoring
                      #(utils/time-limited
                         (:timeout-ms message)
                         :timeout
                         (message-handler-fn body parsed envelope consumer)))]
            (case res
              (:ack  ::bc/ack)
              (do
                (protocol/on-success monitoring message)
                (ack message))

              (:error ::bc/error)
              (do
                (protocol/on-error monitoring message)
                (nack-error message))

              (:retry ::bc/retry)
              (do
                (protocol/on-retry monitoring message)
                (handle-failure message "retry"))

              (:timeout ::bc/timeout)
              (do
                (protocol/on-timeout monitoring message)
                (handle-failure message "timeout"))))

          (catch Exception e
            (protocol/on-exception monitoring (assoc message :exception e))
            (handle-failure message "exception")))))))


(defn- consume [channel consumer]
  (let [message-handler (create-message-handler channel consumer)
        queue-name (get-in consumer [:options :queue-name])]
    (consumer/consume channel queue-name message-handler)))

; QUEUE PROPERTIES AS PER RABBITMQ DOCUMENTATION
;; Durable (the queue will survive a broker restart)
;; Exclusive (used by only one connection and the queue will be deleted when that connection closes)
;; Auto-delete (queue that has had at least one consumer is deleted when last consumer unsubscribes)
;; Arguments (optional; used by plugins and broker-specific features such as message TTL, queue length limit, etc)

(def ^:private default-queue-options
  {:durable true
   :auto-delete false
   :exclusive false
   :arguments {}})


; ;EXCHANGE PROPERTIES AS PER RABBITMQ DOCUMENTATION
;; Durability (exchanges survive broker restart)
;; Auto-delete (exchange is deleted when last queue is unbound from it)

(defn- declare-and-bind
  [ch queue-name exchange-name]
  (let [queue-name-retry (retry-queue queue-name)
        queue-name-error (error-queue queue-name)
        exch-requeue (retry-requeue queue-name)]
    ;; DECLARE EXCHANGES
    ;; at the moment exchange types are not configurable
    ;; the current configured values are due to historical reasons
    ;; (once you define exchange its type & attributes cannot be changed)!
    ;; we might allow this to be configurable in future version
    (log/infof "declare retry-exchange name=%s" queue-name-retry)
    (channel/declare-exchange ch {:options {:durable false
                                            :auto-delete true}
                                  :name queue-name-retry
                                  :type "fanout"})

    (log/infof "declare error-exchange name=%s" queue-name-error)
    (channel/declare-exchange ch {:options {:durable true
                                            :auto-delete false}
                                  :name queue-name-error
                                  :type "topic"})

    (log/infof "declare requeue-exchange name=%s" exch-requeue)
    (channel/declare-exchange ch {:options {:durable false
                                            :auto-delete true}
                                  :name exch-requeue
                                  :type "fanout"})

    ;; DECLARE QUEUES
    (log/infof "declare queue name=%s" queue-name)
    (channel/declare-queue ch {:options default-queue-options
                               :name queue-name})

    (log/infof "declare retry-queue name=%s" queue-name-retry)
    (channel/declare-queue ch {:options (assoc default-queue-options
                                          ;; setup DLE for retry queue
                                               :arguments {"x-dead-letter-exchange" exch-requeue})
                               :name queue-name-retry})

    (log/infof "declare error-queue name=%s" queue-name-error)
    (channel/declare-queue ch {:options default-queue-options
                               :name queue-name-error})

    ;; BIND QUEUES
    ;; bind main queue to configured exchange, routing-key is name of the queue
    (channel/bind-queue ch {:queue queue-name
                            :exchange exchange-name
                            :routing-key queue-name})
    ;; bind main queue to requeue-exchange, using default routing key #
    (channel/bind-queue ch {:queue queue-name
                            :exchange exch-requeue})
    ;; bind error queue to error-exchange, using default routing key #
    (channel/bind-queue ch {:queue queue-name-error
                            :exchange queue-name-error})
    ;; bind retry queue to retry-exchange, using default routing key #
    (channel/bind-queue ch {:queue queue-name-retry
                            :exchange queue-name-retry})))

;; ========= Component =========

(defrecord RetryConsumer [options monitoring rmq-connection
                          channel consumer-channels consumer-tags]
  component/Lifecycle
  (start [this]
    (log/infof "retry-consumer start name=%s" (:queue-name options))
    (when-not (and monitoring rmq-connection)
      (throw (ex-info "monitoring and rmq-connection are required compoenents" {})))
    (if consumer-channels
      this
      (let [connection (:connection rmq-connection)
            {:keys [prefetch-count consumer-threads queue-name exchange-name]} options
            ;; channel used for declaring queues
            ch (channel/create connection)
            _ (declare-and-bind ch queue-name exchange-name)
            ;; channels used for consuming messages (not shared)
            consumer-channels (repeatedly consumer-threads
                                          #(channel/create connection prefetch-count))
            consumer-tags (mapv
                            #(consume % this)
                            consumer-channels)]
        (assoc this
               :consumer-channels consumer-channels
               :consumer-tags consumer-tags
               :channel ch))))

  (stop [this]
    (log/infof "retry-consumer stop name=%s" (:queue-name options))
    ;; ensure all things are stopped
    (mapv (partial consumer/cancel) consumer-channels consumer-tags)
    (mapv channel/close consumer-channels)
    (when channel
      (channel/close channel))
    ;; reset consumer
    (assoc this
           :channel nil
           :consumer-tags nil
           :consumer-channels nil)))

;; ======= setup function ===========
(def default-options
  {:max-retries 3
   :backoff-interval-seconds 60
   :timeout-seconds 60
   :prefetch-count 10
   :consumer-threads 4})


(def allowed-options-keys
  (conj (set (keys default-options)) :exchange-name :queue-name))


(defn- set-defaults [options]
  (merge default-options options))


(defn create
  [{:keys [options message-handler-fn handler deserializer]}]
  {:pre [(or (fn? handler) (fn? message-handler-fn))
         ;; ensure required keys for options are present
         (string? (:queue-name options))
         (string? (:exchange-name options))
         ;; ensure no invalid key is passed in options (avoid typos in config etc.)
         (every? #(contains? allowed-options-keys %) (keys options))]}
  (map->RetryConsumer
    {:message-handler-fn (or handler message-handler-fn)
     :deserializer (or deserializer utils/json-deserializer)
     :options (set-defaults options)}))

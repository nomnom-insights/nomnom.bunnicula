(ns bunnicula.client.rabbitmq.channel
  (:import (com.rabbitmq.client Channel Connection AMQP$BasicProperties$Builder)))

(def default-routing-key "#")

(defn open? [channel]
  (and channel (.isOpen ^Channel channel)))

(defn create
  ([connection]
   (create connection nil))
  ([connection prefetch-count]
   (let [ch (.createChannel ^Connection connection)]
     (when prefetch-count
       (.basicQos ^Channel ch (int prefetch-count)))
     ch)))

(defn close [^Channel channel]
  (if (.isOpen ^Channel channel)
    (.close ^Channel channel)
    channel))

(defn- publish-properties
  [{:keys [attempt persistent expiration]}]
  (let [basic (-> (AMQP$BasicProperties$Builder.)
                  (.deliveryMode (Integer/valueOf (if persistent 2 1))))
        prop  (cond-> basic
                attempt (.headers {"retry-attempts" attempt})
                expiration (.expiration (str expiration)))]
    (.build prop)))

(defn publish-message
  "Publish message bytes using provided channel.
   Publish to given exchange & routing-key.
   Options
   - mandatory (flag to ensure message delivery)
   - persistent? (flag to write message to disk)
   - attempt (set 'retry-attempts' header in message)
   - expiration (set message expiration)"
  [^Channel channel 
   {:keys [exchange routing-key body attempt options]}]
  (let [{:keys [mandatory immediate expiration persistent]
         :or {mandatory false immediate false persistent true}} options
        properties (publish-properties {:expiration expiration
                                        :attempt attempt
                                        :persistent persistent})]
    (.basicPublish
      channel
      exchange
      (or routing-key default-routing-key)
      mandatory
      immediate
      properties
      body)))

(defn ack-message
  "ACK the given message."
  [^Channel channel delivery-tag]
  (.basicAck channel delivery-tag false))

(defn nack-message
  "NACK the given message (message is requeued)!"
  [^Channel channel delivery-tag]
  (.basicNack channel delivery-tag false true))

(defn declare-exchange
  [^Channel channel {:keys [options name type]}]
  (let [{:keys [auto-delete durable internal]} options]
    (.exchangeDeclare
      channel
      ^String name
      ^String type
      (boolean durable)
      (boolean auto-delete)
      (boolean internal)
      nil)))

(defn declare-queue
  [^Channel channel {:keys [name options]}]
  (let [{:keys [auto-delete durable exclusive arguments]} options]
    (.queueDeclare
      channel
      name
      (boolean durable)
      (boolean exclusive)
      (boolean auto-delete)
      arguments)))

(defn bind-queue
  "Bind queue to exchange
   - skip for default exchange ''"
  [channel {:keys [queue exchange routing-key]}]
  (when-not (empty? exchange)
    (.queueBind channel queue exchange (or routing-key default-routing-key) nil)))
# Bunnicula components

<!-- markdown-toc start - Don't edit this section. Run M-x markdown-toc-refresh-toc -->
**Table of Contents**

- [Bunnicula components](#bunnicula-components)
    - [RabbitMQ implementation details](#rabbitmq-implementation-details)
        - [RabbitMQ best practices](#rabbitmq-best-practices)
        - [Automatic recovery](#automatic-recovery)
        - [Durability](#durability)
    - [Connection component <a name="connection-component"></a>](#connection-component-a-nameconnection-componenta)
        - [Configuration](#configuration)
        - [Usage](#usage)
    - [Publisher component <a name="publisher-component"></a>](#publisher-component-a-namepublisher-componenta)
        - [Configuration](#configuration-1)
        - [Publish method](#publish-method)
            - [options](#options)
        - [Usage](#usage-1)
        - [Mock publisher](#mock-publisher)
    - [Consumer with retry component <a name="consumer-component"></a>](#consumer-with-retry-component-a-nameconsumer-componenta)
        - [Message flow](#message-flow)
        - [Exchanges and Queues](#exchanges-and-queues)
        - [Component dependencies](#component-dependencies)
        - [Configuration](#configuration-2)
            - [message-handler-fn <a name="handler-fn"></a>](#message-handler-fn-a-namehandler-fna)
        - [Usage](#usage-2)
        - [Monitoring for consumer<a name="monitoring"></a>](#monitoring-for-consumera-namemonitoringa)
    - [Base monitoring component <a name="base-monitoring-component"></a>](#base-monitoring-component-a-namebase-monitoring-componenta)
        - [Example custom monitoring component](#example-custom-monitoring-component)

<!-- markdown-toc end -->


Buniccula is framework for asynchronous messaging with RabbitMQ.

It defines 4 components (based on [Stuart Sierra's component lib](https://github.com/stuartsierra/component))

- connection
- publisher
- consumer
- base monitoring

## RabbitMQ implementation details

### RabbitMQ best practices

Bunnicula follows the RabbitMQ best practices, inspired by following blog posts.
- [mike hadlow RabbitMQ best practices](http://mikehadlow.blogspot.com/2013/09/rabbitmq-amqp-channel-best-practices.html)
- [cloudamqp RabbitMQ best practices](https://www.cloudamqp.com/blog/2017-12-29-part1-rabbitmq-best-practice.html)

See the relevant practices implemented in Bunnicula

- Channels and connections are opened/closed only at application startup time
(or during auto-recovery)
- Publisher component maintains long running channel
- Channels are not shared between threads (as channels are not thread safe)
- Consuming channel is not used for publishing
- Consumers don't have an unlimited prefetch value (default value is set to 10)
- An ACK is invoked on the same channel on which the delivery was received

### Automatic recovery

Bunnicula uses official RabbitMQ Java client for creating connections, consumers etc.
As of version 4.0.0 of the Java client, automatic recovery is enabled by default.
 See more details [here](https://www.rabbitmq.com/api-guide.html#recovery)


### Durability

By default we ensure your data will survive server restart.

All queues defined by consumer component are durable.
Publisher component publishes persistent message by default.

## Connection component <a name="connection-component"></a>
Connection component represents RabbitMQ connection.
When component is started, new RabbitMQ connection is opened.
When component is closed, connection is closed!

Connection component is used by publisher & consumers as component dependency!

### Configuration
- server params can be specified either via map full `url` key
or by map with `host`, `port`, `username` and `password` keys
- `vhost` is always required to be present in configuration
- optionally user can specify `connection-name`

### Usage
```clojure
(require '[bunnicula.component.connection :as connection]
         '[com.stuartsierra.component :as component])

(def connection (connection/create {:username "rabbit"
                                    :password "password"
                                    :host "127.0.0.1"
                                    :port 5672
                                    :vhost "/main"
                                    :connection-name "connection-test"}))

;; connection specified by url
;; (def connection (connection/create {:url "amqp://rabbit:password@127.0.0.1:5672"
;;                                     :vhost "/main"}))

(component/start connection)
```

<img src="images/rmq-connection.png" height="200px" />

## Publisher component <a name="publisher-component"></a>
Publisher component is used for publishing messages to the broker.
When component is started, new channel is opened.
When component is stopped, channel is closed.

The [connection component](#connection-component-) is a required dependency, it has to be present under the rmq-connection key in the system map.

### Configuration
- `exchange-name` the default exchange messages will be published to
- `serialization-fn` (optional) function used to serialize message (represented as clojure data)
 to RabbitMQ message body (bytes). Default function uses json-serialization.

### Publish method
Publisher component implements publish method, which has multiple arity

- `(publish publisher routing-key message)` will publish message to default exchange
with given routing-key
- `(publish publisher routing-key message options)` will publish message to default exchange
with given routing-key and options
- `(publish publisher exchange routing-key message options)` will publish message to specified exchange
with given routing-key and options

#### options

Following options are supported for publishing

- `mandatory` flag to ensure message delivery, default false
- `expiration` set message expiration
- `persistent` whether to persist message on disk, default true!

### Usage
```clojure
(require '[bunnicula.component.connection :as connection]
         '[bunnicula.component.publisher :as publisher]
         '[bunnicula.protocol :as protocol]
         '[com.stuartsierra.component :as component])

(def connection (connection/create {:url "amqp://rabbit:password@127.0.0.1:5672"
                                    :vhost "/main"}))

(def publisher (publisher/create {:exchange-name "my-exchange"}))

(def system (-> (component/system-map
                  :publisher (component/using
                               publisher
                               [:rmq-connection])
                  :rmq-connection connection)
                component/start-system))

;; publish to 'my-exchange' exchange with routing-key 'some.queue'
(protocol/publish (:publisher system)
                  "some.queue"
                  {:integration_id 1 :message_id "123"})

;; publish to 'another-exchange' exchange with routing-key 'some.queue' and options map
(protocol/publish (:publisher system)
                  "another-exchange"
                  "some.queue"
                  {:integration_id 1 :message_id "123"}
                  {:mandatory true :persistent true})
```

### Mock publisher

For testing purposes or in development mode you can use the Mock Component.
Mock publisher component contains `queues` atom which represents the RabbitMQ.
It is initiated as empty map and any time you publish message,
its value is added to `queues` atom

```clojure
(require '[bunnicula.component.publisher.mock :as mock]
         '[bunnicula.protocol :as protocol]
         '[com.stuartsierra.component :as component])

(def p (-> (mock/create) component/start))

(protocol/publish p
                  "some.queue"
                  {:integration_id 1 :message_id "123"})

 (protocol/publish p
                   "some.queue"
                   {:integration_id 1 :message_id "456"})

(-> p :queues deref)
;; => {"some.queue" [{:integration_id 1, :message_id "456"}
;;                   {:integration_id 1, :message_id "123"}]}
```

## Consumer with retry component <a name="consumer-component"></a>

Defines consumer with auto retry functionality!

The component is composed of a message handler (a plain Clojure function), consumer threads and channels.
When processing fails it can be retried automatically with a fixed amount of times with a delay between each attempt.
If processing still fails - messages will be pushed to an error queue for further inspection and manual retrying.

When the component starts it will create necessary exchanges and queues if they do not exist.

When component stops consumers are destroyed and channels are closed.
All exchanges, queues and their messages will remain intact.
Messages will be fetched again when the consumer reconnects.

### Message flow

Processing message on consumer can result in one of following results

1. **success** => ACK message on original queue
2. **hard failure** => ACK message on original queue and push message to error queue
3. **recoverable failure** => ACK message on original queue and push message to retry queue
to be processed later

<img src="images/message-flow.png" width="800" />

### Exchanges and Queues

Assume configured queue-name is `some.queue`.
Following exchanges are declared when component is started
- **retry exchange** with name `some.queue-retry`
- **error exchange** with name `some.queue-error`
- **dead letter exchange** with name `some.queue-requeue`

Following queues are declared when component is started

- **main queue** with name `some.queue`  which is bind to pre-configured exchange via 'some.queue' routing key (routing-key=queue-name)
- **retry queue** with name `some.queue-retry` which is bind to retry exchange via '#' routing key,
expired messages are sent to dead letter exchange `some.queue-requeue`
- **error queue** with name `some.queue-error` which is bind to error exchange via '#' routing key

Note all queues are durable and publishing messages between queues ensure they are persisted on disk.

> ⚠️  Main exchange used by regular work queues (not retry/error) has to be created before starting the consumer.
 Only retry and error exchanges are created by the consumer component.

<p float="left">
  <img src="images/rmq-exchanges.png" width="400" />
  <img src="images/rmq-queues.png" width="400" />
</p>


### Component dependencies
The [connection component](#connection-component-) and [monitoring component](#base-monitoring-component-) are required dependencies,
 it has to be present under `:rmq-connection` and `:monitoring` key in the system map.


### Configuration


- `message-handler-fn` handler fn for consumer, holds the application logic,
see more [here](#handler-fn-)
- `deserializer` (optional, default is json deserialization)
function to be used to deserializer messages
- `options`
  - `queue-name` queue for consuming messages
  - `exchange-name` exchange which queue will be bind to using queue-name as routing-key
 (exchange needs to be already created, you can use default RabbitMQ exchange '')
  - `max-retries` (optional, default 3) how many times should be message retried
  - `timeout-seconds` (optional, default 60s)  timeout for message-handler-fn
  - `backoff-interval-seconds` (optional, default 60s) how long should message wait on retry queue
  - `consumer-threads` (optional, default 4) how many consumers should be created, allows parallel processing
  - `prefetch-count` (optional, default 10) how many messages should be prefetched by each consumer

#### message-handler-fn <a name="handler-fn"></a>

handler-fn takes 4 arguments

- `body` raw message data, as received by the consumer - usally `byte[]`
- `parsed` parsed message, parsing is defined by the deserializer function - by default it's JSON. This is the payload used by the handler
- `envelope` message envelope
- `components` - components which are specified as dependencies for the consumer

handler-fn is required to return one of following values

- `:ack -` message was processed successfully
- `:retry` - recoverable failure => retry message automatically
- `:error` - hard failure => no retry, send to dead-letter queue
- `:timeout` - consumption timed out, will be retried but also logs/records timeout specific metrics

Note that you can use namespaced versions of these keywords:

- `:bunnicula.consumer/ack`
- `:bunnicula.consumer/error`

and so on.

``` clojure
(defn handler-fn [body parsed envelope components]
 ;; ... some domain specific code ...
 ;; return supported response value
 :ack)
```

The envelope is a map of:

```clojure
{:routing-key "QUEUE-NAME",
:exchange "",
:redelivered? false,
:delivery-tag 1}
```

(see the [JavaDoc](https://www.rabbitmq.com/releases/rabbitmq-java-client/v2.4.1/rabbitmq-java-client-javadoc-2.4.1/index.html?com/rabbitmq/client/Envelope.html) for details)


### Usage

```clojure
(require '[bunnicula.component.connection :as connection]
         '[bunnicula.component.monitoring :as monitoring]
         '[bunnicula.component.consumer-with-retry :as consumer]
         '[com.stuartsierra.component :as component])

(defn import-conversation-handler
  [body parsed envelope components]
  (let [{:keys [integration_id message_id]} parsed]
    ;; ... import intercom conversation for given integration_id & message_id ...
    ;; need to return :ack, :error, :retry
    :ack))

(def connection (connection/create {:url "amqp://rabbit:password@127.0.0.1:5672"
                                    :vhost "/main"}))

(def consumer (consumer/create {:message-handler-fn import-conversation-handler
                                :options {:queue-name "some.queue"
                                          :exchange-name "my-exchange"
                                          :timeout-seconds 120
                                          :backoff-interval-seconds 60
                                          :consumer-threads 4
                                          :max-retries 3}}))

(def system (-> (component/system-map
                  :rmq-connection connection
                  :monitoring monitoring/BaseMonitoring
                  :consumer (component/using
                              consumer
                              [:rmq-connection :monitoring]))
                component/start-system))
```

### Monitoring for consumer<a name="monitoring"></a>

Monitoring component is a required dependency for the consumer component
 (it has to be present under the monitoring key in the system map.)

Bunnicula provides a basic [monitoring component](#base-monitoring-component-).
If you require more advanced monitoring functionality you can also implement your own.

The component needs to implement all methods from [Monitoring protocol](../src/bunnicula/protocol.clj)
and support [component lifecycle](https://github.com/stuartsierra/component#creating-components)

You can also use [bunnicula.monitoring component](https://github.com/nomnom-insights/nomnom.bunnicula.monitoring),
which will track consumer metrics and send those to StatsD and report exceptions to Rollbar.

## Base monitoring component <a name="base-monitoring-component"></a>

Provides basic monitoring functionality for [consumer component](#consumer-component)

It logs the result of consumer's `message-handler-fn` using `clojure.tools.logging`.

### Example custom monitoring component

You can completely override metrics and error reporting backends and call their APIs directly:

```clojure
(ns bunnicula.monitoring.custom
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [bunnicula.protocol :as protocol]
            [xyz.component.raygun :as raygun]
            [xyz.component.graphite :as graphite]))

(defrecord CustomMonitoring [consumer-name raygun graphite]
  component/Lifecycle
    (start [c]
    (log/infof "start consumer-name=%s" consumer-name)
      c)
  (stop [c]
    (log/infof "stop consumer-name=%s" consumer-name)
    c)
  protocol/Monitoring
  (on-success [this args]
    (log/infof "consumer=%s success" consumer-name)
    (graphite/count graphite :success consumer-name))
  (on-error [this args]
    (log/errorf "consumer=%s error payload=%s"
                consumer-name (log-fn (:message args)))
    (graphite/count graphite :error consumer-name))
  (on-timeout [this args]
    (log/errorf "consumer=%s timeout payload=%s"
                consumer-name (log-fn (:message args)))
    (graphite/count graphite :timeout consumer-name))
  (on-retry [this args]
    (log/errorf "consumer=%s retry-attempts=%d payload=%s"
                consumer-name (:retry-attempts args) (log-fn (:message args)))
    (graphite/count graphite :retry consumer-name))
  (on-exception [this args]
    (let [{:keys [exception message]} args]
      (log/errorf exception "consumer=%s exception payload=%s"
                  consumer-name (log-fn message))
      (when exception-tracker
        (tracker/report raygun exception)))
    (graphite/count graphite :fail consumer-name)))
```

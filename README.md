# bunnicula

<img src="http://www.gstatic.com/tv/thumb/tvbanners/12567255/p12567255_b_v8_aa.jpg" align="right"  height="200px" />

A Clojure RabbitMQ client.

[![CircleCI](https://circleci.com/gh/nomnom-insights/nomnom.bunnicula.svg?style=svg)](https://circleci.com/gh/nomnom-insights/nomnom.bunnicula)


## Installation

**Leiningen** (via [Clojars](https://clojars.org/nomnom/bunnicula))

[![](https://img.shields.io/clojars/v/nomnom/bunnicula.svg)](https://clojars.org/nomnom/bunnicula)


## Usage


<!-- markdown-toc start - Don't edit this section. Run M-x markdown-toc-refresh-toc -->
**Table of Contents**

- [bunnicula](#bunnicula)
    - [Installation](#installation)
    - [Usage](#usage)
    - [RabbitMQ implementation details](#rabbitmq-implementation-details)
        - [-](#-)
        - [Automatic recovery](#automatic-recovery)
        - [Durability](#durability)
- [Components](#components)
    - [Connection component <a name="connection-component"></a>](#connection-component-a-nameconnection-componenta)
        - [Configuration](#configuration)
        - [Usage](#usage-1)
        - [Mock publisher](#mock-publisher)
    - [Consumer component <a name="consumer-component"></a>](#consumer-component-a-nameconsumer-componenta)
        - [Message flow](#message-flow)
        - [Exchanges and Queues](#exchanges-and-queues)
        - [Component dependencies](#component-dependencies)
        - [Configuration](#configuration-1)
            - [handler <a name="handler-fn"></a>](#handler-a-namehandler-fna)
        - [Usage](#usage-2)
        - [Monitoring for consumer<a name="monitoring"></a>](#monitoring-for-consumera-namemonitoringa)
    - [Base monitoring component <a name="base-monitoring-component"></a>](#base-monitoring-component-a-namebase-monitoring-componenta)
        - [Example custom monitoring component](#example-custom-monitoring-component)
- [Full example](#full-example)
- [Release notes](#release-notes)
    - [v2.2.0-SNAPSHOT](#v220-snapshot)
    - [v2.1.0 (18.4.2019)](#v210-1842019)
    - [v2.0.2 (15.9.2018)](#v202-1592018)

<!-- markdown-toc end -->


Bunnicula is framework for building asynchronous workflows with RabbitMQ.

It defines 4 components (based on [Stuart Sierra's component lib](https://github.com/stuartsierra/component))

- connection - representing a per-service instance RabbitMQ connection
- publisher - for publishing jobs to queues, usually 1 per service instance
- consumer - for consuming jobs from a queue, a service usually defines multiple ones, consuming jobs from multiple queues. Consumers can also run concurrently, e.g. spin up N consumer threads to pull jobs of the queue
- monitoring - a consumer monitoring component which reports all aspects of the consumer lifecycle (in basic form, it will log all consumer operations)

## RabbitMQ implementation details

#### RabbitMQ best practices

Bunnicula follows the RabbitMQ best practices, inspired by following blog posts.
- [Mike Hadlow's RabbitMQ best practices](http://mikehadlow.blogspot.com/2013/09/rabbitmq-amqp-channel-best-practices.html)
- [CloudAMQP RabbitMQ best practices](https://www.cloudamqp.com/blog/2017-12-29-part1-rabbitmq-best-practice.html)

Relevant practices and patterns implemented in Bunnicula:

- Channels and connections are opened/closed only at application startup time
(or during auto-recovery)
- Publisher component maintains long running channel
- Channels are not shared between threads (as channels are not thread safe)
- Consuming channel is not used for publishing
- Consumers don't have an unlimited prefetch value (default value is set to 10)
- An ACK is invoked on the same channel on which the delivery was received
- All messages are persistent, all queues are durable

#### Automatic recovery

Bunnicula uses official RabbitMQ Java client for creating connections, consumers etc.
As of version 4.0.0 of the Java client, automatic recovery is enabled by default.
 See more details [here](https://www.rabbitmq.com/api-guide.html#recovery)


#### Durability

By default we ensure your queue data will survive RabbitMQ server restart.

All queues defined by consumer component are durable.
Publisher component publishes persistent message by default.

# Components

## Connection component <a name="connection-component"></a>

Connection component represents RabbitMQ connection.
When component is started, new RabbitMQ connection is opened.
When component is stopped, connection is closed!

Connection component is **a required dependency** by the publisher & consumer components, under the `rmq-connection` key in your system.


### Configuration

- server params can be specified either via map full `url` key
or by map with `host`, `port`, `username` and `password` keys
- `vhost` is always required to be present in configuration, default is "/"
- optionally user can specify `connection-name` - this is a recommended setting if you have multiple applications connecting to the same RabbitMQ server
- optionally you can set `secure?` flag to enable connecting to RabbitMQ server with TLS/SSL enabled

### Usage


```clojure
(require '[bunnicula.component.connection :as connection]
         '[com.stuartsierra.component :as component])

(def connection (connection/create {:username "rabbit"
                                    :password "password"
                                    :host "127.0.0.1"
                                    :port 5672
                                    :vhost "/main"
                                    :secure? true
                                    :connection-name "connection-test"}))

;; connection specified by url
;; (def connection (connection/create {:url "amqp://rabbit:password@127.0.0.1:5672"
;;                                     :vhost "/main"}))

(component/start connection)

```


<img src="doc/images/rmq-connection.png" height="200px" />

## Publisher component <a name="publisher-component"></a>

Publisher component is used for publishing messages to the broker.
When component is started, new channel is opened.
When component is stopped, channel is closed.

The [connection component](#connection-component-) is a required dependency, it has to be present under the `rmq-connection` key in the system map.


### Configuration

- `exchange-name` the default exchange messages will be published to
- `serializer` (optional) function used to serialize messages to RabbitMQ message body (bytes). Default function uses json-serialization. See `bunnicula.utils/json-serializer` for an example.

### Publishing

Publisher component implements publish method, which has multiple arity

- `(publish publisher routing-key message)` will publish message to default exchange
with given routing-key (queue name)
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

## Consumer component <a name="consumer-component"></a>

Defines consumer with auto retry functionality!

The component is composed of a message handler (a Clojure function), consumer threads and channels.
When processing fails it can be retried automatically with a fixed amount of times with a delay between each attempt.
If processing still fails - messages will be pushed to an error queue for further inspection and manual retrying (dead lettering).

When the component starts it will create necessary exchanges and queues if they do not exist.

When component stops consumers are destroyed and channels are closed.
All exchanges, queues and their messages will remain intact.
Messages will be fetched again when the consumer reconnects as per the pre-fetch setting.

### Message flow

Processing message on consumer can result in one of following results

1. **success** => ACK message on original queue
2. **hard failure** => ACK message on original queue and push message to error queue
3. **recoverable failure** => ACK message on original queue and push message to retry queue to be processed later

<img src="doc/images/message-flow.png" width="800" />

### Exchanges and Queues


> :warning: If using non-default exchange, the main exchange used by regular work queues (not retry/error) has to be created before starting the consumer.
>  Only retry and error exchanges are created by the consumer component.

Assume configured queue-name is `some.queue`.
Following exchanges are declared when component is started
- **retry exchange** with name `some.queue-retry`
- **error exchange** with name `some.queue-error`
- **dead letter exchange** with name `some.queue-requeue`

Following queues are declared when component is started

- **main queue** with name `some.queue`  which is bound to the pre-configured exchange via 'some.queue' routing key (routing-key is usually the queue name)
- **retry queue** with name `some.queue-retry` which is bound to retry exchange via '#' routing key,
expired messages are sent to dead letter exchange `some.queue-requeue`
- **error queue** with name `some.queue-error` which is bound to error exchange via '#' routing key

Note all queues are durable and publishing messages between queues ensures they are persisted on disk. This also means that all messages will survive the server and consumer restarts.

> :warning: You have to take care of garbage collecting failed messages - either reprocessing them again by moving the messages back to the main queue by using the RabbitMQ shovel plugin or purging the queue. Accumulating failed messages will offload them to the disk, but will eventually bloat RabbitMQ's memory!



<p float="left">
  <img src="doc/images/rmq-exchanges.png" width="400" />
  <img src="doc/images/rmq-queues.png" width="400" />
</p>


### Component dependencies

The [connection component](#connection-component-) and [monitoring component](#base-monitoring-component-) are required dependencies. Both have to be present under `:rmq-connection` and `:monitoring` key in the component dependency list. You can use aliases to avoid name clashes.


### Configuration


- `handler` handler fn for consumer, holds the application logic,
see more [here](#handler-fn-)
- `deserializer` (optional, default is json deserialization)
function to be used to deserializer messages
- `options`
  - `queue-name` queue for consuming messages
  - `exchange-name` exchange which queue will be bind to using queue-name as routing-key (exchange needs to be already created, you can use default RabbitMQ exchange `""`)
  - `max-retries` (optional, default 3) how many times should be message retried
  - `timeout-seconds` (optional, default 60s)  timeout for handler, if handler execution exceeds the timeout it will be terminated and treated as a retry
  - `backoff-interval-seconds` (optional, default 60s) how long should message wait on retry queue
  - `consumer-threads` (optional, default 4) how many consumers should be created, allows parallel processing
  - `prefetch-count` (optional, default 10) how many messages should be prefetched by each consumer

#### handler <a name="handler-fn"></a>

handler function takes 4 arguments

- `raw-body` raw message data, as received by the consumer - always sent as `bytes` (a byte array)
- `deserialized-body` parsed message, parsing is defined by the deserializer function - by default it's JSON. This is the payload used by the handler
- `envelope` message envelope
- `components` - components which are specified as dependencies for the consumer

handler-fn is required to return one of following values

- `:bunnicula.consumer/ack -` message was processed successfully
- `:bunnicula.consumer/retry` - recoverable failure => retry message automatically
- `:bunnicula.consumer/error` - hard failure => no retry, send to dead-letter queue
- `:bunnicula.consumer/timeout` - consumption timed out, will be retried but also logs/records timeout specific metrics

Note that you can use non-namespaced versions of these keywords  - these will be **the removed in v3**.

- `:ack`
- `:retry`
- `:error`
- `:timeout`


> :warning: If it returns *anything else than the keywords listed above* it will be considered a failure and the job will be retried!


A simpler handler looks like this:

``` clojure
(defn handler-fn [raw-body deserialized-body envelope components]
 ;; ... some domain specific code ...
 ;; return supported response value
 :bunnicula.consumer/ack)
```

The envelope is a map of:

```clojure
{:routing-key "QUEUE-NAME",
:exchange "",
:redelivered? false,
:delivery-tag 1}
```

(see the [JavaDoc](https://www.rabbitmq.com/releases/rabbitmq-java-client/v2.4.1/rabbitmq-java-client-javadoc-2.4.1/index.html?com/rabbitmq/client/Envelope.html) for details)

In typical scenario you can ignore the raw body and envelope arguments, as focus on the business logic.
Example handler for sending email notifications:


```clojure

(defn send-email [_ {:keys [to subject content]}
                  _ {:keys [email-sender]}]
  (email/send email-sender {:to to
                            :subject subject
                            :html content
                            :plain-text (util/make-plain-test content)})
  :bunnicula.consumer/ack)
```


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
    ;; need to return :bunnicula.consumer/ack, :bunnicula.consumer/error, :bunnicula.consumer/retry
    :bunnicula.consumer/ack))

(def connection (connection/create {:url "amqp://rabbit:password@127.0.0.1:5672"
                                    :vhost "/main"}))

(def consumer (consumer/create {:handler import-conversation-handler
                                :options {:queue-name "some.queue"
                                          :exchange-name "my-exchange"
                                          :timeout-seconds 120
                                          :backoff-interval-seconds 60
                                          :consumer-threads 4
                                          :max-retries 3}}))

(def system (-> (component/system-map
                  :rmq-connection connection
                  :publisher (component/using
                               (publisher/create)
                               [:rmq-connection])
                  :monitoring (monitoring/create)
                  :consumer (component/using
                              consumer
                              [:rmq-connection :monitoring]))
                component/start-system))

;; publish
(bunnicula.protocol/publish (:publisher system) "some.queue" {:integration_id 123 :message_id 2334})
```

### Monitoring for consumer<a name="monitoring"></a>

Monitoring component is a required dependency for the consumer component
 (it has to be present under the monitoring key in the system map.)

Bunnicula provides a basic [monitoring component](#base-monitoring-component-).
If you require more advanced monitoring functionality you can also implement your own.

The component needs to implement all methods from [Monitoring protocol](src/bunnicula/protocol.clj)
and support [component lifecycle](https://github.com/stuartsierra/component#creating-components)

You can also use a production-ready [bunnicula.monitoring component](https://github.com/nomnom-insights/nomnom.bunnicula.monitoring),
which will track consumer metrics and send those to StatsD and report exceptions to [Rollbar](https://rollbar.com).

## Base monitoring component <a name="base-monitoring-component"></a>

Provides basic monitoring functionality for [consumer component](#consumer-component)

It logs the result of consumer's `handler` using `clojure.tools.logging`.

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

# Full example

See a [full example of a component system with a publisher, monitoring and a consumer](doc/example.clj).

# Release notes

## v2.2.1

- support for `amqps://` scheme and `secure?` flag to allow connecting to RabbitMQ servers with TLS enabled

## v2.2.0

- more flexible configuration, with somewhat better option names
- updated dependencies
- support for namespaced keywords as return values of consumer handlers
- fixed reflection warnings
- documentation improvements
- reformatted & linted code

## v2.1.0 (18.4.2019)

- update all dependencies
- make it work with Clojure 1.10
- *potentially breaking change* consumer config is now more strict and will throw exceptions if invalid configuration is passed

## v2.0.2 (15.9.2018)

- Open source nomnom/bunnicula library

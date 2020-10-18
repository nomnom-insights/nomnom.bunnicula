(ns bunnicula.component.connection
  "Component holds open connection to RMQ"
  (:require
    [bunnicula.client.rabbitmq.connection :as connection]
    [clojure.string :as string]
    [clojure.tools.logging :as log]
    [com.stuartsierra.component :as component])
  (:import
    (java.net
      URI)))


(defn- connection-url
  [{:keys [host port username password vhost]}]
  (format "amqp://%s:%s@%s:%s/%s"
          username password host port (string/replace vhost "/" "%2F")))


(defrecord Connection [host port username password vhost connection-name  connection]
  component/Lifecycle
  (start [this]
    (if connection
      this
      (let [url (connection-url {:host host
                                 :port port
                                 :username username
                                 :password password
                                 :vhost vhost})
            conn (connection/create url connection-name)]
        (log/infof "rmq-connection start name=%s vhost=%s" connection-name vhost)
        (assoc this :connection conn))))
  (stop [this]
    (log/infof "rmq-connection stop name=%s" connection-name)
    (when connection
      (connection/close connection))
    (assoc this :connection nil)))


(defn extract-server-config
  [{:keys [url host port username password vhost connection-name]}]
  {:post [#(string? (:host %))
          #(string? (:port %))
          #(string? (:username %))
          #(string? (:password %))
          #(string? (:vhost %))]}
  (if-let [^java.net.URI uri (and url (java.net.URI. url))]
    (let [[username password] (string/split (.getUserInfo uri) #":")]
      {:host (.getHost uri)
       :port (.getPort uri)
       :username username
       :password password
       :connection-name (or connection-name username)
       :vhost vhost})
    {:host host
     :port port
     :username username
     :password password
     :connection-name (or connection-name username)
     :vhost vhost}))


(defn create
  "Create RabbitMQ connection for given connection config.
   Config can either contain ':url' key with full url
   or can be map with username, password, host and port values.
   Config always need to contain vhost!
   - {:url 'amqp://user:password@localhost:5492' :vhost 'main'}
   - {:username 'user' :password 'password' :host 'localhost' :port '5492':vhost 'main'}
   Optional config params
   - :connection-name
     - if not specified username is used as connection-name
     - {:url 'amqp://user:password@localhost:5492' :vhost 'main' :connection-name 'conn1'}"
  [config]
  (map->Connection (extract-server-config config)))

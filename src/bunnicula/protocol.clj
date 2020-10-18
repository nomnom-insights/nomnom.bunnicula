(ns bunnicula.protocol)


(defprotocol Monitoring
  (with-tracking [this args]
    "Should evaluate body. Can add time tracking etc.")
  (on-success [this args]
    "Called when message is succesfully processed")
  (on-timeout [this args]
    "Called when messsage processing timeouts.")
  (on-retry [this args]
    "Called when consumer fails because of known behviour
    and we want to retry.")
  (on-error [this args]
    "Called when consumer fails because of known behavior
    and we error out ourselves.")
  (on-exception [this args]
    "Called when consumer fails because of an uncontrollable error
     (e.g. network, bug), basically an exception"))


(defprotocol Publisher
  (publish
    [this routing-key body]
    [this routing-key body options]
    [this exchange-name routing-key body options]
    "Publish body to RabbitMQ using exchange & routing-key"))

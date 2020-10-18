(ns bunnicula.component.monitoring
  (:require
    [bunnicula.protocol :as protocol]
    [clojure.tools.logging :as log]))


(defn- trim-message
  ([message]
   (trim-message message 100))
  ([message size]
   (let [s (str message)]
     (subs s 0 (min size (count s))))))


(def BaseMonitoring
  (reify
    protocol/Monitoring
    (with-tracking [this message-fn]
      ;; just evaluate fn
      (message-fn))
    (on-success [this args]
      (log/infof "consumer=%s succeeded" (:queue-name args)))
    (on-error [this args]
      (log/errorf "consumer=%s error message=%s"
                  (:queue-name args) (trim-message (:message args))))
    (on-exception [this args]
      (log/errorf (:exception args) "consumer=%s failed message=%s"
                  (:queue-name args) (trim-message (:message args))))
    (on-timeout [this args]
      (log/errorf "consumer=%s timeout message=%s"
                  (:queue-name args) (trim-message (:message args))))
    (on-retry [this args]
      (log/errorf "consumer=%s retry attempt=%d message=%s"
                  (:queue-name args) (:retry-attempts args) (trim-message (:message args))))))

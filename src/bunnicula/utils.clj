(ns bunnicula.utils
  (:require
    [cheshire.core :as json])
  (:import
    (java.util.concurrent
      TimeUnit
      TimeoutException)))


(defmacro time-limited
  "Evaluate body in another thread.
   If evaluation takes longer then set timeout
   return timeout-response otherwise return result of body.
   Ensure original exception is rethrown"
  [ms-timeout timeout-response & body]
  `(let [f# (future ~@body)]
     (try
       (.get f# ~ms-timeout TimeUnit/MILLISECONDS)
       ;; ensure we cancel future on timeout
       (catch TimeoutException x#
         (do
           (future-cancel f#)
           ~timeout-response))
       ;; ensure we cancel future on error and rethrow the original error
       (catch Exception e#
         (do
           (future-cancel f#)
           (throw e#))))))


(defn- to-string [body]
  (String. body "UTF-8"))


(defn json-serializer [message]
  (-> message
      json/generate-string
      (.getBytes)))


(defn json-deserializer [body]
  (-> body
      to-string
      (json/parse-string true)))


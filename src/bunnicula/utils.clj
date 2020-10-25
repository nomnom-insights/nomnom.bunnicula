(ns bunnicula.utils
  (:require
    [cheshire.core :as json])
  (:import
    (java.util.concurrent
      Future
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
       (.get ^Future  f# ~ms-timeout TimeUnit/MILLISECONDS)
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
  (String. ^bytes body "UTF-8"))


(defn json-serializer [message]
  (let [json  (json/generate-string message)]
    (.getBytes ^String json)))


(defn json-deserializer [body]
  (-> body
      to-string
      (json/parse-string true)))

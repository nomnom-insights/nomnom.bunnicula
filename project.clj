(defproject nomnom/bunnicula "2.2.3"
  :description "Bunnicula: RabbitMQ client"
  :url "https://github.com/nomnom-insights/nomnom.bunnicula"
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/tools.logging "1.1.0"]
                 [com.rabbitmq/amqp-client "5.14.0"]
                 [com.stuartsierra/component "1.0.0"]
                 [cheshire "5.10.1"]]
  :deploy-repositories {"clojars" {:sign-releases false}}
  :min-lein-version "2.5.0"
  :global-vars {*warn-on-reflection* true}
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"
            :year 2018
            :key "mit"}
  :profiles {:dev
             {:dependencies  [[ch.qos.logback/logback-classic "1.2.7"]]}})

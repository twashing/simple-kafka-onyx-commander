(defproject com.interrupt/simple-kafka-onyx-commander "0.1.0-SNAPSHOT"
  :description "Platform code for the edgar trading system"
  :url "https://github.com/twashing/edgarly"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :repositories [["confluent" {:url "http://packages.confluent.io/maven/"}]]

  :dependencies [[org.clojure/clojure "1.8.0"]

                 ;; explicit versions of deps that would cause transitive dep conflicts
                 [org.clojure/tools.reader "1.0.0-beta1"]
                 [slingshot "0.12.2"]
                 [clj-time "0.9.0"]
                 ;; end explicit versions of deps that would cause transitive dep conflicts

                 [aero "1.1.2"]
                 [com.stuartsierra/component "0.3.2"]
                 [org.danielsz/system "0.4.1-SNAPSHOT"]

                 [org.apache.kafka/kafka_2.11 "0.10.1.1" :exclusions [org.slf4j/slf4j-log4j12]]
                 [org.onyxplatform/onyx "0.10.0-SNAPSHOT"]
                 [org.onyxplatform/onyx-kafka "0.10.0.0-SNAPSHOT"]
                 [ymilky/franzy "0.0.1"]
                 [ymilky/franzy-transit "0.0.1"]
                 [ymilky/franzy-admin "0.0.1" :exclusions [org.slf4j/slf4j-api]]]

  :source-paths ["src/clojure"]
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[clj-http "3.0.0"]
                                  [org.clojure/tools.namespace "0.2.11"]
                                  [suspendable "0.1.1"]
                                  [ring-mock "0.1.5"]]
                   :resource-paths ["resources"]}}

  :repl-options {:init-ns user}
  :main com.interrupt.edgarly.core)

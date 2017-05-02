(ns com.interrupt.streaming.core
  (:require [onyx.test-helper :refer [with-test-env load-config]]
            [onyx.plugin.kafka]
            [onyx.api]
            [franzy.clients.producer.client :as producer]
            [franzy.clients.consumer.client :as consumer]
            [franzy.clients.producer.protocols :refer :all]
            [franzy.clients.consumer.protocols :refer :all]
            [franzy.serialization.serializers :as serializers]
            [franzy.serialization.deserializers :as deserializers]
            [franzy.admin.zookeeper.client :as client]
            [franzy.admin.topics :as topics]
            [franzy.clients.producer.defaults :as pd]
            [franzy.clients.consumer.defaults :as cd])
  (:import [java.util UUID]))

(def zookeeper-url "zookeeper:2181")
(def kafka-url "kafka:9092")

(def topic-scanner-command "scanner-command")
(def topic-scanner "scanner")

(def key-serializer (serializers/keyword-serializer))
(def value-serializer (serializers/edn-serializer))
(def key-deserializer (deserializers/keyword-deserializer))
(def value-deserializer (deserializers/edn-deserializer))

(defn one-setup-topics []
  (def zk-utils (client/make-zk-utils {:servers [zookeeper-url]} false))
  (def two (topics/create-topic! zk-utils topic-scanner-command 10))
  (def three (topics/create-topic! zk-utils topic-scanner 10))
  (topics/all-topics zk-utils))

(defn two-write-to-topic []
  (let [;; Use a vector if you wish for multiple servers in your cluster
        pc {:bootstrap.servers [kafka-url]
            :group.id          "group.one"}

        ;;Serializes producer record keys that may be keywords
        key-serializer (serializers/keyword-serializer)

        ;;Serializes producer record values as EDN, built-in
        value-serializer (serializers/edn-serializer)

        ;;optionally create some options, even just use the defaults explicitly
        ;;for those that don't need anything fancy...
        options (pd/make-default-producer-options)
        topic topic-scanner-command
        partition 0]

    (with-open [p (producer/make-producer pc key-serializer value-serializer options)]
      (let [send-fut (send-async! p topic partition :a {:foo :bar} options)]
        (println "Async send results:" @send-fut)))))

(defn three-consume-from-topic []

  (let [cc {:bootstrap.servers [kafka-url]
            :group.id                "group.one"
            :auto.offset.reset       :earliest
            :enable.auto.commit      true
            :auto.commit.interval.ms 1000}

        options (cd/make-default-consumer-options {})]

    (with-open [c (consumer/make-consumer cc key-deserializer value-deserializer options)]

      ;; Note! - The subscription will read your comitted offsets to position the consumer accordingly
      ;; If you see no data, try changing the consumer group temporarily
      ;; If still no, have a look inside Kafka itself, perhaps with franzy-admin!
      ;; Alternatively, you can setup another threat that will produce to your topic while you consume, and all should be well
      (subscribe-to-partitions! c [topic-scanner])

      ;; Let's see what we subscribed to, we don't need Cumberbatch to investigate here...
      (println "Partitions subscribed to:" (partition-subscriptions c))

      ;; now we poll and see if there's any fun stuff for us
      (let [cr (poll! c)
            ;;a naive transducer, written the long way
            filter-xf (filter (fn [cr] (= (:key cr) :inconceivable)))
            ;;a naive transducer for viewing the values, again long way
            value-xf (map (fn [cr] (:value cr)))
            ;;more misguided transducers
            inconceivable-transduction (comp filter-xf value-xf)]

        (println "Record count:" (record-count cr))
        (println "Records by topic:" (records-by-topic cr topic-scanner))

        ;;;The source data is a seq, be careful!
        (println "Records from a topic that doesn't exist:" (records-by-topic cr "no-one-of-consequence"))
        (println "Records by topic partition:" (records-by-topic-partition cr topic-scanner 0))

        ;;;The source data is a list, so no worries here....
        (println "Records by a topic partition that doesn't exist:" (records-by-topic-partition cr "no-one-of-consequence" 99))
        (println "Topic Partitions in the result set:" (record-partitions cr))
        (clojure.pprint/pprint (into [] inconceivable-transduction cr))
                                        ;(println "Now just the values of all distinct records:")
        (println "Put all the records into a vector (calls IReduceInit):" (into [] cr))
        ;;wow, that was tiring, maybe now we don't want to listen anymore to this topic and take a break, maybe subscribe
        ;;to something else next poll....
        (clear-subscriptions! c)
        (println "After clearing subscriptions, a stunning development! We are now subscribed to the following partitions:"
                 (partition-subscriptions c))))))

(def workflow
  [[:read-commands :process-commands]
   [:process-commands :write-messages]])

(def printer (agent nil))
(defn echo-segments [event lifecycle]
  (send printer
        (fn [_]
          (doseq [segment (:onyx.core/batch event)]
            (println (format "Peer %s saw segment %s"
                             (:onyx.core/id event)
                             segment)))))
  {})

(def identity-lifecycle
  {:lifecycle/after-batch echo-segments})

(defn catalog [zookeeper-url topic-read topic-write]
  [{:onyx/name :read-commands
    :onyx/type :input
    :onyx/medium :kafka
    :onyx/plugin :onyx.plugin.kafka/read-messages
    :onyx/min-peers 1
    :onyx/max-peers 1
    :onyx/batch-size 10
    :kafka/zookeeper zookeeper-url
    :kafka/topic topic-read
    :kafka/deserializer-fn :com.interrupt.streaming.core/deserialize-kafka-message
    :kafka/offset-reset :earliest
    :onyx/fn ::spy
    :onyx/doc "Read commands from a Kafka topic"}

   {:onyx/name :process-commands
    :onyx/type :function
    :onyx/min-peers 1
    :onyx/max-peers 1
    :onyx/batch-size 10
    :onyx/fn :clojure.core/identity}

   {:onyx/name :write-messages
    :onyx/type :output
    :onyx/medium :kafka
    :onyx/plugin :onyx.plugin.kafka/write-messages
    :onyx/min-peers 1
    :onyx/max-peers 1
    :onyx/batch-size 10
    :kafka/zookeeper zookeeper-url
    :kafka/topic topic-write
    :kafka/serializer-fn :com.interrupt.streaming.core/serialize-kafka-message
    :kafka/request-size 307200
    :onyx/fn ::wrap-message
    :onyx/doc "Writes messages to a Kafka topic"}])

(defn spy [segment]
  (prn "Spying segment: " segment)
  segment)

#_(defn build-lifecycles []
  [{:lifecycle/task :process-commands
    :lifecycle/calls :com.interrupt.streaming.core/identity-lifecycle
    :onyx/doc "Lifecycle for logging segments"}])

(def serializer (serializers/edn-serializer))
(def deserializer (deserializers/edn-deserializer))

(defn deserialize-kafka-message [bytes]
  (.deserialize deserializer nil bytes #_(String. bytes "UTF-8")))

(defn serialize-kafka-message [segment]
  (.serialize serializer nil segment))

(defn wrap-message [segment]
  {:message segment})

(comment

  ;; 1
  (one-setup-topics)

  ;; 2
  (two-write-to-topic)

  ;; 3
  (three-consume-from-topic)

  ;; 4
  (let [tenancy-id (UUID/randomUUID)
        config (load-config "dev-config.edn")
        env-config (assoc (:env-config config)
                          :onyx/tenancy-id tenancy-id
                          :zookeeper/address zookeeper-url)
        peer-config (assoc (:peer-config config)
                           :onyx/tenancy-id tenancy-id
                           :zookeeper/address zookeeper-url)
        job {:workflow workflow
             :catalog (catalog zookeeper-url topic-scanner-command topic-scanner)
             ;; :lifecycles (build-lifecycles)
             :task-scheduler :onyx.task-scheduler/balanced}

        ;; ===

        env (onyx.api/start-env env-config)
        peer-group (onyx.api/start-peer-group peer-config)
        v-peers (onyx.api/start-peers 5 peer-group)
        submission (onyx.api/submit-job peer-config job)]

    (onyx.api/await-job-completion peer-config (:job-id submission)))

  #_(let [tenancy-id (UUID/randomUUID)
        config (load-config "dev-config.edn")
        env-config (assoc (:env-config config)
                          :onyx/tenancy-id tenancy-id
                          :zookeeper/address zookeeper-url)
        peer-config (assoc (:peer-config config)
                           :onyx/tenancy-id tenancy-id
                           :zookeeper/address zookeeper-url)
        job {:workflow workflow
             :catalog (catalog zookeeper-url topic-scanner-command topic-scanner)
             ;; :flow-conditions c/flow-conditions
             :lifecycles (build-lifecycles)
             ;; :windows c/windows
             ;; :triggers c/triggers
             :task-scheduler :onyx.task-scheduler/balanced}]

    ;; (make-topic! kafka-zookeeper commands-topic)
    ;; (make-topic! kafka-zookeeper events-topic)
    ;; (write-commands! kafka-brokers commands-topic commands)

    (with-test-env [test-env [10 env-config peer-config]]
      (let [submission(onyx.api/submit-job peer-config job)]
        (onyx.api/await-job-completion peer-config (:job-id submission))))))

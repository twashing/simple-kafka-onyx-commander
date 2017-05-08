(ns com.interrupt.streaming.platform
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

(defn two-write-to-topic
  ([] (two-write-to-topic "a" {:foo :bar}))
  ([k v]
   (let [;; Use a vector if you wish for multiple servers in your cluster
         pc {:bootstrap.servers [kafka-url]
             :group.id          "group.one"}

         ;;Serializes producer record keys that may be keywords
         string-serializer (serializers/string-serializer)

         ;;Serializes producer record values as EDN, built-in
         value-serializer (serializers/edn-serializer)

         ;;optionally create some options, even just use the defaults explicitly
         ;;for those that don't need anything fancy...
         options (pd/make-default-producer-options)
         topic topic-scanner-command
         partition 0]

     (with-open [p (producer/make-producer pc string-serializer value-serializer options)]
       (let [send-fut (send-async! p topic partition k v options)]
         (println "Async send results:" @send-fut))))))

;; As laid out in the "Edgarly Platform" diagram
;; https://drive.google.com/file/d/0B213_8Py7z1AVFpBRlFtd0FFUXM/view?usp=sharing
(def workflow
  [[:scanner-command :ibgateway]
   [:ibgateway :scanner]])

#_(def workflow
  [[:scanner-command :ibgateway]
   [:ibgateway :scanner]

   [:scanner :market-scanner]
   [:market-scanner :filtered-stocks]
   [:filtered-stocks :analytics]

   ;;
   [:analytics :stock-command]
   [:stock-command :ibgateway]

   [:ibgateway :stock]
   [:stock :analytics]

   ;;
   [:analytics :predictive-analytics]
   [:predictive-analytics :clnn]
   [:filtered-stocks :clnn]

   ;;
   [:clnn :historical-command]
   [:historical-command :ibgateway]

   [:ibgateway :historical]
   [:historical :clnn]

   ;;
   [:clnn :trade-recommendations]
   [:trade-recommendations :execution-engine]

   ;;
   [:execution-engine :trades]
   [:trades :bookeeping]

   ;;
   [:bookeeping :positions]
   [:positions :execution-engine]])

(def printer (agent nil))
(defn echo-segments [event lifecycle]
  (send printer
        (fn [_]
          (doseq [segment (:onyx.core/batch event)]
            (println (format "Peer %s saw segment %s"
                             (:onyx.core/id event)
                             segment)))))
  {})

(defn catalog [zookeeper-url topic-read topic-write]
  [{:onyx/name :scanner-command
    :onyx/type :input
    :onyx/medium :kafka
    :onyx/plugin :onyx.plugin.kafka/read-messages
    :kafka/wrap-with-metadata? true
    :onyx/min-peers 1
    :onyx/max-peers 1
    :onyx/batch-size 10
    :kafka/zookeeper zookeeper-url
    :kafka/topic topic-read
    :kafka/deserializer-fn ::deserialize-kafka-message
    :kafka/key-deserializer-fn ::deserialize-kafka-key
    :kafka/offset-reset :earliest
    ;;:onyx/fn ::spy
    :onyx/doc "Read from the 'scanner-command' Kafka topic"}

   {:onyx/name :ibgateway
    :onyx/type :function
    :onyx/min-peers 1
    :onyx/max-peers 1
    :onyx/batch-size 10
    :onyx/fn ::local-identity}

   {:onyx/name :scanner
    :onyx/type :output
    :onyx/medium :kafka
    :onyx/plugin :onyx.plugin.kafka/write-messages
    :onyx/min-peers 1
    :onyx/max-peers 1
    :onyx/batch-size 10
    :kafka/zookeeper zookeeper-url
    :kafka/topic topic-write
    :kafka/serializer-fn ::serialize-kafka-message
    :kafka/key-serializer-fn ::serialize-kafka-key
    :kafka/request-size 307200
    ;;:onyx/fn ::wrap-message
    :onyx/doc "Writes messages to a Kafka topic"}])

(defn local-identity [segment]
  (println "local-identity segment: " segment)
  (dissoc segment :topic))

(def deserializer (deserializers/edn-deserializer))
(def serializer (serializers/edn-serializer))
(def string-deserializer (franzy.serialization.deserializers/string-deserializer))
(def string-serializer (franzy.serialization.serializers/string-serializer))

(defn deserialize-kafka-message [segments]
  (.deserialize deserializer nil segments))

(defn serialize-kafka-message [segment]
  (.serialize serializer nil segment))

(defn deserialize-kafka-key [k]
  (.deserialize string-deserializer topic-scanner-command k))

(defn serialize-kafka-key [k]
  (.serialize string-serializer topic-scanner k))



(comment

  ;; 1
  (one-setup-topics)

  ;; 2
  (two-write-to-topic (str (UUID/randomUUID)) {:foo :bar})

  ;; 3
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
             :task-scheduler :onyx.task-scheduler/balanced}

        env (onyx.api/start-env env-config)
        peer-group (onyx.api/start-peer-group peer-config)
        v-peers (onyx.api/start-peers 5 peer-group)
        submission (onyx.api/submit-job peer-config job)]

    (onyx.api/await-job-completion peer-config (:job-id submission))))

;; TODO
;;
;; > How to break up the workflow, if the functions not only are executed on several machines,
;; but exist across several projects?
;;
;; > How to split from a topic that has 1 partition, to a topic with n partitions


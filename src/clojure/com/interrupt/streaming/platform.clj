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
            [franzy.clients.consumer.defaults :as cd]
            [com.interrupt.streaming.platform.scanner-command :as psc]
            [com.interrupt.streaming.platform.scanner :as ps]
            [com.interrupt.streaming.platform.filtered-stocks :as pfs]
            [com.interrupt.streaming.platform.stock-command :as pstc]
            [com.interrupt.streaming.platform.stock :as pst]
            [com.interrupt.streaming.platform.predictive-analytics :as pa]
            [com.interrupt.streaming.platform.historical-command :as phc]
            [com.interrupt.streaming.platform.historical :as ph]
            [com.interrupt.streaming.platform.trade-recommendations :as ptr]
            [com.interrupt.streaming.platform.trades :as pt]
            [com.interrupt.streaming.platform.positions :as pp])
  (:import [java.util UUID]))


(def zookeeper-url "zookeeper:2181")
(def kafka-url "kafka:9092")

(def topic-scanner-command "scanner-command")
(def topic-scanner "scanner")
(def topic-filtered-stocks "filtered-stocks")
(def topic-stock-command "stock-command")


(defn one-setup-topics []

  (def zk-utils (client/make-zk-utils {:servers [zookeeper-url]} false))
  (doseq [topic [topic-scanner-command
                 topic-scanner
                 topic-filtered-stocks
                 topic-stock-command]]
    (topics/create-topic! zk-utils topic 10))

  (topics/all-topics zk-utils))

(defn two-write-to-topic
  ([topic] (two-write-to-topic topic "a" {:foo :bar}))
  ([topic k v]
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
         partition 0]

     (with-open [p (producer/make-producer pc string-serializer value-serializer options)]
       (let [send-fut (send-async! p topic partition k v options)]
         (println "Async send results:" @send-fut))))))

(defn find-task [catalog task-name]
  (let [matches (filter #(= task-name (:onyx/name %)) catalog)]
    (when-not (seq matches)
      (throw (ex-info (format "Couldn't find task %s in catalog" task-name)
                      {:catalog catalog :task-name task-name})))
    (first matches)))

(defn n-peers
  "Takes a workflow and catalog, returns the minimum number of peers
   needed to execute this job."
  [catalog workflow]
  (let [task-set (into #{} (apply concat workflow))]
    (reduce
     (fn [sum t]
       (+ sum (or (:onyx/min-peers (find-task catalog t)) 1)))
     0 task-set)))

;; As laid out in the "Edgarly Platform" diagram
;; https://drive.google.com/file/d/0B213_8Py7z1AVFpBRlFtd0FFUXM/view?usp=sharing
#_(def workflow
  [[:scanner-command :ibgateway]
   [:ibgateway :scanner]

   [:scanner :market-scanner]
   [:market-scanner :filtered-stocks]

   [:filtered-stocks :analytics]
   [:analytics :stock-command]

   [:stock-command :ibgateway]
   [:ibgateway :stock]

   [:stock :analytics]
   [:analytics :predictive-analytics]

   ;; **
   [:predictive-analytics :clnn]
   [:filtered-stocks :clnn]
   [:clnn :historical-command]

   [:historical-command :ibgateway]
   [:ibgateway :historical]

   [:historical :clnn]
   [:clnn :trade-recommendations]

   [:trade-recommendations :execution-engine]
   [:execution-engine :trades]

   [:trades :bookeeping]
   [:bookeeping :positions]

   [:positions :edgarly]
   [:edgarly :scanner-command]])

#_(defn catalog [zookeeper-url topic-read topic-write]
  [;;
   {:onyx/name :scanner-command
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
    :onyx/doc "Writes messages to a Kafka topic"}

   ;;
   {:onyx/name :scanner-input
    :onyx/type :input
    :onyx/medium :kafka
    :onyx/plugin :onyx.plugin.kafka/read-messages
    :kafka/wrap-with-metadata? true
    :onyx/min-peers 1
    :onyx/max-peers 1
    :onyx/batch-size 10
    :kafka/zookeeper zookeeper-url
    :kafka/topic "scanner"
    :kafka/deserializer-fn ::deserialize-kafka-message
    :kafka/key-deserializer-fn ::deserialize-kafka-key
    :kafka/offset-reset :earliest
    :onyx/doc "Read from the 'scanner-command' Kafka topic"}

   {:onyx/name :market-scanner
    :onyx/type :function
    :onyx/min-peers 1
    :onyx/max-peers 1
    :onyx/batch-size 10
    :onyx/fn ::local-identity}

   {:onyx/name :filtered-stocks
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
    :onyx/doc "Writes messages to a Kafka topic"}

   ;;
   {:onyx/name :filtered-stocks-input
    :onyx/type :input
    :onyx/medium :kafka
    :onyx/plugin :onyx.plugin.kafka/read-messages
    :kafka/wrap-with-metadata? true
    :onyx/min-peers 1
    :onyx/max-peers 1
    :onyx/batch-size 10
    :kafka/zookeeper zookeeper-url
    :kafka/topic "filtered-stocks"
    :kafka/deserializer-fn ::deserialize-kafka-message
    :kafka/key-deserializer-fn ::deserialize-kafka-key
    :kafka/offset-reset :earliest
    :onyx/doc "Read from the 'scanner-command' Kafka topic"}

   {:onyx/name :analytics
    :onyx/type :function
    :onyx/min-peers 1
    :onyx/max-peers 1
    :onyx/batch-size 10
    :onyx/fn ::local-identity}

   {:onyx/name :stock-command
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
    :onyx/doc "Writes messages to a Kafka topic"}])


(comment

  ;; 1
  (one-setup-topics)

  ;; 2
  (two-write-to-topic "scanner-command" (str (UUID/randomUUID)) {:foo :bar})

  (let [config (load-config "dev-config.edn")
        env-config (assoc (:env-config config)
                          :zookeeper/address zookeeper-url)
        peer-config (assoc (:peer-config config)
                           :zookeeper/address zookeeper-url)
        env (onyx.api/start-env env-config)
        peer-group (onyx.api/start-peer-group peer-config)

        peer-count 6 ;; #spy/d (n-peers the-catalog the-workflow)
        v-peers (onyx.api/start-peers peer-count peer-group)]

    (let [the-workflow psc/workflow
          the-catalog (psc/catalog zookeeper-url "scanner-command" "scanner")
          job {:workflow the-workflow
               :catalog the-catalog
               :task-scheduler :onyx.task-scheduler/balanced}]
      #spy/d (onyx.api/submit-job peer-config job))

    (let [the-workflow ps/workflow
          the-catalog (ps/catalog zookeeper-url "scanner" "filtered-stocks")
          job {:workflow the-workflow
               :catalog the-catalog
               :task-scheduler :onyx.task-scheduler/balanced}]
      #spy/d (onyx.api/submit-job peer-config job))

    #_(let [the-workflow pfs/workflow
          the-catalog (pfs/catalog zookeeper-url "filtered-stocks" "stock-command")
          job {:workflow the-workflow
               :catalog the-catalog
               :task-scheduler :onyx.task-scheduler/balanced}]
      #spy/d (onyx.api/submit-job peer-config job))

    #_(let [the-workflow pstc/workflow
          the-catalog (pstc/catalog zookeeper-url "stock-command" "stock")
          job {:workflow the-workflow
               :catalog the-catalog
               :task-scheduler :onyx.task-scheduler/balanced}]
      #spy/d (onyx.api/submit-job peer-config job))

    #_(let [the-workflow pst/workflow
          the-catalog (pst/catalog zookeeper-url "stock" "predictive-analytics" )
          job {:workflow the-workflow
               :catalog the-catalog
               :task-scheduler :onyx.task-scheduler/balanced}]
      #spy/d (onyx.api/submit-job peer-config job))

    #_(let [the-workflow pa/workflow
          the-catalog (pa/catalog zookeeper-url "predictive-analytics" "filtered-stocks" "historical-command")
          job {:workflow the-workflow
               :catalog the-catalog
               :task-scheduler :onyx.task-scheduler/balanced}]
      #spy/d (onyx.api/submit-job peer-config job))

    #_(let [the-workflow phc/workflow
          the-catalog (phc/catalog zookeeper-url "historical-command" "historical")
          job {:workflow the-workflow
               :catalog the-catalog
               :task-scheduler :onyx.task-scheduler/balanced}]
      #spy/d (onyx.api/submit-job peer-config job))

    #_(let [the-workflow ph/workflow
          the-catalog (ph/catalog zookeeper-url "historical" "trade-recommendations")
          job {:workflow the-workflow
               :catalog the-catalog
               :task-scheduler :onyx.task-scheduler/balanced}]
      #spy/d (onyx.api/submit-job peer-config job))

    #_(let [the-workflow ptr/workflow
          the-catalog (ptr/catalog zookeeper-url "trade-recommendations" "trades")
          job {:workflow the-workflow
               :catalog the-catalog
               :task-scheduler :onyx.task-scheduler/balanced}]
      #spy/d (onyx.api/submit-job peer-config job))

    #_(let [the-workflow pt/workflow
          the-catalog (pt/catalog zookeeper-url "trades" "positions")
          job {:workflow the-workflow
               :catalog the-catalog
               :task-scheduler :onyx.task-scheduler/balanced}]
      #spy/d (onyx.api/submit-job peer-config job))

    #_(let [the-workflow pp/workflow
          the-catalog (pp/catalog zookeeper-url "positions" "scanner-command")
          job {:workflow the-workflow
               :catalog the-catalog
               :task-scheduler :onyx.task-scheduler/balanced}]
      #spy/d (onyx.api/submit-job peer-config job))))

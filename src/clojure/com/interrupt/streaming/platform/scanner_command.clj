(ns com.interrupt.streaming.platform.scanner-command
  (:require [com.interrupt.streaming.platform.base :as base]
            [com.interrupt.streaming.platform.serialization]))


(def workflow
  [[:scanner-command :ibgateway]
   [:ibgateway :scanner]])

(defn catalog [zookeeper-url topic-read topic-write]
  (base/catalog-basic zookeeper-url topic-read topic-write
                      {:input-name :scanner-command
                       :output-name :scanner
                       :function-name :ibgateway
                       :function-id :com.interrupt.streaming.platform.base/local-identity}))

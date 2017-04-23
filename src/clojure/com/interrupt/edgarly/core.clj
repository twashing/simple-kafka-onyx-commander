(ns com.interrupt.edgarly.core
  (:require  [com.stuartsierra.component :as component]
             [system.repl :refer [set-init! init start stop reset refresh system]]
             [system.components.repl-server :refer [new-repl-server]]))

(defn system-map []
  (component/system-map
   :nrepl (new-repl-server 7888 "0.0.0.0")))

(set-init! #'system-map)
(defn start-system [] (start))
(defn stop-system [] (stop))
(defn -main [& args] (start-system))


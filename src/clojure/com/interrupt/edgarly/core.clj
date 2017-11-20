(ns com.interrupt.edgarly.core
  (:require  [com.stuartsierra.component :as component]
             [system.repl :refer [set-init! init start stop reset refresh system]]
             [system.components.repl-server :refer [new-repl-server]]
             [system.components.http-kit :refer [new-web-server]]))


(defn handler [request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body "ok"})

(defn system-map []
  (component/system-map
   :nrepl (new-repl-server 5444 "0.0.0.0")
   :webserver (new-web-server 8081 handler)))

(set-init! #'system-map)
(defn start-system [] (start))
(defn stop-system [] (stop))
(defn -main [& args] (start-system))

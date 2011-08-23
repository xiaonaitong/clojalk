(ns clojalk.net
  (:refer-clojure :exclude [use peek])
  (:require [clojure.contrib.logging :as logging])
  (:use [clojalk core utils])
  (:use [clojalk.net.protocol])
  (:use [aleph.tcp])
  (:use [lamina.core])
  (:use [gloss.core]))

;; this is for test and debug only
(defn echo-handler [ch client-info]
  (receive-all ch
    #(if-let [msg %]
       (do
         (println msg)
         (if (seq? msg) ;; known command will be transformed into a sequence by codec
           (case (first msg)
             "quit" (close ch)
             (enqueue ch ["INSERTED" "5"]))
         
           (enqueue ch ["UNKNOWN_COMMAND"]))))))

;; sessions 
(defonce sessions (ref {}))

(defn get-or-create-session [ch remote-addr type]
  (dosync
    (if-not (contains? @sessions remote-addr)
      (let [new-session (open-session type)]
        (alter new-session assoc :id remote-addr :channel ch)
        (alter sessions assoc remote-addr new-session))))
  (@sessions remote-addr))

(defn close-session [remote-addr]
  (dosync
    (alter sessions dissoc remote-addr)))

;; reserve watcher
(defn incoming-job-watcher [key identity old-value new-value]
  (let [old-job (:incoming_job old-value)
        new-job (:incoming_job new-value)]
    (if (and new-job (not (= old-job new-job)))
      (let [ch (:channel new-value)]
        (enqueue ch ["RESERVED" (str (:id new-job)) (:body new-job)])))))

;; server handlers
(defn on-put [ch session args]
  (try
    (let [priority (as-int (first args))
          delay (as-int (second args))
          ttr (as-int (nth args 2))
          body (last args)
          job (put session priority delay ttr body)]
      (if job
        (enqueue ch ["INSERTED" (str (:id job))])))
    (catch NumberFormatException e (enqueue ch ["BAD_FORMAT"]))))

(defn on-reserve [ch session]
  (add-watch session (:id session) incoming-job-watcher)
  (reserve session))

(defn on-use [ch session args]
  (let [tube-name (first args)]
    (use session tube-name)
    (enqueue ch ["USING" tube-name])))

(defn on-watch [ch session args]
  (let [tube-name (first args)]
    (watch session tube-name)
    (enqueue ch ["WATCHING" (str (count (:watch @session)))])))

(defn on-ignore [ch session args]
  (if (> (count (:watch @session)) 1)
    (let [tube-name (first args)]
      (ignore session tube-name)
      (enqueue ch ["WATCHING" (str (count (:watch @session)))]))
    (enqueue ch ["NOT_IGNORED"])))

(defn on-quit [ch remote-addr]
  (close-session remote-addr)
  (close ch))

(defn command-dispatcher [ch client-info cmd args]
  (let [remote-addr (:remote-addr client-info)]
    (case cmd
      "PUT" (on-put ch (get-or-create-session ch remote-addr :producer) args)
      "RESERVE" (on-reserve ch (get-or-create-session ch remote-addr :worker))
      "USE" (on-use ch (get-or-create-session ch remote-addr :producer) args)
      "WATCH" (on-watch ch (get-or-create-session ch remote-addr :worker) args)
      "IGNORE" (on-ignore ch (get-or-create-session ch remote-addr :worker) args)
      "QUIT" (on-quit ch remote-addr)
      )))

(defn default-handler [ch client-info]
  (receive-all ch
    #(if-let [msg %]
       (if (seq? msg)
         (try 
           (command-dispatcher ch client-info (first msg) (rest msg))
           (catch Exception e 
                  (do
                    (logging/warn (str "error on processing " msg) e)
                    (enqueue ch ["INTERNAL_ERROR"]))))
         (enqueue ch ["UNKNOWN_COMMAND"])))))

(defn start-server [port]
  (start-tcp-server default-handler {:port port, :frame beanstalkd-codec}))

(defn -main []
  (do
    (start-server 10000)
    (println "server started")))

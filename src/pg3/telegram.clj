(ns pg3.telegram
  (:require
   [k8s.core :as k8s]
   [morse.handlers :as h]
   [morse.polling :as p]
   [morse.api :as t]
   [clojure.string :as str]))

(def token  (or (System/getenv "TELEGRAM_TOKEN")
                (k8s/secret :pg3 :TELEGRAM_TOKEN)))

(def chatid (or (System/getenv "TELEGRAM_CHATID")
                (k8s/secret :pg3 :TELEGRAM_CHATID)))

(def channel (atom {}))

(h/defhandler handler
  (h/command-fn "start"
    (fn [{{id :id :as chat} :chat}]
      (t/send-text token id " Welcome to PG3!")))

  (h/command-fn "chatid"
    (fn [{{id :id :as chat} :chat}]
      (t/send-text token id (str "Chat-id: " id))))

  (h/command-fn "help"
    (fn [{{id :id :as chat} :chat}]
      (t/send-text token id "/chatid - show current chatid ")))

  (h/message-fn
    (fn [{{id :id} :chat :as message}]
      (t/send-text token id "I don't do a whole lot ... yet."))))

(defn notify [msg]
  (t/send-text token chatid {:parse_mode "Markdown"} msg))

(defn start []
  (if (str/blank? token)
    (println "Please provde token in TELEGRAM_TOKEN environment variable!")
    (reset! channel (p/start token handler))) )

(defn stop []
  (when @channel
    (p/stop @channel)
    (reset! channel nil)))

(defn prepare-text [text]
  (if (or (str/includes? text "_") (str/includes? text "*"))
    (str " with ```" text "```")
    (str " with _" text "_")))

(defn make-message [phase text res]
  (let [kind (:kind res)
        res-name (get-in res [:metadata :name])]
    (format "Resource *%s*:*%s* finish phase *%s*%s"
            kind res-name phase
            (if text (prepare-text text) ""))))

(def okEmoji (apply str (Character/toChars 9989)))
(def noEmoji (apply str (Character/toChars 10060)))

(defn notify* [emoji phase text res]
  (let [msg (str emoji (make-message phase text res))]
    (println "telegram: " msg)
    (notify msg)))

(def error (partial notify* noEmoji))
(def success (partial notify* okEmoji))

(defn restart []
  (stop)
  (start))

(comment
  (notify (str (apply str (Character/toChars 9989)) (apply str (Character/toChars 10060))) )
  (start)
  (stop)
  (restart))

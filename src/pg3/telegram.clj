(ns pg3.telegram
  (:require
   [k8s.core :as k8s]
   [morse.handlers :as h]
   [morse.polling :as p]
   [morse.api :as t]
   [clojure.string :as str]))

(def mock-path (System/getenv "TELEGRAM_MOCK_PATH"))

(def token  (or (System/getenv "TELEGRAM_TOKEN")
                (k8s/secret :pg3 :TELEGRAM_TOKEN)))

(def chatid (or (System/getenv "TELEGRAM_CHATID")
                (k8s/secret :pg3 :TELEGRAM_CHATID)))

(def notification-enabled? (or (System/getenv "NOTIFICATION_ENABLED")
                               (k8s/secret :pg3 :NOTIFICATION_ENABLED)))

(defn notify [msg]
  (t/send-text token chatid {:parse_mode "Markdown"} msg))

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
  (if notification-enabled?
    (let [msg (str emoji " " (make-message phase text res))]
      (if mock-path
        (spit mock-path (str msg "\n") :append true)
        (notify msg)))))

(def error (partial notify* noEmoji))
(def success (partial notify* okEmoji))

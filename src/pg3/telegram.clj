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

(defn notify [msg]
  (t/send-text token chatid {:parse_mode "Markdown"} msg))

(defn prepare-text [text]
  (if (or (str/includes? text "_") (str/includes? text "*"))
    (str "```" text "```")
    (str "_" text "_")))

(defn make-error-message [phase text res]
  (let [kind (:kind res)
        res-name (get-in res [:metadata :name])]
    (format "Resource *%s*:*%s* failed phase *%s* with %s"
            kind res-name phase
            (if text (prepare-text text) ""))))

(defn make-success-message [_ text res]
  (let [kind (:kind res)
        res-name (get-in res [:metadata :name])]
    (format "Resource *%s*:*%s* %s"
            kind res-name (prepare-text text))))

(def okEmoji (apply str (Character/toChars 9989)))
(def noEmoji (apply str (Character/toChars 10060)))

(defn notify* [make-message emoji phase text res]
  (let [msg (str emoji " " (make-message phase text res))]
    (if mock-path
      (spit mock-path (str msg "\n") :append true)
      (notify msg))))

(def error (partial notify* make-error-message noEmoji))
(def success (partial notify* make-success-message okEmoji))

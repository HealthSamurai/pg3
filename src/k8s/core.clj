(ns k8s.core
  (:import java.util.Base64)
  (:require
   [org.httpkit.client :as http-client]
   [clj-json-patch.core :as patch]
   [clojure.walk :as walk]
   [cheshire.core :as json]
   [inflections.core :as inflections]
   [clojure.tools.logging :as log]
   [clojure.string :as str]))

(def default-headers
  (if-let [token (System/getenv "KUBE_TOKEN")]
    {"Authorization" (str "Bearer " token)}
    {}))

(def kube-url (or (System/getenv "KUBE_URL") "http://localhost:8001"))

(defn url [pth]
  (str kube-url "/" pth))

(defn base64-decode [s]
  (if s
    (String. (.decode (Base64/getDecoder) s))
    nil))

(defn base64-encode [s]
  (if s
    (String. (.encode (Base64/getEncoder) (.getBytes s)))
    nil))

(defn secret [name key]
  (let [cfg {:apiVersion "v1" :ns "default"}]
    (->
     @(http-client/get
       (str kube-url  "/api/v1/namespaces/default/secrets/" name)
       {:insecure? true
        :headers (merge default-headers {"Content-Type" "application/json-patch+json"})})
     :body (json/parse-string keyword)
     :data key
     base64-decode)))

(defn resolve-secrets [res]
  (walk/postwalk
   (fn [x]
     (if (and (map? x) (contains? x :valueFrom))
       (if-let [{nm :name k :key} (get-in x [:valueFrom :secretKeyRef])]
         (if-let [res (secret nm (keyword k))]
           res
           (do (log/warn "Could not resolve secret " k " from " nm) x))
         x)
       x)) res))

(defn to-query-string [m]
  (->> m
       (mapv (fn [[k v]]
               (str (name k) "=" (java.net.URLEncoder/encode (str v)))))
       (str/join "&")))

;; (to-query-string {:a 1 :b "x in (z)"})

(defn pluralize [x]
  (inflections/plural x))

(defn resource-url [cfg & parts]
  (let [[path params] (if (map? (last parts))
                        [(butlast parts) (last parts)]
                        [parts nil])]
    (url (str (if (re-matches  #"^v[.0-9]+$" (:apiVersion cfg))
                    "api" "apis")
                  "/" (:apiVersion cfg)
                  (when-let [ns (or (:ns cfg) (get-in cfg [:metadata :namespace]))]
                    (str "/namespaces/" ns))
                  (str "/" (pluralize (str/lower-case (:kind cfg))))
                  (when (seq? path)
                    (str "/" (str/join "/" path)))
                  (when params (str "?" (to-query-string params)))))))

(defn query [cfg & pth]
  (println  (apply resource-url cfg pth))
  (let [res @(http-client/get
              (apply resource-url cfg pth)
              {:headers (merge default-headers {"Content-Type" "application/json"})
               :insecure? true})]
    (-> res
     :body
     (json/parse-string keyword)
     (resolve-secrets))))

(defn delete-collection [opts]
  (let [uri   (resource-url opts (select-keys opts [:labelSelector ]))]
    (println "DELETE " uri)
    (-> @(http-client/delete
          uri
          {:headers (merge default-headers {"Content-Type" "application/json"})
           :insecure? true})
        :body
        (json/parse-string keyword))))

;; ReplicationController, ReplicaSet, StatefulSet, DaemonSet, and Deployment

(defn get-json-patch [res nres]
  (println res)
  (let [-res (walk/stringify-keys (-> res
                                      (assoc :metadata (dissoc (:metadata res) :uid :resourceVersion :creationTimestamp :selfLink))
                                      (dissoc :status :selfLink)))
        -nres (walk/stringify-keys nres)]
    (patch/diff -res -nres)))

(defn list [cfg] (query cfg))

(defn find [cfg]
  (query cfg (or (:id cfg) (get-in cfg [:metadata :name]))))

(defn create [res]
  (let [u (resource-url res)]
    (println "POST " u)
    (-> @(http-client/post
          u
          {:body (json/generate-string (walk/stringify-keys res))
           :insecure? true
           :headers (merge default-headers {"Content-Type" "application/json"})})
        :body
        (json/parse-string keyword))))

(defn delete [res]
  (-> @(http-client/delete
        (str (resource-url res (get-in res [:metadata :name])))
        {:headers (merge default-headers {"Content-Type" "application/json"})
         :insecure? true})
      :body
      (json/parse-string keyword)))

(defn *merge
  "merges metadata into one bundle"
  ([a b]
   (loop [[[k v :as i] & ks] b
          acc a]
     (if (nil? i)
       acc
       (let [av (get a k)]
         (if (= v av)
           (recur ks acc)
           (recur ks (if (and (map? v) (map? av))
                       (assoc acc k (*merge av v))
                       (assoc acc k v))))))))
  ([a b & more]
   (apply *merge (*merge a b) more)))

(defn patch [nres]
  (let [res (find nres)]
    (if-not (or (number? res) (= "Failure" (get res :status)))
      (let [diff (patch/diff (walk/stringify-keys res)
                             (walk/stringify-keys (*merge res nres)))]
        (println "PATCH" diff)
        (->
         @(http-client/patch
           (str (resource-url res (get-in res [:metadata :name])))
           {:body (json/generate-string diff)
            :insecure? true
            :headers (merge default-headers {"Content-Type" "application/json-patch+json"})})
         :body
         (json/parse-string keyword)))
      (create nres))))

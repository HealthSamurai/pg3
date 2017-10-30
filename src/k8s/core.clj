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

(defn curl [pth]
  (let [res @(http-client/get
              (url pth)
              {:headers default-headers :insecure? true})]
    (-> res :body)))

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

(resource-url {:kind "PersistentVolumeClaim" :apiVersion "v1"})
(resource-url {:kind "PersistentVolumeClaim" :apiVersion "apiextensions.k8s.io/v1beta1"} {:labelSelector "system in (c3)"})

(resource-url {:kind "PersistentVolumeClaim" :apiVersion "v1" :ns "test"})
(resource-url {:kind "PersistentVolumeClaim" :apiVersion "v1" :ns "test"})


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

(defn do-find [cfg]
  (let [res (query cfg (or (:id cfg) (get-in cfg [:metadata :name])))]
    (when-not (= "Failure" (:status res))
      res)))

(defn create [res]
  (let [uri (resource-url res)]
    (println "POST" res)
    (-> @(http-client/post
          uri
          {:body (json/generate-string (walk/stringify-keys res))
           :insecure? true
           :headers (merge default-headers {"Content-Type" "application/json"})})
        :body
        (json/parse-string))))

(defn delete-collection [opts]
  (let [uri   (resource-url opts (select-keys opts [:labelSelector ]))]
    (println "DELETE " uri)
    (-> @(http-client/delete
          uri
          {:headers (merge default-headers {"Content-Type" "application/json"})
           :insecure? true})
        :body
        (json/parse-string))))

;; ReplicationController, ReplicaSet, StatefulSet, DaemonSet, and Deployment

(defn delete [res & [delete-opts]]
  (-> @(http-client/delete
        (str (resource-url res (get-in res [:metadata :name])))
        (cond-> {:headers (merge default-headers {"Content-Type" "application/json"})
                 :insecure? true}
          delete-opts (assoc :body (json/generate-string (walk/stringify-keys delete-opts)))))
      :body
      (json/parse-string)))

(defn get-json-patch [res nres]
  (println res)
  (let [-res (walk/stringify-keys (-> res
                                      (assoc :metadata (dissoc (:metadata res) :uid :resourceVersion :creationTimestamp :selfLink))
                                      (dissoc :status :selfLink)))
        -nres (walk/stringify-keys nres)]
    (patch/diff -res -nres)))

(defn patch [nres]
  (println "PATCH" (do-find nres))
  (if-let [res (do-find nres)]
    (let [diff  (get-json-patch res nres)
          uri (resource-url nres (or (:id nres) (get-in nres [:metadata :name])))]
      (println "PATCH" diff)
      (->
       @(http-client/patch
         uri
         {:body (json/generate-string diff)
          :insecure? true
          :headers (merge default-headers {"Content-Type" "application/json-patch+json"})})
       :body
       (json/parse-string keyword)))
    (create nres)))

(comment
  ;; (map :resourceVersion (map :metadata (:items (list cfg :repositories))))
  ;; (map :metadata)

  ;; (do-find cfg :repositories "ci3")

  ;; (resolve-secrets {:tar{:foo {:valueFrom {:secretKeyRef {:name "ci3" :key "bbKey"}}}}})
  ;; (resolve-secrets (do-find cfg :repositories "ci3"))

  ;; (patch cfg :repositories "ci3-chart" {:webhook nil})

  ;; (secret "ci3" :mySecret)
  ;; (secret "secrets" :github_token)

  ;; (secret "bitbucket" :oauthConsumerSecret)

  ;; (do-find cfg :builds "ci3-build-6")

  ;; (patch cfg :builds "test-1" {:status "changed"})

  ;; (delete cfg :repositories "ci3")


  ;; (curl {} "api/v1/namespaces/default/pods/aitem-hook-test-d40a2375646990b2dec75e80cf97ce5a8a77a199/log")

  ;; (create cfg :builds
  ;;         {:kind "Build"
  ;;          :apiVersion "ci3.io/v1"
  ;;          :metadata {:name "test-00"}})
  ;; (-> @(http-client/get
  ;;       (url cfg (str "api/v1/namespaces/default/pods"))
  ;;       {:insecure? true
  ;;        :headers (merge default-headers {"Content-Type" "application/json"})})
  ;;     :body
  ;;     (json/parse-string))

  ;; #_(query {:apiVersion "zeroci.io/v1"
  ;;           :ns "default"}
  ;;          :builds "test-1")

  ;; #_(query {:apiVersion "zeroci.io/v1"
  ;;           :ns "default"}
  ;;          :builds "test-1")



  (require '[clj-yaml.core :as yaml])

  (spit "/tmp/pods.yaml"
        (yaml/generate-string
         (query {:prefix "api"
                 :ns "pg3"
                 :apiVersion "v1"}
                :pods)))

  (spit "/tmp/pods.yaml"
        (yaml/generate-string
         (query {:prefix "api"
                 :ns "pg3"
                 :apiVersion "v1"}
                :persistentvolumes)))
  )
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

(first (:items (query {:apiVersion "v1" :kind "PersistentVolumeClaim"})))

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
        (json/parse-string))))

(defn delete [res]
  (-> @(http-client/delete
        (str (resource-url res (get-in res [:metadata :name])))
        {:headers (merge default-headers {"Content-Type" "application/json"})
         :insecure? true})
      :body
      (json/parse-string)))


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
         :body))
      (create nres))))

(comment
  ;; (map :resourceVersion (map :metadata (:items (list cfg :repositories))))
  ;; (map :metadata)

  ;; (find cfg :repositories "ci3")

  ;; (resolve-secrets {:tar{:foo {:valueFrom {:secretKeyRef {:name "ci3" :key "bbKey"}}}}})
  ;; (resolve-secrets (find cfg :repositories "ci3"))

  ;; (patch cfg :repositories "ci3-chart" {:webhook nil})

  ;; (secret "ci3" :mySecret)
  ;; (secret "secrets" :github_token)

  ;; (secret "bitbucket" :oauthConsumerSecret)

  ;; (find cfg :builds "ci3-build-6")

  ;; (patch cfg :builds "test-1" {:status "changed"})

  ;; (delete cfg :repositories "ci3")


  ;; (curl {} "api/v1/namespaces/default/pods/aitem-hook-test-d40a2375646990b2dec75e80cf97ce5a8a77a199/log")

  ;; (create cfg :builds
  ;;         {:kind "Build"
  ;;          :apiVersion "ci3.io/v1"
  ;;          :metadata {:name "test-00"}})
  ;; (-> @(http-client/get
  ;;       (url cfg (str "api/v1/namespaces/default/pods"))
  ;;       {:insecure? true
  ;;        :headers (merge default-headers {"Content-Type" "application/json"})})
  ;;     :body
  ;;     (json/parse-string))

  ;; #_(query {:apiVersion "zeroci.io/v1"
  ;;           :ns "default"}
  ;;          :builds "test-1")

  ;; #_(query {:apiVersion "zeroci.io/v1"
  ;;           :ns "default"}
  ;;          :builds "test-1")



  (require '[clj-yaml.core :as yaml])

  (spit "/tmp/pods.yaml"
        (yaml/generate-string
         (query {:prefix "api"
                 :ns "pg3"
                 :apiVersion "v1"}
                :pods)))

  (spit "/tmp/pods.yaml"
        (yaml/generate-string
         (query {:prefix "api"
                 :ns "pg3"
                 :apiVersion "v1"}
                :persistentvolumes)))
  )

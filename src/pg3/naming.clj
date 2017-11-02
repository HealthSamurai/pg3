(ns pg3.naming
  (:require [clojure.string :as str]))

;; The system is very sensitive to naming changes
;; be carefull and backward compatible here

(def api-group "pg3.io")
(def api-version "v1")
(def api (str api-group "/" api-version))

(def cluster-resource-name (str "pgs." api-group))
(def cluster-resource-kind "Pg")
(def cluster-resource-plural "pgs")

(def instance-resource-name (str "pginstances." api-group))
(def instance-resource-kind "PgInstance")
(def instance-resource-plural "pginstances")

(def colors ["lightseagreen" "dodgerblue" "cornflowerblue" "blue" "gray" "dimgray" "aqua" "floralwhite" "burlywood" "deepskyblue" "deeppink" "cornsilk" "darkviolet" "firebrick" "black" "blanchedalmond" "gainsboro" "greenyellow" "darkturquoise" "beige" "coral" "ghostwhite" "aquamarine" "brown" "indigo" "forestgreen" "lightgoldenrodyellow" "antiquewhite" "chocolate" "cadetblue" "slategrey" "lightslategray" "navy" "indianred" "chartreuse" "mediumpurple" "aliceblue" "mediumspringgreen" "lightskyblue" "maroon" "mediumblue" "bisque" "navajowhite" "green" "lightyellow" "lightcyan" "magenta" "mintcream" "mediumturquoise" "oldlace" "peru" "blueviolet" "azure" "darkseagreen" "lightslategrey" "lightsteelblue" "slateblue" "fuchsia" "palevioletred" "lawngreen" "rosybrown" "mediumorchid" "lemonchiffon" "pink" "red" "lavender" "plum" "goldenrod" "silver" "lime" "linen" "grey" "mediumseagreen" "darkgrey" "salmon" "darkgreen" "midnightblue" "powderblue" "palegoldenrod" "purple" "mediumaquamarine" "sienna" "dimgrey" "lavenderblush" "saddlebrown" "snow" "khaki" "mediumslateblue" "turquoise" "seagreen" "darkslategray" "ivory" "orangered" "royalblue" "mistyrose" "darkgray" "gold" "yellow" "slategray" "darkkhaki" "limegreen" "hotpink" "moccasin" "yellowgreen" "teal" "lightsalmon" "thistle" "darkmagenta" "white" "lightpink" "wheat" "lightgrey" "sandybrown" "darkcyan" "lightblue" "olive" "steelblue" "honeydew" "lightgray" "mediumvioletred" "violet" "papayawhip" "olivedrab" "cyan" "crimson" "lightcoral" "springgreen" "whitesmoke" "darkslategrey" "lightgreen" "darkgoldenrod" "tan" "orange" "darkblue" "darkorchid" "palegreen" "skyblue" "seashell" "darkslateblue" "orchid" "darksalmon" "rebeccapurple" "darkred" "paleturquoise" "peachpuff" "darkorange" "darkolivegreen" "tomato"])

(def data-path "/data")
(def wals-path "/wals")
(def config-path "/config")

(defn resource-name [x]
  (get-in x [:metadata :name]))

(defn cluster-name [cluster]
  (str "pg3-" (resource-name cluster)))

(defn config-map-name [cluster-name]
  (str "pg3-" cluster-name))

(defn secret-name [cluster-name]
  (str "pg3-" cluster-name))

(defn service-name [cluster-name]
  (str "pg3-" cluster-name))

(defn instance-name [cluster color]
  (str (cluster-name cluster) "-" color))

(defn data-volume-name [inst-spec]
  (str (resource-name inst-spec) "-data"))

(defn wals-volume-name [inst-spec]
  (str (resource-name inst-spec) "-wals"))

(defn deployment-name [inst-spec]
  (resource-name inst-spec))

(defn pod-name [inst-spec & postfix]
  (str (resource-name inst-spec)
       (when postfix
         (str "-" (str/join "-" postfix)))))

(defn cluster-labels [cluster]
  {:system "pg3"
   :service (cluster-name cluster)})

(defn instance-labels [role color]
  {:pgrole role
   :color color})

(defn master-service-selector [cluster-name]
  {:service (str "pg3-" cluster-name) 
   :pgrole "master"})

(defn replica-service-name [inst-spec]
  (resource-name inst-spec))

(defn replica-service-selector [cluster clr]
  {:service (cluster-name cluster)
   :color clr
   :pgrole "replica"})

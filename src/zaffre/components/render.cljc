(ns zaffre.components.render
  (:require [clojure.test :refer [is]]
            [taoensso.timbre :as log]
            [zaffre.components :as zc]
            [zaffre.terminal :as zt]))


;; Env functions
(defn get-props-in-env [env k]
  (if (nil? env)
    nil
    (get-in env [:props k] (get-props-in-env (get env :parent) k))))

(defn wrap-env [parent child key]
  {
    :key key
    ;:env/child child
    ;:env/props (assoc (second child)
    ;                  :children (drop 2 child))
    :parent parent
  })

(defn env-path [env]
  (if (nil? env)
    []
    (cons (get env :key) (env-path (get env :parent)))))

(defn env-path->str [env]
  (apply str (interpose " > " (reverse (env-path env)))))

;; Primitive elements
(defprotocol PrimitiveElement
  (element-type [this]))
(defrecord Text [text]
  PrimitiveElement
  (element-type [_] :text))
(defrecord View [x y fg bg children]
  PrimitiveElement
  (element-type [_] :view))
(defrecord Layer [layer-id children]
  PrimitiveElement
  (element-type [_] :layer))
(defrecord Group [group-id x y children]
  PrimitiveElement
  (element-type [_] :group))
(defrecord Groups [children]
  PrimitiveElement
  (element-type [_] :groups))
;; Compositing functions
;; {:x x :y y :width w :height h :cells [...]}
(defrecord Cell [c fg bg])
(defn cell [c fg bg]
  {:pre [(is (char? c) "c is not a char")
         (is (seq? fg) "fg is not a seq")
         (is (= (count fg) 3) "fg does not have 3 elements")
         (is (every? int? fg) "not every element in fg is an int")
         (is (seq? bg) "bg is not a seq")
         (is (= (count bg) 3) "bg does not have 3 elements")
         (is (every? int? bg) "not every element in bg is an int")]}
  (->Cell c fg bg))

(defprotocol BoxModel
  (width [this])
  (height [this]))
  
(defrecord Tiles [x y cells]
  BoxModel
  (width [_] (count (first cells)))
  (height [_] (count cells)))

(defn tiles [x y cells]
  {:pre [(is (number? x) "x not number")
         (is (pos? x) "x not positive")
         (is (number? y) "y not number")
         (is (pos? y) "y not positive")
         (is (seq? cells) "cells not a sequence")
         (is (every? seq? cells) "cells is not composed of sequences")
         (is (every? (partial every? (partial instance? Cell)) cells) "element in cells not a Cell")]}
  (->Tiles x y cells))

(defn- blit! [dest tiles]
  "Blits the cells in tiles into dest array"
  #_{:pre [(is (instanceof? java.lang.Array dest) "dest not array")]}
  (let [tx (.x tiles)
        ty (.y tiles)]
    (doseq [[y lines] (map-indexed vector (.cells tiles))
            [x cell]  (map-indexed vector lines)
            :let [i (+ x tx (* (+ y ty) (width tiles)))]]
      (aset dest i cell))))

(def primitive-elements #{:terminal :group :layer :view :string})
(defn primitive? [component]
  (or (string? component)
      (contains? primitive-elements (first component))))

;; Recursively calculate dimensions based on size of child components
;; Finds env in render-state and returns it. If not found,
;; evaluates body and caches result in render-state
(defmacro caching-render [component render-state & body]
  `(let [component# ~component
         render-state# ~render-state
         k# (if (string? component#)
              component#
              (let [type# (first component#)
                    props# (second component#)]
                [type# props#]))]
     (log/trace "render-state val" render-state# (contains? (deref render-state#) k#))
     ;; either get the cached value, or generate, cache and return it
     ;; use or instead of default value for get because get's default value
     ;; is eagerly evaluaged and will swap! every time
     (or
       (get (deref render-state#) k#)
       (let [result# (do ~@body)]
         (when (> (count (deref render-state#)) 10000)
           (reset! render-state# {}))
         (log/trace "swap!-ing render-state" render-state# k#)
         (swap! render-state# #(assoc % k# result#))
         (log/trace "new render-state" (deref render-state#))
         result#))))

(declare render)
(defn render-primitive [env render-state component]
  (log/trace "render-primitive" (env-path->str env) component)
  (caching-render component render-state
    (if (string? component)
      component
      (let [[type props & children] component
            wrapped-env (wrap-env env component type)]
        [type
         (assoc props
                :children
                (vec (map-indexed
                       (fn [index child]
                         (render
                           (wrap-env
                             env
                             child
                             (get props :key index))
                           render-state
                           child))
                       children)))]))))
    
(defn render-composite [env render-state component]
  (log/trace "render-composite" (env-path->str env) component)
  (caching-render component render-state
    (if (string? component)
      (zc/render-comp component {})
      (let [type     (first component)
            props    (or (second component) {})
            children (drop 2 component)
            element  (zc/render-comp type (assoc props :children children))]
        (render env render-state element)))))

(defn render
  #_([component]
    (render nil (atom {}) component))
  ([render-state component]
    (render nil render-state component))
  ([env render-state component]
    (log/trace "render" (env-path->str env) component)
    (let [env (wrap-env env component (first component))]
      (caching-render component render-state
        ;; render the component
        (if (primitive? component)
          (render-primitive env render-state component)
          (render-composite env render-state component))))))

(defn merge-envs [env other]
  {:fg (get env :fg (get other :fg))
   :bg (get env :bg (get other :bg))
   :x  (+ (get env :x 0) (get other :x 0))
   :y  (+ (get env :y 0) (get other :y 0))})

(defn render-string-into-container [target env s]
  (let [{:keys [x y fg bg]} env
        target-line (aget target y)]
    (loop [index 0 s s]
      (when (seq s)
        (aset target-line (+ x index) {:c (first s) :fg fg :bg bg})
        (recur (inc index) (rest s))))))

(defn render-layer-into-container
  ([target layer]
    (let [default-style {:x 0 :y 0 :fg [255 255 255 255] :bg [0 0 0 255]}
          [_ {:keys [children]}] layer]
      (doseq [child children]
        (render-layer-into-container target default-style child))))
  ([target env component]
      (cond
        ;; render raw strings
        (string? component)
          (render-string-into-container target env component)
        ;; render wrapped strings
        (= :string (first component))
          (let [[type {:keys [children]} :as props] component]
            (doseq [child children]
              (render-string-into-container target env child)))
        ;; render views
        (= :view (first component))
          (let [[type {:keys [children]} :as props] component
                merged-env (merge-envs env props)]
            (doseq [child children]
              (render-layer-into-container target merged-env child)))
        ;; die on other components
        :default
          (assert false (format "Found unknown component %s" component)))))
                    

;; Renders component into container. Does not call refresh! on container
(defn render-into-container
  [container render-state component]
  (let [[type {:keys [children]} :as terminal-element] (render render-state component)
        groups children
        group-info (zt/groups container)]
    (assert (= type :terminal)
            (format "Root component not :terminal found %s instead" type))
    ;; for each group in terminal
    (doseq [[type {:keys [group-id pos children]}] groups
            :let [layers children
                  {:keys [columns rows]} (get group-info group-id)]]
      (assert (= type :group)
              (format "Expected :group found %s instead" type))
      ;; update group pos
      (when pos
        (zt/alter-group-pos! container group-id pos))
      ;; for each layer in group
      (doseq [[type {:keys [layer-id]} :as layer] layers]
        (assert (= type :layer)
                (format "Expected :layer found %s instead" type))
        ;; create a container to hold cells
        (let [layer-container (object-array rows)]
          (doseq [i (range rows)]
            (aset layer-container i (object-array columns)))
          ;; render layer into layer-container
          (render-layer-into-container layer-container layer)
          ;; replace chars in layer
          (zt/replace-chars! container layer-id layer-container))))))

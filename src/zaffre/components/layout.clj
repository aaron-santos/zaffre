(ns zaffre.components.layout
  (:require [clojure.test :refer [is]]
            [taoensso.timbre :as log]
            [cashmere.core-graal :as cm]
            [zaffre.components :as zc]
            [zaffre.text :as ztext])
  (:import (org.lwjgl.system MemoryStack)
           (org.lwjgl.util.yoga
             YGMeasureFunc
             YGMeasureFuncI
             YGLogger
             YGLoggerI
             YGSize
             Yoga)))

(defn parse-percent [s]
  (Float/parseFloat (subs s 0 (dec (count s)))))

(defmulti measure-text (fn [width width-mode height height-mode text]
  (cond
    (and (= width-mode Yoga/YGMeasureModeUndefined)
         (= height-mode Yoga/YGMeasureModeUndefined))
      :unrestricted
    (= width-mode Yoga/YGMeasureModeUndefined)
      :width-restricted
    (= height-mode Yoga/YGMeasureModeUndefined)
      :height-restricted
    :default
      :bi-restricted)))

(defmethod measure-text :unrestricted
  [width width-mode height height-mode text]
    #_(log/info "measure-text :unrestricted" width height text)
    [(count text) 1])

(defmethod measure-text :width-restricted
  [width width-mode height height-mode text]
    #_(log/info "measure-text :width-restricted" width height text)
    [width (count (ztext/word-wrap width text))])

;; TODO: Use https://cstheory.stackexchange.com/questions/10385/break-text-evenly-into-certain-number-of-lines
(defmethod measure-text :height-restricted
  [width width-mode height height-mode text]
    #_(log/info "measure-text :height-restricted" width height text)
    (let [words (clojure.string/split (clojure.string/trim text) #"\s+")]
      ;; Split by number of words and then find max line length
      [(reduce max 0 (map count (partition height words))) height]))

;; From
;; https://github.com/lunarraid/react-pixi-layout/blob/ed3f697a0cfa7c0430cf6989feecaecc99fb7135/src/elements/Text.js#L30
(defmethod measure-text :bi-restricted
  [width width-mode height height-mode text]
    #_(log/info "measure-text :bi-restricted" width height text)
    (let [wrapped (ztext/word-wrap width text)
          measure [(reduce max 0 (map count wrapped))
                   (count (ztext/word-wrap width text))]]
      #_(log/ifo "measure-text :bi-restricted " measure)
      ;; Split by number of words and then find max line length
      measure))

(defn text-measure-func [text]
  (YGMeasureFunc/create (reify YGMeasureFuncI
    (invoke [_ node width width-mode height height-mode]
      (let [[result-width result-height] (measure-text width width-mode height height-mode text)]
        (with-open[stack (MemoryStack/stackPush)]
            (-> (YGSize/mallocStack stack)
                (.width result-width)
                (.height result-height)
                YGMeasureFunc/toLong)))))))

(def text-measure-func-memo (memoize text-measure-func))

(defn yoga-align [value]
  (case value
    :auto Yoga/YGAlignAuto
    :flex-start Yoga/YGAlignFlexStart
    :center Yoga/YGAlignCenter
    :flex-end Yoga/YGAlignFlexEnd
    :stretch Yoga/YGAlignStretch
    :baseline Yoga/YGAlignBaseline
    :space-between Yoga/YGAlignSpaceBetween
    :space-around Yoga/YGAlignSpaceAround
    (assert false (str value " not a valid yoga alignment value"))))

(defn yoga-edge [value]
  (case value
    (:border-left :margin-left :padding-left :left)
      Yoga/YGEdgeLeft
    (:border-top :margin-top :padding-top :top)
      Yoga/YGEdgeTop
    (:border-right :margin-right :padding-right :right)
      Yoga/YGEdgeRight
    (:border-bottom :margin-bottom :padding-bottom :bottom)
      Yoga/YGEdgeBottom
    (:border-start :margin-start :padding-start :start)
      Yoga/YGEdgeStart
    (:border-end :margin-end :padding-end :end)
      Yoga/YGEdgeEnd
    (:border-horizontal :margin-horizontal :padding-horizontal)
      Yoga/YGEdgeHorizontal
    (:border-vertical :margin-vertical :padding-vertical)
      Yoga/YGEdgeVertical
    (:border :margin :padding)
      Yoga/YGEdgeAll
    (assert false (str value " not a valid yoga edge value"))))

(defn yoga-flex-direction [value]
  (case value
    :column Yoga/YGFlexDirectionColumn
    :column-reverse Yoga/YGFlexDirectionColumnReverse
    :row Yoga/YGFlexDirectionRow
    :row-reverse Yoga/YGFlexDirectionRowReverse
    (assert false (str value " not a valid yoga edge value"))))

(defn yoga-justify [value]
  (case value
    :flex-start Yoga/YGJustifyFlexStart
    :center Yoga/YGJustifyCenter
    :flex-end Yoga/YGJustifyFlexEnd
    :space-between Yoga/YGJustifySpaceBetween
    :space-around Yoga/YGJustifySpaceAround
    ; TODO findout why this field cannot be found
    ; :space-evenly Yoga/YGJustifySpaceEvenly
    (assert false (str value " not a valid yoga justify value"))))

(defn yoga-overflow [value]
  (case value
    :visible Yoga/YGOverflowVisible
    :hidden Yoga/YGOverflowHidden
    :scroll Yoga/YGOverflowScroll
    (assert false (str value " not a valid yoga overflow value"))))

(defn yoga-display [value]
  (case value
    :flex Yoga/YGDisplayFlex
    :none Yoga/YGDisplayNone
    (assert false (str value " not a valid yoga display value"))))

(defn yoga-position-type [value]
  (case value
    :relative Yoga/YGPositionTypeRelative
    :absolute Yoga/YGPositionTypeAbsolute
    (assert false (str value " not a valid yoga position type value"))))

(defn yoga-wrap [value]
  (case value
    :no-wrap Yoga/YGWrapNoWrap
    :wrap Yoga/YGWrapWrap
    :wrap-reverse Yoga/YGWrapReverse
    (assert false (str value " not a valid yoga wrap value"))))

(defn copy-property [style k type f]
  (when-let [value (get style k)]
    #_(log/info "copying property" k value)
    (f (type value))))

(defn copy-property-or-percent [style k type f f-percent]
  (when-let [value (type (get style k))]
    #_(log/info "copying property" k value)
    (cond
      (number? value)
        (f value)
      (and (string? value)
           (clojure.string/ends-with? value "%"))
        (let [value-percent (parse-percent value)]
          (f-percent value-percent)))))

(defn style-keys [prefix style]
  (filter (fn [k] (clojure.string/starts-with? (name k) prefix))
          (keys style)))

(defn border-keys [style]
  (remove #{:border-style
            :border-color
            :border-color-top
            :border-color-bottom
            :border-color-left
            :border-color-right} (style-keys "border" style)))
         
(defn margin-keys [style]
  (style-keys "margin" style))

(defn padding-keys [style]
  (style-keys "padding" style))

(defn position-keys [style]
  (keys (select-keys style [:top :bottom :left :right])))

(defn style-node [style node]
  ;; Alignment
  (copy-property style :align-content yoga-align
    #(Yoga/YGNodeStyleSetAlignContent node %))

  (copy-property style :align-items yoga-align
    #(Yoga/YGNodeStyleSetAlignItems node %))

  (copy-property style :align-self yoga-align
    #(Yoga/YGNodeStyleSetAlignSelf node %))

  ;; Border
  (doseq [border-key (border-keys style)]
    (copy-property style border-key identity
      #(Yoga/YGNodeStyleSetBorder node (yoga-edge border-key) %)))

   ;; Flex
  (copy-property-or-percent style :flex-basis identity
    #(Yoga/YGNodeStyleSetFlexBasis node %)
    #(Yoga/YGNodeStyleSetFlexBasisPercent node %))

  (copy-property style :flex-grow identity
    #(Yoga/YGNodeStyleSetFlexGrow node %))

  (copy-property style :flex-shrink identity
    #(Yoga/YGNodeStyleSetFlexShrink node %))

  (copy-property style :flex-direction yoga-flex-direction
    #(Yoga/YGNodeStyleSetFlexDirection node %))

  (copy-property style :flex-wrap yoga-wrap
    #(Yoga/YGNodeStyleSetFlexWrap node %))

    ;; Size
  (copy-property-or-percent style :width identity
    #(Yoga/YGNodeStyleSetWidth node %)
    #(Yoga/YGNodeStyleSetWidthPercent node %))

  (copy-property-or-percent style :min-width identity
    #(Yoga/YGNodeStyleSetMinWidth node %)
    #(Yoga/YGNodeStyleSetMinWidthPercent node %))

  (copy-property-or-percent style :max-width identity
    #(Yoga/YGNodeStyleSetMaxWidth node %)
    #(Yoga/YGNodeStyleSetMaxWidthPercent node %))

  (copy-property-or-percent style :height identity
    #(Yoga/YGNodeStyleSetHeight node %)
    #(Yoga/YGNodeStyleSetHeightPercent node %))

  (copy-property-or-percent style :min-height identity
    #(Yoga/YGNodeStyleSetMinHeight node %)
    #(Yoga/YGNodeStyleSetMinHeightPercent node %))

  (copy-property-or-percent style :max-height identity
    #(Yoga/YGNodeStyleSetMaxHeight node %)
    #(Yoga/YGNodeStyleSetMaxHeightPercent node %))

    ;; Position
  (when (contains? #{:absolute :relative} (get style :position))
    (copy-property style :position yoga-position-type
      #(Yoga/YGNodeStyleSetPositionType node %)))

  #_(log/info "style" style "position-keys" (vec (position-keys style)))
  (doseq [position-key (position-keys style)]
    #_(log/info "setting position property" position-key (get style position-key) node)
    (copy-property-or-percent style position-key identity
      #(Yoga/YGNodeStyleSetPosition node (yoga-edge position-key) %)
      #(Yoga/YGNodeStyleSetPositionPercent node (yoga-edge position-key) %)))

    ;; Margin
  (doseq [margin-key (margin-keys style)]
    (copy-property style margin-key identity
      (fn [margin]
          (cond
            (number? margin)
              (Yoga/YGNodeStyleSetMargin node (yoga-edge margin-key) margin)
            (and (string? margin)
                 (clojure.string/ends-with? margin"%"))
              (let [margin-percent (parse-percent margin)]
                (Yoga/YGNodeStyleSetMarginPercent node (yoga-edge margin-key) margin-percent))
            (= :auto)
              (Yoga/YGNodeStyleSetMarginAuto node (yoga-edge margin-key))))))
            
    ;; Padding
  (doseq [padding-key (padding-keys style)]
    (copy-property-or-percent style padding-key identity
      #(Yoga/YGNodeStyleSetPadding node (yoga-edge padding-key) %)
      #(Yoga/YGNodeStyleSetPaddingPercent node (yoga-edge padding-key) %)))

  (copy-property style :overflow yoga-overflow
    #(Yoga/YGNodeStyleSetOverflow node %))

  ;; TODO
  ;  void setDirection(YogaDirection direction) {
  ;      YGNodeStyleSetDirection(node, direction.value);

  ;  void setDisplay(YogaDisplay display) {
  ;      YGNodeStyleSetDisplay(node, display.value);
  (copy-property style :display yoga-display
    #(Yoga/YGNodeStyleSetDisplay node %))

  (copy-property style :justify-content yoga-justify
    #(Yoga/YGNodeStyleSetJustifyContent node %)))

(defn build-yoga-tree [max-width max-height parent index element]
  "Takes Instance elements and annotates them with yoga nodes like
   using metadata. Yoga nodes are stored in metadata with the key :node.
   Does this recursively to the element's children"
  #_(log/info "build-yoga-tree" index element)
  (let [{:keys [element-type
                props
                children]
          :as styled-element} (if (nil? parent)
                                (-> element
                                  (assoc-in [:props :style :width] max-width)
                                  (assoc-in [:props :style :height] max-height))
                                element)
        {:keys [style]} props
        children @children
        node (Yoga/YGNodeNew)]
    (Yoga/YGNodeStyleSetFlexDirection node Yoga/YGFlexDirectionColumn)
    

    ;; set style
    (style-node style node)
    #_(log/info "build-yoga-tree children" children)

    (when parent
      (Yoga/YGNodeInsertChild parent node index))
 
    (when (= element-type :text)
      (Yoga/YGNodeSetMeasureFunc node (text-measure-func-memo
                                        (ztext/text element))))

    (when (= element-type :img)
      (let [{:keys [width height]} props]
        #_(log/info "img style width x height: " width "x" height)
        (Yoga/YGNodeStyleSetWidth node width)
        (Yoga/YGNodeStyleSetHeight node height)))

    #_(log/info "node" node)
    ;; don't recurse into :text and :img
    (let [result
      (with-meta
        (if (or (= element-type :text) (= element-type :img))
          styled-element
          (cm/reset-children! styled-element
            (if children
              ;; force eager evaluation so that entire tree is created
              (vec (map-indexed (fn [index child]
                (if (record? child)
                  (let [child-props (:props child)]
                    ;; When position == fixed, detatch child and position separately
                    (if (= (get-in child-props [:style :position]) :fixed)
                      (build-yoga-tree max-width max-height nil index (assoc-in child [:props :style :position] :absolute))
                      (build-yoga-tree max-width max-height node index child)))
                  child))
                children))
              []))) {:node node})]
      ;; if no parent, then calculate layout before returning
      (when (nil? parent)
        #_(log/info "build-yoga-tree YGNodeCalculateLayout" result)
        (Yoga/YGNodeCalculateLayout node Yoga/YGUndefined Yoga/YGUndefined Yoga/YGDirectionLTR))
      result)))

(defn transfer-layout
  "Traverses the yoga tree updating the element portion with
   layout information and frees the yoga node. layout-x and layout-y
   are in absolute coordintes"
  ([yoga-tree]
    (transfer-layout 0 0 yoga-tree))
  ([parent-layout-x parent-layout-y yoga-tree]
    {:post [(some? %)]}
    #_(log/info "transfer-layout" yoga-tree)
    (if-let [node (-> yoga-tree meta :node)]
      (let [{:keys [element-type props host-dom]} yoga-tree
            style (get props :style)
            layout (get style :layout-type :static)
            left (Yoga/YGNodeLayoutGetLeft node)
            top  (Yoga/YGNodeLayoutGetTop node)
            [layout-x layout-y] (case layout
                                  :fixed
                                  [(get style :left 0)
                                   (get style :top 0)]
                                  :absolute
                                  ;; TODO use :right/:bottom and handle both cases
                                  [(+ parent-layout-x (get style :left 0))
                                   (+ parent-layout-y (get style :top 0))]
                                  ; default
                                  [(+ parent-layout-x left)
                                   (+ parent-layout-y top)])
            layout-width (Yoga/YGNodeLayoutGetWidth node)
            layout-height (Yoga/YGNodeLayoutGetHeight node)
            layout {:x layout-x
                    :y layout-y
                    :width layout-width
                    :height layout-height}
            props-with-layout (assoc props
                                :zaffre/layout layout)]
        (assert layout (str element-type props))
        #_(log/info "copying" element-type (get props :key))
        #_(log/info "layout params" {:left left :top top
                                   :layout-x layout-x :layout-y layout-y
                                   :layout-width layout-width :layout-height layout-height})
        #_(log/info "freeing" node)
        (Yoga/YGNodeFree node)
        ; set host-dom so that refs have access to layouts
        #_(log/info "setting host-dom" host-dom layout)
        (reset! host-dom layout)
        (-> yoga-tree
          ; copy layout into props so that render code has access to immutable version of layout
          (assoc :props props-with-layout)
          (cm/swap-children! 
           (fn [children]
             (if children
               (mapv (partial transfer-layout layout-x layout-y)
                     children)
               [])))))
      yoga-tree)))

(def yoga-logger
  (YGLogger/create (reify YGLoggerI
    (invoke [_ config node level format args]
        (log/info "Got Yoga Log" config node level format)
        #_(let [print-fn (Yoga/YGNodeGetPrintFunc node)]
          (.invoke print-fn node))
        (int 0)
        #_(with-open[stack (MemoryStack/stackPush)]
            (-> (YGSize/mallocStack stack)
                (.width result-width)
                (.height result-height)
                YGMeasureFunc/toLong))))))

(defn layout-element [element]
  {:pre [(some? element)]
   :post [(some? %)]}
  (let [width (get-in element [:props :style :width])
        height (get-in element [:props :style :height])
        yoga-config (Yoga/YGConfigNew)]
    (try
      (Yoga/YGConfigSetLogger yoga-config yoga-logger)
      (let [yoga-tree (build-yoga-tree width height nil nil element)]
        #_(log/info "yoga-tree" yoga-tree)
        (transfer-layout yoga-tree))
      (catch Throwable t
        (log/error t)
        (Yoga/YGConfigFree yoga-config)))))


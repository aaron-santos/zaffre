;; Functions for rendering state to screen
(ns zaffre.terminal)

(defprotocol Terminal
  "Methods suffixed with ! indicate a change of state within the terminal. refresh! and destroy! are not transaction-safe and must not be called from within a transaction."
  (args [this] "Returns the arguments passed to create-terminal.")
  (alter-group-pos! [this group-id pos-fn] "Change the [x y] position of a layer group. `x` and `y` are mesured in pixels from the upper left corner of the screen.")
  (alter-group-font! [this group-id font-fn] "Changes the font for a layer group. `font-fn` is a function that takes one argument: one of :linux :macosx or :windows, and returns a font.")
  (put-chars! [this layer-id characters] "Changes the characters in a layer. `characters` is a sequence where each element is a map and must have these keys: :c - a character or keyword, :x int, column, :y int row, :fg [r g b], :bg [r g b] where r,g,b are ints from 0-255.")
  (set-fg! [this layer-id x y fg] "Changes the foreground color of a character in a layer.")
  (set-bg! [this layer-id x y bg] "Changes the background color of a character in a layer.")
  (assoc-shader-param! [this k v] "Changes the value of a uniform variable in the post-processing shader.")
  (pub [this] "Returns a clojure.core.async publication partitioned into these topics: :keypress :mouse-down :mouse-up :click :mouse-leave :mouse-enter :close.")
  (refresh! [this] "Uses group and layer information to draw to the screen.")
  (clear! [this]
          [this layer-id] "Clears all layers or just a specific layer.")
  (fullscreen! [this v] "Changes the terminal to fullscreen mode if v is a value returned by fullscreen-sizes. If false is supplied the terminal will revert to windowed mode.")
  (fullscreen-sizes [this] "Returns a list of fullscreen values.")
  (set-fx-fg! [this layer-id x y fg] "Overrides the forecround color of a character.")
  (set-fx-bg! [this layer-id x y bg] "Overrides the background color of a character.")
  (set-fx-char! [this layer-id x y c] "Overrides the character on a layer.")
  (clear-fx! [this]
             [this layer-id] "Clears all of the fx overrides or just those for a layer.")
  (destroy! [this] "Stops the terminal, and closes the window."))

;; namespace with only a protocol gets optimized out, causing missing dependencies.
;; add a dummp def to prevent this ns from being optimized away.
#?(:cljs
(def x 1))
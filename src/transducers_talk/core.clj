(ns transducers-talk.core
  (:require [clojure.core.async :as a]
            [clojure.repl :refer [doc source]]))



















































(def my-ints (range 20))

;; Take only the odd integers from `my-ints` and return a vector of each of them doubled.
;; Do this without allocating any intermediate sequences.



















































(def times2 #(* % 2))

;; "Old school" way

(->> my-ints
     (filter odd?)
     (map times2)
     vec)




















































(->> my-ints
     (filter odd?) ; intermediate sequence
     (map times2) ; intermediate sequence
     vec)



















































(reduce (fn [nums x]
          (if (odd? x)
            (conj nums (times2 x))
            nums))
        [] my-ints)



















































;; Want these to be _composable_

;; conj -> conj only odd values
;;      -> conj only odd values and times2 those
;; Want functions that transform conj to a new _reducing function_



















































(defn conj-when-odd [nums v]
  (if (odd? v)
    (conj nums v)
    nums))

(reduce conj-when-odd [] my-ints)



















































(defn filter-odd [rf]
  (fn [nums v]
    (if (odd? v)
      (rf nums v)
      nums)))

(reduce (filter-odd conj) [] my-ints)



















































(reduce (filter-odd +) 0 my-ints)



















































(defn map-times2 [rf]
  (fn [nums v]
    (rf nums (times2 v))))

(reduce (map-times2 conj) [] my-ints)



















































(reduce (map-times2 (filter-odd conj)) [] my-ints)



















































;; Replace rf with (filter-odd conj) = conj-when-odd
(fn [nums v]
  (conj-when-odd nums (times2 v)))



















































;; We're calling times2 first! Transducers on the "outside" are called first

(reduce (filter-odd (map-times2 conj)) [] my-ints)

(reduce (filter-odd (map-times2 +)) 0 my-ints)



















































(reduce ((comp filter-odd
               map-times2)
         conj)
        []
        my-ints)

(transduce (comp filter-odd
                 map-times2)
           conj [] my-ints)



















































(defn filter-odd [rf]
  (fn
    ([] (rf))
    ([result] (rf result))
    ([result v]
     (if (odd? v)
       (rf result v)
       result))))

(defn map-times2 [rf]
  (fn
    ([] (rf))
    ([result] (rf result))
    ([result v]
     (rf result (times2 v)))))



















































(transduce (comp (filter odd?)
                 (map times2))
           + 0 my-ints)

(transduce (comp (filter odd?)
                 (map times2))
           conj [] my-ints)

(into []
      (comp (filter odd?)
            (map times2))
      my-ints)



















































(def filter-odd-and-times2-xf (comp (filter odd?)
                                    (map times2)))

(transduce filter-odd-and-times2-xf
           + 0 my-ints)

(into [] filter-odd-and-times2-xf my-ints)



















































(def ch (a/chan 1 filter-odd-and-times2-xf))

(a/onto-chan ch my-ints true)

(loop []
  (let [val (a/<!! ch)]
    (when val
      (println val)
      (recur))))



















































(defn string-append
  ([] (StringBuilder.))
  ([acc] (.toString acc))
  ([acc input] (.append acc input)))

(transduce filter-odd-and-times2-xf
           string-append my-ints)

(transduce (comp filter-odd map-times2 (interpose ","))
           string-append my-ints)

(def filter-odd-and-times2-and-remove-even-indices-xf
  (comp (filter odd?)
        (map times2)
        (map-indexed (fn [idx v] [idx v]))
        (remove (fn [[idx v]] (even? idx)))
        (map (fn [[_ v]] v))))

(into [] filter-odd-and-times2-and-remove-even-indices-xf my-ints)

(transduce filter-odd-and-times2-and-remove-even-indices-xf + 0 my-ints)



















































;; You can still use transducers with lazy sequences!

(take 10 (sequence filter-odd-and-times2-and-remove-even-indices-xf (range)))

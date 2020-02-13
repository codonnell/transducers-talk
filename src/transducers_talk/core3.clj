(ns transducers-talk.core3
  (:require [clojure.core.async :as a]))

;; What is transducer? A transducer is a function that takes a reducing function and returns a reducing function.

;; What is a reducing function? A function that you would pass to `reduce`

(reduce conj [] (range 5))

(reduce + 0 (range 5))

;; A reducing function takes an accumulated value and an item and returns a new accumulated value

(defn conj-when-odd [result input]
  (if (odd? input)
    (conj result input)
    result))

(reduce conj-when-odd [] (range 5))

;; Why don't you just use filter?

(reduce conj [] (filter odd? (range 5)))

(reduce conj [] (map #(* % 2) (filter odd? (range 5))) )

(->> (range 5)
  (filter odd?)
  (map #(* % 2))
  (reduce conj []))

(->> (range 5)
  (filter odd?)
  (map #(* % 2))
  (reduce + 0))

;; Nice properties:
;; * Composable wrt. reducing function
;; * Composable wrt. sequence transformation

;; This is a transducer!
(defn filter-odd [rf]
  (fn [result input]
    (if (odd? input)
      (rf result input)
      result)))

(reduce (filter-odd conj) [] (range 5))

(reduce (filter-odd +) 0 (range 5))

(defn map-double [rf]
  (fn [result input]
    (rf result (* input 2))))

(reduce (map-double (filter-odd conj)) [] (range 5))

(defn map-double [conj-when-odd] ; Replace rf with conj-when-odd
  (fn [result input]
    (conj-when-odd result (* input 2))))

;; Rule of thumb composing transducers: transducers compose from the outside in

(reduce (filter-odd (map-double conj)) [] (range 5))

(reduce (filter-odd (map-double +)) 0 (range 5))

;; EXERCISE: Make the return items are duplicated (x -> [x x])

(defn map-duplicated [rf]
  (fn [result input]
    (rf result [input input])))

(reduce (filter-odd (map-double (map-duplicated conj))) [] (range 5))
(reduce ((comp filter-odd map-double map-duplicated) conj) [] (range 5))

(defn map* [f]
  (fn [rf]
    (fn [result input]
      (rf result (f input)))))

(reduce ((comp filter-odd (map* #(* % 2)) (map* (fn [x] [x x]))) conj) [] (range 5))

(reduce ((comp (filter odd?) (map #(* % 2)) (map (fn [x] [x x]))) conj) [] (range 5))

(transduce (comp (filter odd?) (map #(* % 2)) (map (fn [x] [x x]))) conj [] (range 5))

(defn batman-rf
  ([] (StringBuilder.))
  ([sb]
   (-> sb (.append " BATMAN!") (.toString)))
  ([sb s]
   (.append sb s)))

(transduce (interpose "-") batman-rf (repeat 10 "na"))

(into [] (partition-all 3) (range 10))

(defn partition-all*
  [^long n]
  (fn [rf]
    (let [a (java.util.ArrayList. n)]
      (fn
        ([] (rf))
        ([result]
         (let [result (if (.isEmpty a)
                        result
                        (let [v (vec (.toArray a))]
                          ;;clear first!
                          (.clear a)
                          (unreduced (rf result v))))]
           (rf result)))
        ([result input]
         (.add a input)
         (if (= n (.size a))
           (let [v (vec (.toArray a))]
             (.clear a)
             (rf result v))
           result))))))

(into [] (partition-all* 3) (range 10))

;; EXERCISE: Implement `take` as a transducer

;; (def ch (a/chan 1 (comp (filter odd?) (map #(* % 2)) (map (fn [x] [x x])))))

;; (a/go
;;   (a/>! ch 3)
;;   (println "Put complete"))

;; (a/go
;;   (let [v (a/<! ch)]
;;     (println "Take complete" v)))

;; (a/close! ch)

;; PROBLEM: We have some streaming input coming in on a core.async channel. We need to batch that input so it can be processed more efficiently. But, we also need batches be flushed periodically. No item should remain waiting for its batch to fill for more than 5 seconds.

;; Proposed solution: use a transducer! (Write a custom transducer)
;; * Transducer behaves exactly like partition-all, but it also flushes the batch when it sees the input value `:flush`.
;; * Create a go loop that puts `:flush` into the channel every 5 seconds
;; * Immediately flush when you see `:urgent` item


(defn partition-all-with-flush
  [^long n]
  (fn [rf]
    (let [a (java.util.ArrayList. n)]
      (fn
        ([] (rf))
        ([result]
         (let [result (if (.isEmpty a)
                        result
                        (let [v (vec (.toArray a))]
                          ;;clear first!
                          (.clear a)
                          (unreduced (rf result v))))]
           (rf result)))
        ([result input]
         (when-not (= :flush input)
           (.add a input))
         (if (and (or (= :flush input )(= n (.size a)))
               (pos? (.size a)))
           (let [v (vec (.toArray a))]
             (.clear a)
             (rf result v))
           result))))))

(def flush-urgent (mapcat (fn [item]
                            (case item
                              :urgent [:urgent :flush]
                              [item]))))

(def ch (a/chan 1 (comp flush-urgent (partition-all-with-flush 3))))

(a/go
  (loop []
    (when-some [v (a/<! ch)]
      (println v)
      (recur))))

(a/go
  (loop []
    (a/>! ch :flush)
    (a/<! (a/timeout 5000))
    (recur)))

(a/onto-chan ch [0 1 2 3 :urgent] false)
(a/close! ch)


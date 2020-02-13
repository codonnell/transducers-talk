(ns transducers-talk.core
  (:require [clojure.core.async :as a]
            [clojure.repl :refer [doc source]]))

;; What is a transducer? A transducer is a function that takes a reducing function and returns a reducing function

;; What is a reducing function? A function that you would pass to `reduce`

(reduce conj [] (range 5))

(reduce + 0 (range 5))

;; A reducing function takes an accumulated value and an item, and returns a new accumulated value

(defn conj-when-odd [acc x]
  (if (odd? x)
    (conj acc x)
    acc))

(reduce conj-when-odd [] (range 5))

;; Why don't you use filter?

(reduce conj [] (filter odd? (range 5)))

(->> (range 5)
  (filter odd?)
  (reduce conj []))

(->> (range 5)
  (filter odd?)
  (map #(* % 2))
  (reduce conj []))

(->> (range 5)
  (filter odd?)
  (map #(* % 2))
  (reduce + 0))

;; How can we make the transformation reducing function-agnostic? How can we chain transformations?

;; This is a transducer!
(defn filter-odd [rf]
  (fn [acc x]
    (if (odd? x)
      (rf acc x)
      acc)))

(reduce (filter-odd conj) [] (range 5))

(reduce (filter-odd +) 0 (range 5))

(defn map-double [rf]
  (fn [acc x]
    (rf acc (* x 2))))

(reduce (map-double (filter-odd conj)) [] (range 5))

;; Replace rf with conj-when-odd
(defn map-double [conj-when-odd]
  (fn [acc x]
    (conj-when-odd acc (* x 2))))

(defn filter-odd [conj-double]
  (fn [acc x]
    (if (odd? x)
      (conj-double acc x)
      acc)))

(reduce (filter-odd (map-double conj)) [] (range 5))

(reduce (filter-odd (map-double +)) 0 (range 5))

;; Rule of thumb: transducers chain from the outside in

;; EXERCISE - Duplicate all the doubled values [2 6] -> [[2 2] [6 6]]

(defn map-duplicate [rf]
  (fn [acc x]
    (rf acc [x x])))

(reduce (filter-odd (map-double (map-duplicate conj))) [] (range 5))
(reduce ((comp filter-odd map-double map-duplicate) conj) [] (range 5))

(defn map* [f]
  (fn [rf]
    (fn [acc x]
      (rf acc (f x)))))

(reduce ((comp filter-odd (map* #(* % 2)) (map* (fn [x] [x x]))) conj) [] (range 5))

(defn filter* [pred]
  (fn [rf]
    (fn [acc x]
      (if (pred x)
        (rf acc x)
        acc))))

(reduce ((comp (filter* odd?) (map* #(* % 2)) (map* (fn [x] [x x]))) conj) [] (range 5))
(reduce ((comp (filter odd?) (map #(* % 2)) (map (fn [x] [x x]))) conj) [] (range 5))

(transduce (comp (filter odd?) (map #(* % 2)) (map (fn [x] [x x]))) conj [] (range 5))

;; transduce + conj = into

(into []
  (comp
    (filter odd?)
    (map #(* % 2))
    (map (fn [x] [x x])))
  (range 5))

(->> (range 5)
  (filter odd?)
  (map #(* % 2))
  (map (fn [x] [x x]))
  vec)

(defn batman-rf
  ([] (StringBuilder.))
  ([sb] (-> sb (.append " BATMAN!") (.toString)))
  ([sb x] (.append sb x)))

(transduce (interpose "A") batman-rf (repeat 10 "na"))

(->> "na"
  (repeat 10)
  (interpose "A")
  (apply str)
  (#(str % " BATMAN!!!")))

(transduce (partition-all 3) conj [] (range 5))

(defn partition-all
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

;; core.async mini-tutorial

;; (def ch (a/chan 1 (comp
;;                     (filter odd?)
;;                     (map #(* % 2))
;;                     (map (fn [x] [x x])))))

;; (a/go
;;   (a/>! ch 3)
;;   (println "Put complete"))

;; (a/go
;;   (let [v (a/<! ch)]
;;     (println "Take complete" v)))

;; (a/close! ch)

;; PROBLEM: We have some data streaming in on a core.async channel. We would like to batch that data for efficient processing, but we would like to ensure that data remains buffered in the channel for at most 5 seconds.

;; Solution: use a transducer!
;; * Write a transducer that acts like `partition-all` but flushes its buffer when it receives the item `:flush`
;; * Put `:flush` into the channel every 5 seconds

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
         (if (and (or (= :flush input) (= n (.size a)))
               (pos? (.size a)))
           (let [v (vec (.toArray a))]
             (.clear a)
             (rf result v))
           result))))))

(def flush-danger (mapcat (fn [x]
                            (if (= x :danger)
                              [x :flush]
                              [x]))))

(def ch (a/chan 5 (comp flush-danger (partition-all-with-flush 3))))

(a/go
  (loop []
    (a/>! ch :flush)
    (a/<! (a/timeout 5000))
    (recur)))

(a/go
  (loop [v (a/<! ch)]
    (println v)
    (when (some? v)
      (recur (a/<! ch)))))

(a/go
  (dotimes [_ 10]
    (a/>! ch 1))
  (a/>! ch :danger))

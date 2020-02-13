(ns transducers-talk.core2
  (:require [clojure.repl :refer [source]]
            [clojure.core.async :as a]))

;; Transducers are just functions that take a reducing function and return a reducing function.

;; What is a reducing function? A function you would pass to reduce.

(reduce conj [] (range 5))

(reduce + 0 (range 5))

(defn conj-when-odd [acc x]
  (if (odd? x)
    (conj acc x)
    acc))

(reduce conj-when-odd [] (range 5))

;; What is a reducing function? A function that takes an accumulated result and an item and returns a new accumulated result.

(reduce conj-when-odd 0 (range 5))

;; What about filter?

(reduce conj [] (filter odd? (range 5)))

(->> (range 5)
  (filter odd?)
  (reduce conj []))

(->> (range 5)
  (filter odd?)
  (reduce + 0))

(->> (range 5)
  (filter odd?)
  (map #(* % 2))
  (reduce conj []))

;; These solutions are subtly different. They change the collection instead of the reducing function.
;; What if we wanted to change the reducing function instead?

(defn filter-odd [rf] ; Mentally replace `rf` with `conj` for ease of reading
  (fn [acc x]
    (if (odd? x)
      (rf acc x)
      acc)))

(reduce (filter-odd conj) [] (range 5))

(reduce (filter-odd +) 0 (range 5))

;; What if we want to double only the odd values?

(->> (range 5)
  (filter odd?) ; intermediate sequence
  (map #(* % 2)) ; intermediate sequence
  (reduce conj []))

(->> (range 5)
  (filter odd?)
  (map #(* % 2))
  (reduce + 0))

(defn map-double [rf]
  (fn [acc x]
    (rf acc (* x 2))))

(reduce (map-double (filter-odd conj)) [] (range 5))

(defn map-double [conj-when-odd] ; Mentally replace `rf` with `conj-when-odd`
  (fn [acc x]
    (conj-when-odd acc (* x 2))))

;; We're doubling _first_ and then removing odds, so nothing is left

(reduce (filter-odd (map-double conj)) [] (range 5))

(reduce (filter-odd (map-double +)) 0 (range 5))

;; Rule of thumb: Read transducers from the outside in

;; WANT: Double and duplicate (x -> [x x]) only the odd values. Ask the audience to implement this.

(defn map-duplicate [rf]
  (fn [acc x]
    (rf acc [x x])))

(reduce (filter-odd (map-double (map-duplicate conj))) [] (range 5))
(reduce ((comp filter-odd map-double map-duplicate) conj) [] (range 5))

;; Okay, okay. Let's write a generic map transducer.

(defn map* [f]
  (fn [rf]
    (fn [acc x]
      (rf acc (f x)))))

(defn filter* [pred]
  (fn [rf]
    (fn [acc x]
      (if (pred x)
        (rf acc x)
        acc))))

(reduce ((comp (filter* odd?) (map* #(* % 2))) conj) [] (range 5))
(reduce ((comp (filter* odd?) (map* #(* % 2))) +) 0 (range 5))

(reduce ((comp (filter odd?) (map #(* % 2))) conj) [] (range 5))

(transduce (comp (filter odd?) (map #(* % 2))) conj [] (range 5))

(transduce (comp (filter* odd?) (map #(* % 2)) conj [] (range 5)))

(defn str-rf
  ([] (StringBuilder.))
  ([sb] (.toString sb))
  ([sb s] (.append sb s)))

(transduce (interpose ",") str-rf (range 5))

(into [] (partition-all 3) (range 10))

;; transduce + conj = into

(into []
  (comp
    (filter odd?)
    (map #(* % 2))
    (map (fn [x] [x x])))
  (range 5))

;; Really similar to...
(->> (range 5)
  (filter odd?)
  (map #(* % 2))
  (map (fn [x] [x x]))
  vec)

;; What if you want to transform a stream of data? Theoretically possible to represent this as a lazy seq, but seems inadvisable.

;; Quick introduction to core.async

(def ch (a/chan 1 (comp
                    (filter odd?)
                    (map #(* % 2))
                    (map (fn [x] [x x])))))

(a/go
  (a/>! ch 7)
  (println "Put complete"))

(a/go
  (let [v (a/<! ch)]
    (println "Take complete" v)))

(a/close! ch)

(a/go
  (a/>! ch 5)
  (println "First put complete")
  (a/>! ch 6))

(a/go
  (println (a/<! ch)))

(a/close! ch)

(def ch (a/chan 5 (comp
                    (filter odd?)
                    (map #(* % 2))
                    (map (fn [x] [x x])))))

(a/onto-chan ch (range 5))

(a/<!! (a/into [] ch))

;; PROBLEM: We have some streaming input that we want to partition into batches. However, we need to flush a batch even if it hasn't reached the full size after 1 second. The solution must be easy to test.

;; Solution outline:
;; * Transducer that acts just like `partition-all`, _but_ flushes contents when it sees the item `::flush`
;; * Process that puts `::flush` into channel once a second

(source partition-all)

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
         (when-not (= ::flush input)
           (.add a input))
         (if (and (or (= ::flush input) (= n (.size a)))
               (pos? (.size a)))
           (let [v (vec (.toArray a))]
             (.clear a)
             (rf result v))
           result))))))

;; TODO: Write test file
(comment
  ;; [[0 1 2] [3]]
  (into [] (partition-all-with-flush 3) (range 4))
  ;; [[0 1 2] [3]]
  (into [] (partition-all-with-flush 3) [0 1 2 3 ::flush])
  ;; [[0 1 2] [3]]
  (into [] (partition-all-with-flush 3) [0 1 2 3 ::flush ::flush])
  ;; [[0 1 2] [3] [4]]
  (into [] (partition-all-with-flush 3) [0 1 2 3 ::flush 4 ::flush])
  )

(into [] (partition-all-with-flush 3) [0 1 2 3 ::flush ::flush])

(def partitioned-ch (a/chan 10 (partition-all-with-flush 3)))

(a/go
  (loop []
    (a/>! partitioned-ch ::flush)
    (a/<! (a/timeout 5000))
    (recur)))

(a/go
  (loop [v (a/<! partitioned-ch)]
    (println v)
    (recur (a/<! partitioned-ch))))

(a/onto-chan partitioned-ch (range 13) false)

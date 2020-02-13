(ns transducers-talk.core-test
  (:require [transducers-talk.core3 :as sut]
            [clojure.test :refer [is deftest]]))

(deftest test-1
  (is (= [[0 1 2] [3]]
        (into [] (sut/partition-all-with-flush 3) [0 1 2 3]))))

(deftest test-2
  (is (= [[0 1 2] [3]]
        (into [] (sut/partition-all-with-flush 3) [0 1 2 3 :flush]))))

(deftest test-3
  (is (= [[0 1 2] [3] [4]]
        (into [] (sut/partition-all-with-flush 3) [0 1 2 3 :flush 4]))))

(deftest test-4
  (is (= [[0 1 2] [3] [4]]
        (into [] (sut/partition-all-with-flush 3) [0 1 2 3 :flush :flush 4]))))

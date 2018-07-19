(ns iface.components.ptm.ui.form
  (:require [cljs.spec.alpha :as s]
            [cljsjs.react-day-picker]
            [reagent.core :as r]
            [devtools.defaults :as d]
            [toolbelt.core :as tb]))

;; specs ========================================================================


(s/def ::label
  (s/or :string string? :element vector?))

(s/def ::optional
  boolean?)

(s/def ::help
  string?)

(s/def ::error
  boolean?)

(s/def ::value
  some?)

(s/def ::placeholder
  string?)

(s/def ::name
  string?)

(s/def ::on-change
  fn?)


;; components ===================================================================


(defn form []
  (into [:form] (r/children (r/current-component))))


(defn item [{:keys [label optional help error] :as props}]
  [:div
   [:label {:for label} label]
   (when optional
     [:span.small [:em " Optional"]])
   (let [children (map
                   #(update % 1 tb/assoc-when :error error)
                   (r/children (r/current-component)))]
     (into [:div] children))
   (when help
     [:p.small.red help])])

(s/fdef form-item
  :args (s/cat :props (s/keys :opt-un [::label
                                       ::optional
                                       ::help
                                       ::error])))


(defn text [{:keys [error] :as props}]
  [:input (-> props
              (merge {:type  "text"
                      :class (when error "error")})
              (dissoc :error))])

(s/fdef text
  :args (s/cat :props (s/keys :opt-un [::error])))


(defn number [{:keys [error] :as props}]
  [:input (-> props
              (merge {:type  "number"
                      :class (when error "error")})
              (dissoc :error))])

(s/fdef number
  :args (s/cat :props (s/keys :opt-un [::error])))


(defn textarea [{:keys [error] :as props}]
  [:textarea (-> props
                 (merge {:class (when error "error")})
                 (dissoc :error))])

(s/fdef textarea
  :args (s/cat :props (s/keys :opt-un [::error])))


(defn select-option [{:keys [value] :as props}]
  (into [:option props]
        (r/children (r/current-component))))

(s/fdef select-option
  :args (s/cat :props (s/keys :req-un [::value])))


(defn select [{:keys [error placeholder value] :as props}]
  (let [props' (-> props
                   (tb/assoc-when :value (when (and (= value nil) placeholder) ""))
                   (dissoc :error :placeholder))]
    [:div.select {:class (when error "select error")}
     (into [:select props'
            (when placeholder
              [select-option
               {:value    ""
                :disabled true}
               placeholder])]
           (r/children (r/current-component)))]))

(s/fdef select
  :args (s/cat :props (s/keys :opt-un [::error
                                       ::placeholder])))


(defn checkbox [{:keys [error checked]
                 :or {checked false}
                 :as props}]
  (let [props' (-> props
                   (merge {:type "checkbox"
                           :checked checked})
                   (dissoc :error))]
    [:div {:class (if error "checkbox error" "checkbox")}
     (into [:label
            [:input props']
            [:span.checkbox-style]]
           (r/children (r/current-component)))]))

(s/fdef checkbox
  :args (s/cat :props (s/keys :opt-un [::error])))


(defn checkbox-group [{:keys [error on-change value] :as props}]
  (let [c        (r/children (r/current-component))
        children (map
                  #(update % 1 tb/assoc-when
                           :error error
                           :checked (some (fn [v] (= v (:value (second %)))) value)
                           :on-change on-change)
                  ;; need to check if we are getting individual children
                  ;; or the result of mapping a collection of data over a child function
                  (if (map? (second (first c)))
                    c
                    (first c)))]
    (into [:div] children)))

(s/fdef checkbox-group
  :args (s/cat :props (s/keys :opt-un [::error
                                       ::name
                                       ::on-change])))


(defn radio-option [{:keys [checked]
                     :or   {checked false}
                     :as   props}]
  (let [props' (-> props
                   (merge {:type    "radio"
                           :checked checked})
                   (dissoc :label))]
    [:div.radio
     (into [:label
            [:input props']
            [:span.radio-style]]
           (r/children (r/current-component)))]))

(s/fdef radio-option
  :args (s/cat :props (s/keys :opt-un [])))


(defn radio-group [{:keys [name value on-change] :as props}]
  (let [c        (r/children (r/current-component))
        children (map
                  #(update % 1 tb/assoc-when
                           :name name
                           :checked (= value (:value (second %)))
                           :on-change on-change)
                  ;; need to check if we are getting individual children
                  ;; or the result of mapping a collection of data over a child function
                  (if (map? (second (first c)))
                    c
                    (first c)))]
    (into [:div] children)))

(s/fdef radio-group
  :args (s/cat :props (s/keys :opt-req [::name])))


(defn inline-date [props]
  (.createElement js/React js/DayPicker (clj->js props)))

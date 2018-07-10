(ns apply.db
  (:require [apply.routes :as routes]
            [clojure.string :as string]
            [iface.modules.loading :as loading]
            [iface.utils.formatters :as formatters :refer [format]]))


(def ^:private nav-items
  [{:section    :logistics
    :label      "Logistics"
    :icon       "check"
    :first-step :logistics/move-in-date}
   {:section    :community
    :label      "Community"
    :icon       "moon"
    :first-step :community/select}
   {:section    :personal
    :label      "Personal Info"
    :icon       "paper"
    :first-step :personal/phone-number}
   {:section    :payment
    :label      "Payment"
    :icon       "credit-card"
    :first-step :payment/review}])


(def nav-path
  ::nav)


(defn bootstrap [account]
  (merge
   {:lang    :en
    :account account
    nav-path nav-items
    :route   {:page      :home
              :path      [:home]
              :params    {}
              :requester account}}
   loading/db))


;; ==============================================================================
;; navigation ===================================================================
;; ==============================================================================


(def ^:private first-step
  :logistics/move-in-date)


(defn- is-substep? [step]
  (contains? (into #{} (namespace step)) "."))


(defn step->route
  "Produce the route that corresponds to `step`."
  [step]
  (let [ns   (namespace step)
        name (name step)]
    (if (is-substep? step)
      (let [[section step] (string/split ns #"\.")]
        (routes/path-for :section.step/substep
                         :section-id section
                         :step-id step
                         :substep-id name))
      (routes/path-for :section/step
                       :section-id ns
                       :step-id name))))


(defn route->step
  "Produce the step that corresponds to this `route`."
  [{{:keys [section-id step-id substep-id]} :params, :as route}]
  (cond
    (nil? section-id)
    first-step

    (some? substep-id)
    (keyword (str section-id "." step-id) substep-id)

    :otherwise
    (keyword section-id step-id)))


(defn- step-dispatch
  [db]
  (-> db :route route->step))


(defn- step-data [db]
  (let [step (step-dispatch db)]
    (get db step {})))


;;; last saved ===================================================================


(defmulti get-last-saved (fn [db step] step))


(defmethod get-last-saved :default [db step] first-step)


(defn recur-saved
  [db step]
  (loop [s step]
    (if (nil? (s db))
      s
      (recur-saved db (get-last-saved db s)))))


(defn last-saved
  [db]
  (recur-saved db first-step))


;; next =========================================================================


(defmulti next-step step-dispatch)


(defmethod next-step :default [db _] first-step)


;; previous =====================================================================


(defmulti previous-step step-dispatch)


(defmethod previous-step :default [db _] first-step)


;; has next? ====================================================================


;; NOTE: If you want to see the next button for every step, comment out the
;; implementation of this function and have it just return `true`
(defn has-next-button? [db]
  (let [step (step-dispatch db)]
    (boolean
     (#{:logistics.move-in-date/choose-date
        :logistics.occupancy/co-occupant
        :logistics.pets/dog
        :logistics.pets/other
        :community/select
        :personal/phone-number
        :personal.background-check/info
        :personal/income
        :personal.income/cosigner
        :personal/about
        :payment/review}
      step))))


;; has back? ====================================================================


(defmulti has-back-button? step-dispatch)


(defmethod has-back-button? :default [db]
  true)


;; section completion ===========================================================


(defmulti section-complete? (fn [db section] section))


;; (defmethod section-complete? :logistics [_ _] true)


;; (defmethod section-complete? :community [_ _] true)


;; NOTE: Changing this to `true` will toggle complete state on all sections
(defmethod section-complete? :default [_ _] false)


(defn can-navigate?
  "Is navigation to `section` allowed?"
  [db section]
  (let [prior (->> (nav-path db) (map :section) (take-while (partial not= section)))]
    (every? (partial section-complete? db) prior)))


;; step completion ==============================================================


(defmulti step-complete? (fn [db step] step))


(defmethod step-complete? :default [_ _] false)
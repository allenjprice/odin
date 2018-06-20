(ns apply.db
  (:require [apply.routes :as routes]
            [clojure.string :as string]
            [iface.modules.loading :as loading]
            [iface.utils.formatters :as formatters :refer [format]]
            [iface.utils.log :as log]))


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


(def ^:private application-default
  {:logistics {}
   :community {}
   :personal  {}
   :payment   {}})


(def application-path
  ::application)

(defn bootstrap [account]
  (merge
   {:lang            :en
    :account         account
    nav-path         nav-items
    :route           {:page      :home
                      :path      [:home]
                      :params    {}
                      :requester account}
    application-path application-default}
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


;; next =========================================================================


(defmulti next-step step-dispatch)


(defmethod next-step :logistics/move-in-date
  [db]
  ;; NOTE: inspection of `db` is needed for a proper implementation of this step
  ;; because some options within the step itself allow one to skip selection of
  ;; a date, and in one case triggers a separate flow.
  (if (= :date (step-data db))
    :logistics.move-in-date/choose-date
    :logistics/occupancy))


(defmethod next-step :logistics.move-in-date/choose-date
  [db]
  :logistics/occupancy)


#_(defmethod next-step :logistics/occupancy
  [db]
  :logistics/pets)


#_(defmethod next-step :logistics/pets
  [db]
  ;; NOTE: See above
  :community/select)


(defmethod next-step :logistics.pets/dog
  [db]
  :community/select)


(defmethod next-step :logistics.pets/other
  [db]
  :community/select)


(defmethod next-step :community/select
  [db]
  :community/term)


(defmethod next-step :community/term
  [db]
  :personal/phone-number)


(defmethod next-step :personal/phone-number
  [db]
  :personal/background-check)


(defmethod next-step :personal/background-check
  [db]
  ;; NOTE: See above
  :personal.background-check/info)


(defmethod next-step :personal.background-check/info
  [db]
  :personal/income)


(defmethod next-step :personal/income
  [db]
  :personal/about)


(defmethod next-step :personal/about
  [db]
  :payment/review)


(defmethod next-step :default [db _] first-step)


;; previous =====================================================================


(defmulti previous-step step-dispatch)


(defmethod previous-step :payment/review
  [db]
  :personal/about)


(defmethod previous-step :personal/about
  [db]
  :personal/income)


(defmethod previous-step :personal/income
  [db]
  :personal/background)


(defmethod previous-step :personal/background
  [db]
  :personal/phone-number)


(defmethod previous-step :personal/phone-number
  [db]
  :community/term)


(defmethod previous-step :community/term
  [db]
  :community/select)


(defmethod previous-step :community/select
  [db]
  :logistics/pets)


(defmethod previous-step :logistics.pets/dog
  [db]
  :logistics/pets)


(defmethod previous-step :logistics.pets/other
  [db]
  :logistics/pets)


#_(defmethod previous-step :logistics/pets
  [db]
  :logistics/occupancy)


(defmethod previous-step :logistics/occupancy
  [db]
  :logistics/move-in-date)


(defmethod previous-step :default [db _] first-step)


;; has next? ====================================================================


;; NOTE: If you want to see the next button for every step, comment out the
;; implementation of this function and have it just return `true`
(defn has-next-button? [db]
  true
  (let [step (step-dispatch db)]
    (boolean
     (#{:logistics.move-in-date/choose-date
        :logistics.pets/dog
        :logistics.pets/other
        :community/select
        :personal/phone-number
        :personal.background-check/info
        :personal/income
        :personal/about}
      step))))


;; has back? ====================================================================


(defmulti has-back-button? step-dispatch)


(defmethod has-back-button? :logistics/move-in-date
  [_]
  false)


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

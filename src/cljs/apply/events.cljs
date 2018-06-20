(ns apply.events
  (:require [apply.db :as db]
            [apply.routes :as routes]
            [re-frame.core :refer [reg-event-db reg-event-fx path]]
            [toolbelt.core :as tb]
            [iface.utils.log :as log]))


;; ==============================================================================
;; bootstrap ====================================================================
;; ==============================================================================


(reg-event-fx
 :app/init
 (fn [_ [_ account]]
   {:db (db/bootstrap account)}))


;; ==============================================================================
;; top-level ====================================================================
;; ==============================================================================


(reg-event-fx
 :ptm/start
 (fn [_ _]
   ;; TODO: Do server stuff
   {:route (db/step->route db/first-step)}))


;; ==============================================================================
;; nav ==========================================================================
;; ==============================================================================


(reg-event-fx
 :nav.item/select
 (fn [{db :db} [_ nav-item]]
   (tb/assoc-when
    {}
    :route (when (db/can-navigate? db (:section nav-item))
             (routes/path-for :section/step
                              :section-id (name (:section nav-item))
                              :step-id (name (:first-step nav-item)))))))


;; ==============================================================================
;; steps ========================================================================
;; ==============================================================================


(defn- next-route
  [db params]
  (-> db db/next-step db/step->route))


(defn- default-save-fx [db params]
  (let [res {:route    (next-route db params)
             :dispatch [:ui/loading :step.current/save false]}]
    res))


(defmulti save-step-fx
  (fn [db params]
    (-> db :route db/route->step)))


(defmethod save-step-fx :default [db params]
  (default-save-fx db params))


(reg-event-fx
 :step.current/save
 (fn [{db :db} [k params]]
   (merge {:dispatch [:ui/loading k true]}
          (save-step-fx db params))))


(reg-event-fx
 :step.current/next
 (fn [{db :db} [_ params]]
   {:dispatch [:step.current/save params]}))

(reg-event-fx
 :step/advance
 (fn [{db :db} [_ params]]
   {:route (next-route db params)}))

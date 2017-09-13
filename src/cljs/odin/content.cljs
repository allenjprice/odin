(ns odin.content
  (:require [antizer.reagent :as ant]
            [toolbelt.core :as tb]
            [odin.utils.dispatch :as dispatch]
            [reagent.core :as r]))


(defmulti view
  (fn [{:keys [requester path]}]
    (dispatch/role-dispatch view (:role requester) (first path))))


(defmethod view :default [{:keys [page path root params]}]
  [ant/card {:title "View not found"}
   [:p [:b "Page:"] page]
   [:p [:b "Path:"] path]
   [:p [:b "Root:"] root]
   [:p [:b "Params:"] params]])


;; This component is rendered when the user navigations to the /logout entpoint.
;; Because we need a catch-all route in `odin.routes` to prevent from hitting
;; the server on un-implemented routes, this component is rendered and
;; /immediately/ reloads the window, causing a forced server request.
(defn- logout! []
  (r/create-class
   {:component-will-mount
    (fn [_]
      (.reload js/window.location))
    :reagent-render
    (fn []
      ;; TODO: Show some sort of indication that we're logging out (e.g.
      ;; spinner) for instances where it hangs a bit
      [:div])}))


(defmethod view :logout [_] [logout!])

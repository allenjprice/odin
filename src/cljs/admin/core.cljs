(ns admin.core
  (:require [accountant.core :as accountant]
            [admin.accounts.views]
            [admin.content :as content]
            [admin.overview.views]
            [admin.events]
            [admin.fx]
            [admin.history]
            [admin.kami.views]
            [admin.metrics.views]
            [admin.profile.views]
            [admin.properties.views]
            [admin.services.views]
            [admin.routes :as routes]
            [admin.subs]
            [antizer.reagent :as ant]
            [cljsjs.moment]
            [day8.re-frame.http-fx]
            [goog.dom :as gdom]
            [iface.components.layout :as layout]
            [iface.components.notes :as inotes]
            [iface.modules.graphql :as graphql]
            [iface.modules.loading :as loading]
            [iface.modules.modal]
            [iface.modules.notification]
            [iface.modules.payments]
            [iface.utils.routes :as iroutes]
            [reagent.core :as r]
            [re-frame.core :as rf :refer [dispatch subscribe]]
            [starcity.re-frame.stripe-fx]
            [toolbelt.re-frame.fx]
            [taoensso.timbre :as timbre]
            [toolbelt.core :as tb]))


(enable-console-print!)


;; ==============================================================================
;; modules ======================================================================
;; ==============================================================================


;; ==============================================================================
;; views ========================================================================
;; ==============================================================================


(defn- nav-user-menu []
  [ant/menu
   [ant/menu-item {:key "profile-link"}
    [:a {:href (routes/path-for :profile/membership)} "My Profile"]]
   [ant/menu-item {:key "log-out"}
    [:a {:href "/logout"} "Log Out"]]])


(defn navbar []
  (let [account             (subscribe [:user])
        menu-items          (subscribe [:menu/items])
        mobile-menu-showing (subscribe [:layout.mobile-menu/showing?])
        active              (subscribe [:route/root])]
    [layout/navbar {:mobile-menu-showing @mobile-menu-showing
                    :on-menu-click       #(dispatch [:layout.mobile-menu/toggle])}
     [layout/navbar-menu-items @menu-items @active]
     [layout/navbar-menu-profile
      (:name @account) [nav-user-menu] [inotes/create-button
                                        {:on-click #(dispatch [:note.create/open])}]]]))


(defn layout []
  (let [route (subscribe [:route/current])]
    [layout/layout
     [inotes/create-modal
      {:loading     @(subscribe [:ui/loading? :note.create/create-note!])
       :can-submit  @(subscribe [:note/can-submit?])
       :is-creating @(subscribe [:note/showing?])
       :form        @(subscribe [:note/form])
       :members     @(subscribe [:note.mentions/options :member])
       :applicants  @(subscribe [:note.mentions/options :applicant])
       :onboarding  @(subscribe [:note.mentions/options :onboarding])
       :properties  @(subscribe [:properties/list])
       :on-cancel   #(dispatch [:note.create/cancel])
       :on-submit   #(dispatch [:note.create/create-note! %])
       :on-change   #(dispatch [:note.form/update %1 %2])}]
     [navbar]
     [layout/content
      [content/view @route]]]))


;; ==============================================================================
;; app entry ====================================================================
;; ==============================================================================


(defn render []
  (r/render
   [ant/locale-provider {:locale (ant/locales "en_US")}
    [layout]]
   (gdom/getElement "admin")))


(defn reload! []
  (render)
  (accountant/dispatch-current!))


(defn ^:export run []
  (let [account (js->clj (aget js/window "account") :keywordize-keys true)]
    (graphql/configure
     "/api/graphql"
     {:on-unauthenticated (fn [_]
                            {:route "/logout"})
      :on-error-fx        (fn [[k _]]
                            {:dispatch [:ui/loading k false]})})


    (rf/dispatch-sync [:app/init account])
    (iroutes/hook-browser-navigation! routes/app-routes)
    (render)))

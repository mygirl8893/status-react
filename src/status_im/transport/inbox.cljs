(ns ^{:doc "Offline inboxing events and API"}
    status-im.transport.inbox
  (:require [re-frame.core :as re-frame]
            [status-im.native-module.core :as status]
            [status-im.transport.utils :as transport.utils]
            [status-im.utils.config :as config]
            [taoensso.timbre :as log]
            [status-im.utils.ethereum.core :as ethereum]

            [day8.re-frame.async-flow-fx]))

(defn- parse-json
  ;; NOTE(dmitryn) Expects JSON response like:
  ;; {"error": "msg"} or {"result": true}
  [s]
  (try
    (let [res (-> s
                  js/JSON.parse
                  (js->clj :keywordize-keys true))]
      ;; NOTE(dmitryn): AddPeer() may return {"error": ""}
      ;; assuming empty error is a success response
      ;; by transforming {"error": ""} to {:result true}
      (if (and (:error res)
               (= (:error res) ""))
        {:result true}
        res))
    (catch :default e
      {:error (.-message e)})))

(defn- response-handler [error-fn success-fn]
  (fn handle-response
    ([response]
     (let [{:keys [error result]} (parse-json response)]
       (handle-response error result)))
    ([error result]
     (if error
       (error-fn error)
       (success-fn result)))))

(defn get-current-wnode-address [db]
  (let [network  (ethereum/network->chain-keyword (get db :network))
        wnode-id (get-in db [:account/account :settings :wnode network])]
    (get-in db [:inbox/wnodes network wnode-id :address])))

(defn initialize-offline-inbox-flow []
  {:first-dispatch [:inbox/get-sym-key]
   :rules [{:when :seen-both?
            :events [:inbox/get-sym-key-success :inbox/connection-success]
            :dispatch [:inbox/request-messages {:discover? true}]}]})

(defn recover-offline-inbox-flow []
  {:first-dispatch [:inbox/fetch-peers]
   :rules [{:when     :seen?
            :events   :inbox/connection-success
            :dispatch [:inbox/request-messages :discover? true]}]})

(defn initialize-offline-inbox
  "Initialises offline inbox if inboxing enabled in config"
  [{:keys [db]}]
  (when config/offline-inbox-enabled?
    (let [wnode (get-current-wnode-address db)]
      (log/info "offline inbox: initialize " wnode)
      (when wnode
        {:async-flow (initialize-offline-inbox-flow)
         :inbox/add-peer  {:wnode wnode}}))))

(defn recover-offline-inbox
  "Recover offline inbox connection after being offline because of connectivity loss"
  [back-online? {:keys [db]}]
  (when config/offline-inbox-enabled?
    (let [wnode (get-current-wnode-address db)]
      (when (and back-online?
                 wnode
                 (:account/account db))
        (log/info "offline inbox: recover" wnode)
        {:async-flow (recover-offline-inbox-flow)}))))

(defn add-peer [enode success-fn error-fn]
  (status/add-peer enode (response-handler error-fn success-fn)))

(defn fetch-peers
  ;; https://github.com/ethereum/go-ethereum/wiki/Management-APIs#admin_peers
  ;; retrieves all the information known about the connected remote nodes
  ;; TODO(dmitryn): use web3 instead of rpc call
  [success-fn error-fn]
  (let [args    {:jsonrpc "2.0"
                 :id      2
                 :method  "admin_peers"
                 :params  []}
        payload (.stringify js/JSON (clj->js args))]
    (status/call-web3-private payload (response-handler error-fn success-fn))))

(defn registered-peer? [peers enode]
  (let [peer-ids (into #{} (map :id) peers)
        enode-id (transport.utils/extract-enode-id enode)]
    (contains? peer-ids enode-id)))

(defn mark-trusted-peer [web3 enode success-fn error-fn]
  (.markTrustedPeer (transport.utils/shh web3)
                    enode
                    (fn [err resp]
                      (if-not err
                        (success-fn resp)
                        (error-fn err)))))

(defn request-messages [web3 wnode topics to from sym-key-id success-fn error-fn]
  (let [opts (merge {:mailServerPeer wnode
                     :symKeyID       sym-key-id}
                    (when from {:from from})
                    (when to {:to to}))]
    (log/info "offline inbox: request-messages request for topics " topics)
    (doseq [topic topics]
      (let [opts (assoc opts :topic topic)]
        (log/info "offline inbox: request-messages args" (pr-str opts))
        (.requestMessages (transport.utils/shh web3)
                          (clj->js opts)
                          (fn [err resp]
                            (if-not err
                              (success-fn resp)
                              (error-fn err topic))))))))

(re-frame/reg-fx
  :inbox/add-peer
  (fn [{:keys [wnode]}]
    (add-peer wnode
              #(re-frame/dispatch [:inbox/fetch-peers])
              #(log/error "offline inbox: add-peer error" %))))

(re-frame/reg-fx
  :inbox/fetch-peers
  (fn [retries]
    (fetch-peers #(re-frame/dispatch [:inbox/check-peer-added % retries])
                 #(log/error "offline inbox: fetch-peers error" %))))

(re-frame/reg-fx
  :inbox/mark-trusted-peer
  (fn [{:keys [wnode web3]}]
    (mark-trusted-peer web3
                       wnode
                       #(re-frame/dispatch [:inbox/connection-success %])
                       #(log/error "offline inbox: mark-trusted-peer error" % wnode))))

(re-frame/reg-fx
  :inbox/request-messages
  (fn [{:keys [wnode topics to from sym-key-id web3]}]
    (request-messages web3
                      wnode
                      topics
                      to
                      from
                      sym-key-id
                      #(log/info "offline inbox: request-messages response" %)
                      #(log/error "offline inbox: request-messages error" %1 %2 to from))))


(defn request-messages-for-chat [chat-id {:keys [db] :as cofx}]
  (let [web3                       (:web3 db)
        wnode                      (get-current-wnode-address db)
        {:keys [sym-key-id topic]} (get-in cofx [:db :transport/chats chat-id])]
    {:inbox/request-messages {:wnode      wnode
                              :topics     [topic]
                              :sym-key-id sym-key-id
                              :web3       web3}}))

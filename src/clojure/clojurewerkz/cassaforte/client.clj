;; Copyright (c) 2012-2014 Michael S. Klishin, Alex Petrov, and the ClojureWerkz Team
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns clojurewerkz.cassaforte.client
  "Provides fundamental functions for

   * connecting to Cassandra nodes and clusters
   * configuring connections
   * tuning load balancing, retries, reconnection strategies and consistency settings
   * preparing and executing queries constructed via DSL
   * working with executing results"
  (:require [clojure.java.io                    :as io]
            [clojurewerkz.cassaforte.policies   :as cp]
            [clojurewerkz.cassaforte.conversion :as conv]
            [qbits.hayt.cql                     :as hayt])
  (:import [com.datastax.driver.core Statement ResultSet ResultSetFuture Host Session Cluster
            Cluster$Builder SimpleStatement PreparedStatement BoundStatement HostDistance PoolingOptions
            SSLOptions ProtocolOptions$Compression]
           [com.datastax.driver.auth DseAuthProvider]
           [com.google.common.util.concurrent ListenableFuture Futures FutureCallback]
           [java.net URI]
           [javax.net.ssl TrustManagerFactory KeyManagerFactory SSLContext]
           [java.security KeyStore SecureRandom]
           [com.datastax.driver.core.exceptions DriverException]))

(declare build-ssl-options select-compression)

(def ^:dynamic *async* false)

;;
;; Macros
;;

(defmacro async
  "Prepare a single statement, return prepared statement"
  [body]
  `(binding [*async* true]
     (do ~body)))

(defmacro prepare
  "Prepare a single statement, return prepared statement"
  ([body]
     `(binding [hayt/*prepared-statement* true
                hayt/*param-stack*        (atom [])]
        ~body))
  ([session body]
     `(binding [hayt/*prepared-statement* true
                hayt/*param-stack*        (atom [])]
        (.prepare ~session ~body))))

;;
;; Protocols
;;

(defprotocol DummySession
  (executeAsync [_ query]))

(deftype DummySessionImpl []
  DummySession
  (executeAsync [_ query] (throw (Exception. "Not connected"))))

(defprotocol BuildStatement
  (build-statement [query]))

(extend-protocol BuildStatement
  String
  (build-statement [query]
    (build-statement (SimpleStatement. query)))

  clojure.lang.IPersistentMap
  (build-statement [raw-statement]
    (build-statement (hayt/->raw raw-statement)))

  Statement
  (build-statement [s]
    s))

(defprotocol Listenable
  (add-listener [_ runnable executor]))

(deftype AsyncResult [fut]
  clojure.lang.IDeref
  (deref [_]
    (conv/to-clj @fut))

  clojure.lang.IBlockingDeref
  (deref [_ time-period time-unit]
    (conv/to-clj (deref fut time-period time-unit)))

  Listenable
  (add-listener [_ runnable executor]
    (.addListener fut runnable executor)))

;;
;; Fns
;;

(defn ^Cluster build-cluster
  "Builds an instance of Cluster you can connect to.

   Options:
     * hosts: hosts to connect to
     * port: port, listening to incoming binary CQL connections (make sure you have `start_native_transport` set to true).
     * credentials: connection credentials in the form {:username username :password password}
     * connections-per-host: specifies core number of connections per host.
     * max-connections-per-host: maximum number of connections per host.
     * retry-policy: configures the retry policy to use for the new cluster.
     * load-balancing-policy: configures the load balancing policy to use for the new cluster.

     * consistency-level: default consistency level for all queires to be executed against this cluster
     * ssl: ssl options in the form {:keystore-path path :keystore-password password} Also accepts :cipher-suites with a Seq of cipher suite specs.
     * ssl-options: pre-built SSLOptions object (overrides :ssl)
     * kerberos: enables kerberos authentication"
  [{:keys [hosts
           port
           credentials
           connections-per-host
           max-connections-per-host
           consistency-level
           retry-policy
           reconnection-policy
           load-balancing-policy
           ssl
           ssl-options
           kerberos
           protocol-version
           compression]
    :or {protocol-version 2}}]
  (let [^Cluster$Builder builder        (Cluster/builder)
        ^PoolingOptions pooling-options (PoolingOptions.)]
    (when port
      (.withPort builder port))
    (when protocol-version
      (.withProtocolVersion builder protocol-version))
    (when credentials
      (.withCredentials builder (:username credentials) (:password credentials)))
    (when connections-per-host
      (.setCoreConnectionsPerHost pooling-options HostDistance/LOCAL
                                  connections-per-host))
    (when max-connections-per-host
      (.setMaxConnectionsPerHost pooling-options HostDistance/LOCAL
                                 max-connections-per-host))
    (.withPoolingOptions builder pooling-options)
    (doseq [h hosts]
      (.addContactPoint builder h))
    (when retry-policy
      (.withRetryPolicy builder retry-policy))
    (when reconnection-policy
      (.withReconnectionPolicy builder reconnection-policy))
    (when load-balancing-policy
      (.withLoadBalancingPolicy builder load-balancing-policy))
    (when compression
      (.withCompression (select-compression compression)))
    (when ssl
      (.withSSL builder (build-ssl-options ssl)))
    (when ssl-options
      (.withSSL builder ssl-options))
    (when kerberos
      (.withAuthProvider builder (DseAuthProvider.)))
    (.build builder)))

(defn- ^SSLOptions build-ssl-options
  [{:keys [keystore-path keystore-password cipher-suites]}]
  (let [keystore-stream   (io/input-stream keystore-path)
        keystore          (KeyStore/getInstance "JKS")
        ssl-context       (SSLContext/getInstance "SSL")
        keymanager        (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
        trustmanager      (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
        password          (char-array keystore-password)
        ssl-cipher-suites (if cipher-suites
                            (into-array String cipher-suites)
                            SSLOptions/DEFAULT_SSL_CIPHER_SUITES)]
    (.load keystore keystore-stream password)
    (.init keymanager keystore password)
    (.init trustmanager keystore)
    (.init ssl-context (.getKeyManagers keymanager) (.getTrustManagers trustmanager) nil)
    (SSLOptions. ssl-context ssl-cipher-suites)))

(defn- ^ProtocolOptions$Compression select-compression
  [compression]
  (case compression
    :snappy ProtocolOptions$Compression/SNAPPY
    :lz4 ProtocolOptions$Compression/LZ4
    ProtocolOptions$Compression/NONE))

(defn- connect-or-close
  "Attempts to connect to the cluster or closes the cluster and reraises any errors."
  [^Cluster cluster & [keyspace]]
  (try
    (if keyspace
      (.connect cluster keyspace)
      (.connect cluster))
    (catch DriverException e
      (.close cluster)
      (throw e))))

(defn ^Session connect
  "Connects to the Cassandra cluster. Use `build-cluster` to build a cluster."
  ([hosts]
     (connect-or-close (build-cluster {:hosts hosts})))
  ([hosts keyspace-or-opts]
     (if (string? keyspace-or-opts)
       (connect hosts keyspace-or-opts {})
       (let [keyspace (:keyspace keyspace-or-opts)
             opts     (dissoc keyspace-or-opts :keyspace)]
         (if keyspace
           (connect hosts keyspace opts)
           (connect-or-close (-> opts (merge {:hosts hosts}) build-cluster))))))
  ([hosts keyspace opts]
     (let [c (build-cluster (merge opts {:hosts hosts}))]
       (connect-or-close c (name keyspace)))))

(defn ^Session connect-with-uri
  ([^String uri]
     (connect-with-uri uri {}))
  ([^String uri opts]
     (let [^URI u (URI. uri)]
       (connect [(.getHost u)] (merge {:port (.getPort u) :keyspace (-> u .getPath (.substring 1))} opts)))))

(defn disconnect
  "1-arity version receives Session, and shuts it down. It doesn't shut down all other sessions
   on same cluster."
  [^Session session]
  (.close session))

(defn disconnect!
  "Shuts the cluster and session down.  If you have other sessions, use the safe `disconnect` function instead."
  [^Session session]
  (.close (.getCluster session)))

(defn shutdown-cluster
  "Shuts down provided cluster"
  [^Cluster cluster]
  (.close cluster))

(defn bind
  "Binds prepared statement to values, for example:

   With string statement:

      (client/bind
             (client/prepare s \"INSERT INTO users (name, city, age) VALUES (?, ?, ?);\")
             [\"Alex\" \"Munich\" (int 19)])

    With queries:

      (let [prepared (client/prepare (insert s :users {:name ? :city ? :age  ?}))]
        (client/execute s
                (client/bind prepared [\"Alex\" \"Munich\" (int 19)]))"
  [^PreparedStatement statement values]
  (.bind statement (to-array values)))

(defn execute
  "Executes a statement"
  ([^Session session query]
     (if hayt/*prepared-statement*
       (let [^String q (hayt/->raw query)]
         (.prepare session q))
       (let [^Statement built-statement (build-statement query)]
         (if *async*
           (AsyncResult. (.executeAsync session built-statement))
           (-> (.execute session built-statement)
               (conv/to-clj))))))
  ([^Session session query & {:keys [retry-policy
                                     consistency-level
                                     fetch-size
                                     enable-tracing
                                     default-timestamp]}]
     (let [^Statement built-statement (build-statement query)]
       (when default-timestamp
         (.setDefaultTimestamp built-statement))
       (when enable-tracing
         (.enableTracing built-statement))
       (when fetch-size
         (.setFetchSize built-statement (int fetch-size)))
       (when retry-policy
         (.setRetryPolicy built-statement retry-policy))
       (when consistency-level
         (.setConsistencyLevel built-statement consistency-level))
       (if *async*
         (AsyncResult. (.executeAsync session built-statement))
         (-> (.execute session built-statement)
             (conv/to-clj))))))

(defn ^String export-schema
  "Exports the schema as a string"
  [^Session client]
  (-> client
      .getCluster
      .getMetadata
      .exportSchemaAsString))

(defn get-hosts
  "Returns all nodes in the cluster"
  [^Session session]
  (map (fn [^Host host]
         {:datacenter (.getDatacenter host)
          :address    (.getHostAddress (.getAddress host))
          :rack       (.getRack host)
          :is-up      (.isUp host)})
       (-> session
           .getCluster
           .getMetadata
           .getAllHosts)))

;; defn get-replicas
;; defn get-cluster-name
;; defn get-keyspace
;; defn get-keyspaces
;; defn rebuild-schema

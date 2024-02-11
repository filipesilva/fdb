(ns fdb.repl
  (:refer-clojure :exclude [apply load])
  (:require
   [babashka.fs :as fs]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [clojure.tools.namespace.repl :as ns]
   [fdb.utils :as u]
   [nrepl.core :as nrepl]
   [nrepl.server :as server]
   [taoensso.timbre :as log]))

;; Don't reload this ns when refreshing all files to keep *fdb state.
(ns/disable-reload!)

(defonce *fdb (atom nil))

(defn node [config-path]
  (when (= config-path (:config-path @*fdb))
    (:node @*fdb)))

(def default-opts {:port 2525})

(defn port-file [config-path]
  (-> config-path (u/sibling-path ".nrepl-port") fs/file))

(defn save-port-file
  "Like nrepl.cmdline/save-port-file, but next to the config file."
  [config-path {:keys [port]}]
  (let [f (port-file config-path)]
    (.deleteOnExit ^java.io.File f)
    (spit f port)))

(defn has-server? [config-path]
  (fs/exists? (port-file config-path)))

(defn connect
  [config-path]
  (let [config (u/slurp-edn config-path)
        opts   (merge default-opts (:nrepl config))]
    (nrepl/connect opts)))

(defn apply
  [config-path sym & args]
  (with-open [conn (connect config-path)]
    (let [code  (format "(clojure.core/apply (requiring-resolve '%s) %s)" sym (vec args))
          _     (log/debug "apply" code)
          resp  (-> (nrepl/client conn 15000)
                    (nrepl/message {:op "eval" :code code})
                    doall)
          value (first (nrepl/response-values resp))]
      (log/debug resp)
      (log/debug value)
      (doseq [{:keys [out err]} resp]
        (when out (log/info out))
        (when err (log/error err)))
      (log/debug value))))

(defn as-comments
  [s & {:keys [prefix]}]
  (let [first-line (str ";; " prefix)
        other-lines (str ";; " (clojure.core/apply str (repeat (count prefix) " ")))]
    (str first-line (str/replace s #"\n." (str "\n" other-lines)))))

(defn load
  [config-path file-path output-path]
  (with-open [conn (connect config-path)]
    (log/debug "load" file-path)
    (let [append #(spit output-path %1 :append true)
          file   (slurp file-path)
          _      (append file)
          resp   (-> (nrepl/client conn 15000)
                     (nrepl/message {:op        "load-file"
                                     :file      file
                                     :file-name (fs/file-name file-path)
                                     :file-path file-path})
                     doall)
          value  (first (nrepl/response-values resp))]
      (log/debug resp)
      (log/debug value)
      (doseq [{:keys [out err]} resp]
        (when out (-> out as-comments append))
        (when err (-> err as-comments append)))
      (let [value-str (with-out-str (pprint/pprint value))]
        (-> value-str (as-comments :prefix "=> ") append))
      (append "\n"))))

(defn start-server
  [{:keys [config-path config] :as fdb}]
  (let [opts   (merge default-opts (:nrepl config))
        server (server/start-server opts)]
    (reset! *fdb fdb)
    (save-port-file config-path opts)
    (log/info "nrepl server running at" (:port opts))
    server))

(def refresh ns/refresh)

(comment
  (def config-path "tmp/fdbconfig.edn")
  (has-server? config-path)
  (apply config-path 'clojure.core/+ 1 2)
  (apply config-path 'clojure.core/println "foo\nbar\nbaz")
  (apply config-path 'clojure.core/merge {:a 1 :b 2} {:c 3})
  (apply config-path 'clojure.core// 1 0)

  (load config-path "tmp/load.clj" "tmp/load.log")

  )

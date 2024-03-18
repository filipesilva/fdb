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

(def default-opts {:port 2525})

(defn port-file [config-path]
  (-> config-path (u/sibling-path ".nrepl-port") fs/file))

(defn save-port-file
  "Like nrepl.cmdline/save-port-file, but next to the config file."
  [config-path {:keys [port]}]
  (let [f (port-file config-path)]
    (.deleteOnExit ^java.io.File f)
    (spit f port)))

(defn connect
  [config-path]
  (let [config (u/slurp-edn config-path)
        opts   (merge default-opts (:nrepl config))]
    (nrepl/connect opts)))

(defn as-comments
  [s & {:keys [prefix]}]
  (let [first-line (str ";; " prefix)
        other-lines (str ";; " (clojure.core/apply str (repeat (count prefix) " ")))]
    (str first-line (str/replace s #"\n." (str "\n" other-lines)))))

(defn load
  [config-path input-path content]
  (with-open [conn (connect config-path)]
    (log/debug "load" input-path)
    (let [*output (atom "")
          append #(swap! *output str % "\n")
          _      (append content)
          resp   (-> (nrepl/client conn 15000)
                     (nrepl/message {:op        "load-file"
                                     :file      content
                                     :file-name (fs/file-name input-path)
                                     :file-path input-path})
                     doall)
          value  (first (nrepl/response-values resp))]
      (log/debug resp)
      (log/debug value)
      (doseq [{:keys [out err]} resp]
        (when out (-> out as-comments append))
        (when err (-> err as-comments append)))
      (let [value-str (with-out-str (pprint/pprint value))]
        (-> value-str (as-comments :prefix "=> ") append))
      @*output)))

(defn start-server
  [config-path nrepl]
  (let [opts   (merge default-opts nrepl)
        server (server/start-server opts)]
    (log/info "nrepl server running at" (:port opts))
    (save-port-file config-path opts)
    server))

(def refresh ns/refresh)

(comment
  (def config-path "tmp/fdbconfig.edn")
  (load config-path "tmp/load.clj" "tmp/load.log")

  )

;; TODO:
;; - can I do repl/load in triggers in a simpler way?
;;   - just via eval with out/err bindings

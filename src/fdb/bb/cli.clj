#!/usr/bin/env bb

(ns fdb.bb.cli
  "CLI commands for fdb.
  Runs in a babashka environment for startup speed, deps in ./bb.edn.
  Creates or connects to an existing fdb process to run commands.
  Symlink this file to /usr/local/bin/fdb to be able to run it from anywhere
    ln -s \"$(pwd)/src/fdb/bb/cli.clj\" /usr/local/bin/fdb
  "
  (:refer-clojure :exclude [sync read])
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.nrepl-client :as nrepl]
   [babashka.process :as process]
   [clojure.string :as str]
   [fdb.config :as config]
   [fdb.utils :as u]
   [taoensso.timbre :as log]))

(defn escape-quotes
  [s]
  (str/escape (str s) {\" "\\\""}))

(defn repl-port [config-path]
  (some-> config-path (u/sibling-path ".nrepl-port") u/slurp parse-long))

;; don't attempt to start if there's a repl already
(defn start-fdb-repl
  "Start fdb.core/repl in another process and wait until repl is up.
  Returns future blocking on proces. Deref future to block until process finishes."
  [config-path debug]
  (log/merge-config! {:min-level (if debug :debug :info)})
  (log/info "starting fdb repl process")
  (let [deps {:deps {'org.clojure/clojure {:mvn/version "1.12.0-alpha5"}
                     'filipesilva/fdb     {:local/root (u/fdb-root)}}}
        opts {:config-path config-path
              :debug       debug}
        cmd  (format "clojure -Sdeps \"%s\" -X fdb.core/repl \"%s\""
                     (escape-quotes deps) (escape-quotes opts))
        _    (log/debug "running shell cmd" cmd)
        fut  (future (process/shell cmd))]
    (while (not (repl-port config-path))
      (Thread/sleep 50))
    fut))

(defn eval-in-fdb [config-path sym & args]
  (if-let [port (repl-port config-path)]
    (nrepl/eval-expr {:port port :expr (format "(apply %s %s)" sym (pr-str (vec args)))})
    (log/error "no fdb repl server running")))

(defn find-config-path [config]
  (let [config-path (config/path config)]
    (log/info "config found at" config-path)
    config-path))

(defn init [{{:keys [dir demo]} :opts}]
  (let [path (config/new-path dir)]
    (if (fs/exists? path)
      (log/error path "already exists!")
      (let [fdb-demo-path (fs/path (u/fdb-root) "demo")
            demo-path     (u/sibling-path path "fdb-demo")]
        (when demo
          (fs/copy-tree fdb-demo-path demo-path {:replace-existing true})
          (log/info "created demo folder at" demo-path))
        (u/spit-edn path (cond-> {:db-path    "./db"
                                  :mounts     {}
                                  :readers    {}
                                  :extra-deps {}
                                  :load       []}
                           demo (-> (assoc-in [:mounts :demo] demo-path)
                                    (update :load conj "/demo/repl.fdb.clj"))))
        (log/info "created new config at" path)))))

(defn watch [{{:keys [config debug]} :opts}]
  (let [config-path (find-config-path config)
        repl        (start-fdb-repl config-path debug)]
    (eval-in-fdb config-path 'fdb.core/watch-config! config-path)
    @repl))

(defn sync [{{:keys [config debug]} :opts}]
  (let [config-path (find-config-path config)]
    (start-fdb-repl config-path debug)
    (eval-in-fdb config-path 'fdb.core/sync config-path)))

(defn read [{{:keys [config pattern]} :opts}]
  (let [config-path (find-config-path config)]
    (eval-in-fdb config-path 'fdb.core/read config-path (str (fs/cwd)) pattern)))

(defn help [m]
  (println m)
  (println "help!"))

(def spec {:config {:desc    "The FileDB config file."
                    :alias   :c}
           :debug  {:desc    "Print debug info."
                    :alias   :d
                    :default false
                    :coerce  :boolean}})

(def table
  [{:cmds []        :fn help :spec spec}
   {:cmds ["init"]  :fn init :args->opts [:dir]}
   {:cmds ["watch"] :fn watch}
   {:cmds ["sync"]  :fn sync}
   {:cmds ["read"]  :fn read :args->opts [:pattern]}])

(defn -main [& args]
  (cli/dispatch table args))

;; Call main when bb is called over this file.
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

;; TODO:

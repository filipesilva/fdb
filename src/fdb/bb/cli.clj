#!/usr/bin/env bb

(ns fdb.bb.cli
  "CLI commands for fdb.
  Runs in a babashka environment for startup speed, deps in ./bb.edn.
  Creates or connects to an existing fdb process to run commands.
  Symlink this file to /usr/local/bin/fdb to be able to run it from anywhere
    ./symlink-fdb.sh
  or
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

(defn init [{{:keys [dir demos]} :opts}]
  (let [path (config/new-path dir)]
    (if (fs/exists? path)
      (do
        (log/error path "already exists!")
        (System/exit 1))
      (let [fdb-demos-path (fs/path (u/fdb-root) "demos")
            user-path      (u/sibling-path path "user")
            demos-path     (u/sibling-path path "demos")]
        (fs/create-dirs user-path)
        (spit (str (fs/path user-path "repl.fdb.clj"))
              (str ";; Clojure code added here will be evaluated, output will show up in ./repl-out.fdb.clj\n"
                   ";; Quick help: https://clojuredocs.org https://github.com/filipesilva/fdb#call-spec-and-call-arg\n"))
        (spit (str (fs/path user-path "query.fdb.edn"))
              (str ";; XTDB queryes added here will be evaluated, output will show up in ./query-out.fdb.edn\n"
                   ";; Quick help: https://v1-docs.xtdb.com/language-reference/datalog-queries/\n"))
        (log/info "created user folder at" user-path)
        (when demos
          (fs/copy-tree fdb-demos-path demos-path {:replace-existing true})
          (log/info "created demos folder at" demos-path))
        (u/spit-edn path (cond-> {:db-path       "./xtdb"
                                  :mounts        {:user user-path}
                                  :extra-deps    {}
                                  :extra-readers {}
                                  :load          []}
                           demos (-> (assoc-in [:mounts :demos] demos-path)
                                     (update :load conj
                                             (str (fs/path demos-path "reference/repl.fdb.clj"))))))
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

(def spec {:config {:desc    "The FileDB config file. Defaults to ~/fdb/fdbconfig.edn."
                    :alias   :c}
           :debug  {:desc    "Print debug info."
                    :alias   :d
                    :default false
                    :coerce  :boolean}})

(defn help [ms]
  (println (format
            "FileDB is a hackable database environment for your file library.
Local docs at %s/README.md.
Repo at https://github.com/filipesilva/fdb.

Available commands:
  fdb init <path-to-dir>    Add a empty fdbconfig.edn at path-to-dir or ~ if omitted, --demos adds demos mount.
  fdb watch                 Start fdb in watch mode, reacting to file changes as they happen.
  fdb sync                  Run fdb once, updating db to current file state and calling any triggers.
  fdb read <glob-pattern>   Read all files matched by glob-pattern. Use after changing readers.

All commands take the following options:
%s "
            (u/fdb-root)
            (cli/format-opts {:spec spec}))))

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
;; - can I start clojure on fdb-root instead of trying making a new deps?
;;   - could enable using aliases, improve repl setup
;;   - atm I'm auto-adding cider stuff
;;   - maybe I can just add them in my own fdbconfig.json
;;   - maybe I can do something fancy with deps alias and config merging from fdbconfig.edn
;;   - actually, global clojure deps should work for defining aliases
;;   - so just a way to chose an alias for the project, I guess --aliases flag on all args

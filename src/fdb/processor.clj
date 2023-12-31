(ns fdb.processor)

;; TODO:
;; - support file ext processors, e.g. markdown with props
;;   - extract data from content directly to db metadata, without making the metadata file
;;   - avoids lots of clutter in existing dirs
;;   - really good for obsidian or for code ASTs and such
;; - always read content for some file types, e.g. json, yaml, xml, html, but allow config
;; - great for triggered actions on some file types like mbox
;; - use processors and extra-processors, like deps and extra-deps
;; - should processors be super special config items tho?
;;   - would be nice if they worked like triggers... I mean they are a sort of metadata trigger
;;   - fdb.on/metadata ?
;;   - vec of fns, each adding to the metadata that will be written
;;   - then you config them globally like other triggers
;;   - it's different than other triggers because it happens while loading the metadata for the file,
;;     so before the tx even happens, and cares a lot about the difference between content and metadata files
;;   - but even if it's different, the config point still stands

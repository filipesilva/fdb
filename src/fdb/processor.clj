(ns fdb.processor)

;; TODO:
;; - support file ext processors, e.g. markdown with props
;;   - extract data from content directly to db metadata, without making the metadata file
;;   - avoids lots of clutter in existing dirs
;;   - really good for obsidian or for code ASTs and such
;; - always read content for some file types, e.g. json, yaml, xml, html, but allow config

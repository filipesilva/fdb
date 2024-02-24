---
tags:
  - accepted
  - applied
  - action-item
aliases:
  - something
  - else
  - another
cssclasses:
  - .foo
  - .bar
text: one two three
checkbox: true
list:
  - one
  - two
  - three
number: 14
date: 1986-04-25
datetime: 1986-04-25T16:00:00
text-link: "[[another file]]"
list-links:
  - "[[another file]]"
  - "[[other file]]"
fdb/k: '{:foo "bar"}'
fdb.a/ks:
  - "n.s/sym"
  - "{:call n.s/another-sym}"
  - '[:sh "echo"]'
---
markdown body

some tags #learning #accepted #1bâc #aAa

a non-valid tag #123

some links [[another file]] [[other file]]

link with path [[inbox/another file]]

a block link [[another file#^b86803]] 

an alias link [[another file|foo]]

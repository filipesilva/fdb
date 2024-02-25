(ns fdb.fns.notifications)

;; TODO:
;; - use web push for notification app
;;   - https://world.hey.com/dhh/native-mobile-apps-are-optional-for-b2b-startups-in-2024-4c870d3e
;; - https://github.com/tonsky/AnyBar is cool for mac desktop
;; - https://github.com/binwiederhier/ntfy has cli and ios app, oss
;; - could probably do something interesting with RSS plus ios app that notifies based on RSS
;;   - https://apps.apple.com/ie/app/simple-rss-push/id711503013 app, paid tho
;; - https://github.com/caronc/apprise
;;  - could be really nice with discord webhooks
;;    - https://support.discord.com/hc/en-us/articles/228383668-Intro-to-Webhooks
;;    - in fact... don't even need apprise for this :|
;;  - lots of work for signal https://github.com/caronc/apprise/wiki/Notify_signal
;;  - pushover looks really nice
;;    - https://github.com/caronc/apprise/wiki/Notify_pushover
;;    - https://pushover.net/pricing 5$ one time for the apps
;;  - what about just email?
;;    - mailto link
;;    - already use gmail STMP anyway
;;    - nice and known protocol, everyone uses email, easy to have on phone, easy to configure
;;

# 2.4.0-beta.8 — l'adresse IP ne clignote plus dans le panneau d'état

Signalement : sur Fire Stick, l'adresse IP affichée apparaissait et disparaissait plusieurs fois après l'activation d'un DNS.

Deux causes, corrigées :

- le panneau était **vidé avant d'être rempli** à chaque rafraîchissement (IP remplacée par « … », puis IPv4, puis IPv6), et le rafraîchissement était déclenché quatre fois d'affilée après une activation ;
- un appel réseau échoué **écrasait l'IP déjà connue** par « indisponible ». Le délai d'attente de 3 s expirait régulièrement sur une liaison lente.

Désormais : la dernière valeur connue reste affichée pendant la mise à jour, un échec ne l'efface plus, les rafales ne déclenchent qu'un seul appel réseau, et le délai passe à 6 s. Les échecs sont journalisés (`PdmWhoami`, type d'erreur uniquement, jamais l'adresse IP).

Reproduit et vérifié sur AVD Android 13 avec le réseau volontairement ralenti (EDGE, 57 ko/s) : avant, l'IP disparaissait pendant 8 secondes ; après, elle reste affichée sans interruption pendant toute l'activation. 22 tests unitaires OK.

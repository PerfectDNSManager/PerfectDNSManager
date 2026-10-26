# IPv6 et catalogue DNS — 25 octobre 2026

Travail local sur la bêta graphique, sans publication. Ce complément remplace les chiffres de catalogue du rapport graphique initial : **14 fournisseurs, 119 profils** (30 DoH, 30 DoT, 22 DoQ, 37 UDP).

## Diagnostic et corrections

Un serveur DNS joignable en IPv4 peut parfaitement répondre aux requêtes AAAA. L’absence d’une adresse IPv6 du résolveur n’implique donc pas un blocage du trafic IPv6. Le VPN permet la famille IPv6 lorsque `disable_ipv6=false`, qui reste la valeur par défaut. La route `::/0` de blocage n’est ajoutée que lorsque l’option est activée.

Deux problèmes confirmés ont été traités :

1. Les erreurs des transports DNS étaient silencieuses. Le service répond maintenant SERVFAIL, en conservant l’identifiant et la question. Il ne prétend pas que le nom n’a aucune adresse IPv6. Les réécritures locales n’ajoutent plus de CNAME à une réponse en échec. Aucun basculement automatique vers un autre fournisseur ni relâchement TLS.
2. Le verrou d’établissement DoQ abandonnait après 300 ms. Des demandes A/AAAA simultanées pouvaient échouer pendant le handshake. L’attente est désormais bornée à 12 s, avec un verrou par endpoint et résolution bootstrap partagée. Les délais de bootstrap et de connexion restent bornés, les certificats restent vérifiés. Le test de concurrence a échoué sur l’ancien APK puis réussi sur le nouvel APK (8 requêtes × 3 démarrages, latence AVD configurée identiquement).

Ce deuxième défaut n’établit pas la cause du signalement initial : le protocole, la version et le matériel du signalant ne sont pas connus.

## Catalogue

- Cloudflare, Control D, dns.sb en tête sur Android et sur le site.
- Presets Quad9, Surfshark et Mullvad retirés, y compris des listes enregistrées lors de la migration. Les profils explicitement personnels sont conservés. Le profil déjà actif n’est pas remplacé à l’insu de l’utilisateur.
- Bootstrap interne : Cloudflare puis dns.sb, plus aucun repli implicite Quad9. Les correspondances TLS d’anciennes IP restent disponibles pour les configurations personnelles.
- FDN UDP est marqué opérateur : réservé FDN/FFDN, masqué par défaut dans l’application. Les profils publics DoH/DoT restent proposés. Le site indique clairement cette restriction. Source : [annonce FDN du 15 mars 2025](https://www.fdn.fr/renforcement-serveurs-dns-2025/), encore référencée sur [la page DNS FDN](https://www.fdn.fr/actions/dns/).
- Le retrait de Quad9 est demandé par l’utilisateur ; ce rapport ne confirme pas son motif géographique.

## Mesures

Le script `tools/dns_ipv6_catalog_probe.py` vérifie les questions A/AAAA, les réponses et les certificats. Le balayage hôte initial comprenait aussi les fournisseurs retirés ; le sous-ensemble correspondant au catalogue final, avec reprises et DoH HTTP/2, donne :

| Transport | Entrées mesurées | Résultat AAAA |
|---|---:|---|
| DoH | 30 | 30 réponses avec adresses IPv6 |
| DoT | 30 | 30 réponses avec adresses IPv6 |
| UDP | 130 adresses primaires/secondaires IPv4/IPv6 | 67 réponses, 60 adresses IPv6 injoignables depuis l’hôte, 3 délais dépassés |

Les 3 délais UDP concernent Cloudflare Malware Blocking (1.1.1.2 et 1.0.0.2) et FDN ns0 (80.67.169.12), à cet instant et depuis ce réseau. Ce n’est pas une preuve d’indisponibilité universelle. Les 60 adresses IPv6 ne peuvent pas être validées : l’hôte n’a pas de route IPv6 publique.

Une première matrice Android TV API 31, avant retrait des fournisseurs et correction DoQ, a vérifié 106 scénarios : 106 connexions TCP IPv6 locales réussies avec le blocage désactivé. Certaines résolutions ont échoué ; elles sont enregistrées séparément du routage. Le contrôle négatif DoH local inaccessible doit retourner SERVFAIL pour A et AAAA tout en laissant passer IPv6.

Les preuves brutes locales sont dans `build/review/ipv6-matrix-android-expanded.jsonl`, `ipv6-matrix-current.jsonl`, `ipv6-catalog-current-host.json` et les rapports de compilation/tests sous `app/build/`. Ces sorties générées ne sont pas versionnées.

## Limites

La sonde TCP IPv6 `[fec0::2]:18089` utilise la passerelle de l’émulateur vers `[::1]:18089` de l’hôte. Elle vérifie le routage IPv6 réel dans l’AVD, pas l’accès IPv6 public d’un opérateur. DoT est sondé depuis l’hôte ; le parcours Android Private DNS/ADB n’est pas validé par cette matrice. Les profils privés Control D/NextDNS nécessitent les paramètres de leur propriétaire. Aucun Firestick ou Formuler physique n’est disponible. Une indication « IPv6 indisponible » dans une page de diagnostic ne suffit pas à identifier une panne du VPN.

## Résultats sur le catalogue final

- Matrice Android TV : **90 scénarios, 90 connexions TCP IPv6 locales réussies** avec blocage désactivé. Les 89 profils réels (30 DoH, 22 DoQ, 37 UDP) ont tous répondu avec A et AAAA ; le 90e contrôle négatif a renvoyé SERVFAIL pour les deux types, comme prévu. Exécution : 213 s.
- Migration des presets retirés : test instrumenté réussi, profil personnel conservé et liste persistée nettoyée.
- Préférence IPv6 : quatre tests d’installation/migration réussis ; blocage désactivé initialement et choix explicites conservés.
- Tunnel DoH puis requête HTTPS : test instrumenté réussi.
- Site : tests worker, 12 langues, catalogue, 32 combinaisons de filtres et TypeScript réussis. Génération du catalogue idempotente.

- Régression finale de routage : OFF/ON/OFF puis quatre changements du véritable interrupteur pendant une connexion Control D, test réussi. La première exécution mesurait avant la fin de l’installation des routes Android ; le test attend désormais, dans une fenêtre bornée, le résultat du routage après la transition.
- Concurrence DoQ finale : 24 requêtes A/AAAA réussies sur trois connexions Control D distinctes, avec attente explicite des transitions réseau. Ce test et celui du réglage IPv6 passent ensemble (2 tests, 50,6 s).
- Vérification finale complète du build (1 min 51 s) : APK debug, 30 tests JVM sans échec, lint sans erreur et 317 avertissements. Un premier lancement concurrent de lint avait échoué dans l’analyseur Kotlin (`Cannot find a KaModule`) ; l’exécution suivante a terminé normalement. Les avertissements ne sont pas masqués.

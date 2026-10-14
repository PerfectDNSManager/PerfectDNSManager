# 2.4.0-beta.9 — IPv6 autorisé par défaut

- Le premier lancement n’active plus « Désactiver IPv6 / Forcer IPv4 ».
- Les anciennes versions enregistraient automatiquement cette option à `true`, sans distinguer une activation manuelle. La mise à jour la remet donc à `false` une seule fois, y compris lorsque PDM démarre via un service. Les utilisateurs qui souhaitent bloquer IPv6 peuvent réactiver l’option ; ce choix sera ensuite conservé aux prochains lancements.
- L’écran et le rapport distinguent désormais IPv6 « indisponible » d’un blocage activé dans PDM.
- Le correctif DoH des bêtas 7/8 est conservé : initialisation du socket avant `VpnService.protect()`, puis connexion.
- Tests de régression Android ajoutés pour les valeurs initiales, la migration et la conservation des choix ultérieurs. Le test DNS/HTTPS vérifie désormais les valeurs par défaut réelles, sans désactiver lui-même le blocage IPv6.

IPv6 dépend également du réseau et du fournisseur d’accès : autoriser son passage dans PDM ne crée pas une connectivité IPv6 absente du réseau.

Validation : 22 tests JVM ; sur AVD Android TV 12, 4 tests de migration/préférences, DNS et HTTPS via Cloudflare DoH, puis échange TCP IPv6 réel avec blocage OFF → ON → OFF (passage → blocage → passage). Control D DoH passe aussi ; xdp.es DoH passe après un premier timeout réseau. Le Fire Stick physique reste à confirmer.

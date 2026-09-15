# 2.4.0-beta.6 — revue sécurité et retours GitHub

- Correction IPv6 : désactiver son blocage autorise maintenant effectivement son trafic (issue #3).
- Partage : bouton de copie explicite, sans lecture automatique du presse-papiers (issue #1).
- Correction des profils NextDNS DoQ et migration des anciennes adresses.
- Validation renforcée des profils, exports plus fidèles, fiches fournisseur rafraîchies après modification.
- Corrections DoT, protection des sockets DNS et délais de pairing ADB.
- Correction du faux résultat de résolveur dans le test de fuite DNS.
- Mise à jour BouncyCastle, lectures de métadonnées bornées et correction de la règle R8 Ookla.
- xdp.es, profils personnels Control D et réglage Adblock conservés.

Revue détaillée : `docs/audit/2026-09-15.md`. Le problème Formuler Z7+/Zx (#2) n’est pas déclaré résolu sans essai sur ces modèles. Validation TV/Fire TV physique encore requise.

Validation : 17 tests JVM réussis, vérifications des profils, migration NextDNS, messages DNS, limites HTTP et noms de certificats. Publication conditionnée au lint et à la compilation release signée par CI. Backend bêta : tests TypeScript et aller-retour HTTPS synthétique réussis. Aucun test TV physique effectué ici.

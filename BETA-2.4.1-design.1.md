# 2.4.1-beta.design.1

Publication sur le canal bêta autorisée le 26 octobre 2026. La release signée met à jour PDM ; les APK debug locaux « PDM Design Beta » restent séparés.

- Palette plus sobre et lisible, cartes fournisseurs allégées.
- Cases DoH / DoT / DoQ / UDP dans le catalogue, compteurs et état vide.
- Conservation des filtres lors d’une recréation de l’écran ; navigation télécommande explicite.
- Filtres respectés par les profils personnels ; Adblock reste opt-in.
- Retrait des notes de vitesse/confidentialité statiques.
- Lectures des métadonnées bornées, réutilisation des connexions IPv4/IPv6.
- Liens de partage du site reconnus par l’application ; identifiants personnels mieux masqués dans les logs.
- Mise à jour Bouncy Castle 1.86 pour les avis de sécurité connus.
- Aucun changement du réglage IPv6 par défaut : blocage désactivé.

Voir `docs/audit/2026-10-25-design-beta.md` pour les résultats, risques résiduels et limites matérielles.

## Complément réseau et catalogue — 25 octobre 2026

- Ordre des fournisseurs : Cloudflare, Control D, dns.sb, puis les autres.
- Retrait des presets Quad9, Surfshark et Mullvad ; migration des listes enregistrées et conservation des profils personnels.
- Suppression du repli bootstrap implicite Quad9 ; Cloudflare puis dns.sb.
- FDN : profils UDP réservés aux réseaux FDN/FFDN, masqués avec les autres DNS opérateurs par défaut. DoH et DoT restent disponibles.
- DoQ : les requêtes concurrentes attendent la connexion en cours au lieu d’abandonner après 300 ms ; bootstrap partagé par endpoint.
- Échec amont DoH/DoQ/UDP : réponse DNS SERVFAIL explicite au lieu d’un silence ; pas de fausse réponse AAAA vide ni de CNAME inventé sur échec.
- Blocage IPv6 toujours désactivé par défaut ; validation réseau détaillée dans `docs/audit/2026-10-25-ipv6-catalog.md`.

- DNS privé DoT externe reconnu dans les paramètres et préservé au démarrage, même avec un ancien profil VPN enregistré.

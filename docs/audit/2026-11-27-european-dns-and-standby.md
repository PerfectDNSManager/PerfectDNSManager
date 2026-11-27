# DNSForge, DNS4EU et secours privé — 27 novembre 2026

Préparation locale 2.4.12-beta.1 sur `codex/pdm-unbound-beta`. Aucun push,
aucune release ni déploiement Worker. La stable et le site public sont inchangés.

## Catalogue Android

Sources officielles consultées et adresses reprises :
- https://dnsforge.de/ : Blank, Normal, Clean, Hard ; DoH/DoT/DoQ et DNS classique.
- https://joindns4.eu/for-public : Unfiltered, Protective, Child, Noads,
  Child-Noads ; DoH/DoT et DNS classique. Aucun DoQ annoncé n'a été inventé.

31 profils de transport, plus 9 profils Unbound/DoT dérivés par le mécanisme
existant. Les identifiants 1900–1982 sont nouveaux ; l'unicité est testée. La
première tentative utilisait une plage déjà occupée par Digitale Gesellschaft :
les tests l'ont détecté et la plage a été corrigée avant validation finale.

Les profils Blank/Unfiltered sont présentés comme Standard et restent premiers.
Les trois variantes filtrées DNSForge et les deux variantes anti-pub DNS4EU
respectent le réglage Adblock. Les autres variantes DNS4EU suivent l'affichage
des variantes. Les protocoles suivent leurs paramètres existants ; DoH reste
le défaut. Cloudflare, Control D et dns.sb restent les trois premiers fournisseurs.

DNS4EU indique que les blocages ordonnés par la justice concernent aussi sa
variante Unfiltered. Une note apparaît dans les descriptions, traduite dans les
douze langues, plutôt que de présenter ce fournisseur comme absolument neutre.

## Vérifications effectuées

- DoH : 9 endpoints × questions A/AAAA, tous réussis avec réponse DNS et contrôle
  de l'identifiant/question. HTTPS authentifié, aucune désactivation TLS.
- DoT : 9 endpoints × A/AAAA, tous réussis avec validation du certificat et nom TLS.
- DoQ DNSForge : 4 endpoints × A/AAAA, tous réussis avec dnspython/aioquic et
  vérification TLS. Ce test réseau ne remplace pas un essai sur chaque box.
- DNS classique : 18 adresses IPv4 × A/AAAA. Cinq endpoints ont eu un timeout
  lors de la première série ; leur nouvel essai séquentiel passe pour A et AAAA.
- Les 18 adresses IPv6 sont renseignées d'après les sources officielles, mais
  non joignables depuis cette machine sans route IPv6. Les réponses AAAA reçues
  sur transport IPv4 ne prouvent pas une connectivité IPv6 de bout en bout.
- Compilation APK debug réussie, 48 tests unitaires passent, 515 ressources ×
  12 langues vérifiées ; quatre ABI et alignement 16 Ko contrôlés.
- Résultats réseau détaillés : `2026-11-27-european-dns-probes.json`.

## Secours xdp / Worker

Voir `../../tools/emergency-dns/README.md` : sources xdp MIT archivées avec commit
et checksum, Worker indépendant préparé à partir des bases de l'archive fournie.
Son export contient un service de filtrage DoH, pas l'algorithme xdp complet.

Le Worker préparé protège un endpoint par secret aléatoire 256 bits et limiteur
obligatoire. Il reste désactivé et sans route publique. Réécritures temporaires
sur noms/IP explicites, A/AAAA et hints HTTPS/SVCB, aucun effacement d'IPv6,
AD retiré lorsque la réponse est modifiée. Il ne déduit pas les blocages du FAI
et ne garantit pas la validité DNSSEC des données réécrites.

14 tests Node passent : accès désactivé/inconnu, limiteur, upstream autorisé,
paquets malformés/cycles/tailles, IDs, A/AAAA/HTTPS, expiration, rejets d'adresses
privées et changements de famille, flux DoH GET/POST et erreurs amont.
Le test HTTPS hint a révélé un problème d'offset avec les Buffer Node ; la copie
normalisée Uint8Array le corrige. Les réponses de production sont également
traitées sous ce format uniforme.

Deux requêtes réelles example.com A/AAAA passent à travers le handler en local.
Wrangler 4.84.0 accepte la configuration et produit le bundle en `--dry-run`
(aucun upload). Ce n'est pas un test d'un endpoint déjà déployé chez Cloudflare.
Aucune URL de secours active n'existe encore : l'activation explicite, le secret,
le hostname et la vérification depuis le réseau affecté restent nécessaires.

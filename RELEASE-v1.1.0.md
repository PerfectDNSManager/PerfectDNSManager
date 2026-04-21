# PerfectDNSManager 1.1.0 — Migration vers Cloudflare Workers

**Date :** 2026-04-20

## En bref

Refonte du back-end de partage. Les deux services VPS (`vault.appstorefr.net` pour le stockage, `cut.appstorefr.net` pour les liens courts) sont remplacés par un Worker Cloudflare unique sur `pdm.appstorefr.net`. Un seul appel HTTP là où il y en avait deux. Partage toujours chiffré de bout en bout (AES-256-GCM, clé en fragment d'URL, jamais transmise au serveur).

## Pourquoi

Le partage de configs et de rapports reposait sur deux services Flask hébergés sur un VPS. Panne du VPS = plus de partage. L'app reste utilisable pour son cœur de fonction (changement DNS local), mais tout ce qui concerne l'export/import/partage tombait avec le serveur.

Le passage à Cloudflare Workers :

- **résilience** : infra distribuée, pas de VPS unique à maintenir, uptime aligné sur celui de Cloudflare ;
- **stockage éphémère natif** : KV gère l'expiration automatiquement (1h par défaut, jusqu'à 30 jours) ;
- **moins de surface d'attaque** : le Worker n'a pas de shell, pas de filesystem, pas de base de données à patcher ;
- **vrai E2EE** : contrairement à l'ancien flux où le serveur court-lien connaissait la clé de déchiffrement (elle passait dans son Location header), le Worker ne voit que du ciphertext. La clé reste côté client, dans le fragment de l'URL (`#...`), qui n'est jamais envoyé au serveur par les navigateurs.

L'app survit donc à une défaillance du serveur principal : même si le VPS tombe, le partage continue de fonctionner via Cloudflare.

## Ce qui change pour vous

**À l'usage :** rien. Le bouton "Partager" et l'import par code fonctionnent comme avant. Seuls les liens générés ressemblent désormais à `https://pdm.appstorefr.net/123456#…` au lieu de `https://cut.appstorefr.net/123456`.

**Pour les imports :** l'ancienne saisie d'un code à 6 chiffres seul est remplacée par le collage du lien complet (parce que la clé est dans le fragment, pour que même nous ne puissions pas déchiffrer vos exports).

**Transition :** l'ancienne infrastructure VPS reste en ligne jusqu'au 2026-05-20 pour que les anciennes versions (1.0.x) continuent de fonctionner. Mise à jour recommandée.

## Nouveautés

- **Messages broadcast** : l'app peut désormais recevoir au démarrage un message d'information publié côté serveur (annonces, alertes, guides de mise à jour). Affiché une fois puis masqué.
- **Correctif profils NextDNS personnalisés** : la suppression d'un profil NextDNS perso nettoie désormais correctement l'entrée dans les préférences (avant, l'ID restait référencé et pouvait réapparaître de façon inattendue).
- **Simplification de l'export partagé** : un seul appel HTTP au lieu de deux → partage plus rapide, moins de points de défaillance.

## Détails techniques

- Nouveau sous-domaine : `pdm.appstorefr.net` (Cloudflare Worker, KV namespace dédié).
- Format de payload : `IV (12 octets) || ciphertext || tag GCM (16 octets)`, binaire brut (plus de base64 sur le réseau, économie de 33%).
- Clé AES-256 transmise en base64url dans le fragment de l'URL (`#...`), jamais dans les headers ni le body HTTP.
- Expiration par défaut 1h pour les configs, 72h pour les rapports IP. Paramétrable côté Worker (1h, 6h, 24h, 48h, 72h, 7d, 30d).
- Page de déchiffrement servie directement par le Worker (WebCrypto AES-GCM), plus de dépendance à GitHub Pages.

## Fin de vie

- Services VPS `vault.appstorefr.net` et `cut.appstorefr.net` : décommissionnement prévu le **2026-05-20** (30 jours après la sortie de la 1.1.0).
- Versions PDM ≤ 1.0.81 : fonctionnelles jusqu'à cette date, ensuite le partage cessera de fonctionner sur ces versions. Mise à jour recommandée avant cette échéance.

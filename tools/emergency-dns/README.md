# Secours DNS privé — prêt à préparer, non déployé

Ce répertoire conserve deux possibilités distinctes. Aucun service Cloudflare,
route publique, DNS ou cron n'a été créé. Aucun profil de secours n'est inscrit
dans le catalogue PDM ; la stable n'est pas modifiée.

## 1. Reconstituer le service xdp sur un serveur

`vendor/xdp-dns-evadeproxy-455427e.tar.gz` contient les sources Git complètes
(version suivie, hors dépendances téléchargées et artefacts de compilation) du
commit `455427ea2af820ee30629f3d71814b2a7c273c32`, licence MIT jointe.
SHA256 : `8ea8a22cb0bf4ab6df1266c4b89696daa6205c7424afd292740ba0f2625af0d1`.

L'archive contient le README, le proxy Rust, les sondes, les scripts et Docker.
Extraire dans un répertoire dédié, lire la configuration avant tout lancement.
Les sources sont conservées même si le service xdp disparaît. Le fonctionnement
automatique demande cependant toujours des données de blocage et une sonde
sur le réseau résidentiel concerné. Les dépendances Rust doivent être obtenues
pour reconstruire. Ne pas exécuter les installateurs système sur une box Android.

Attention au mode amont qui supprime AAAA/HTTPS lorsqu'une redirection n'est
vérifiée qu'en IPv4, et aux conséquences des réécritures sur DNSSEC. Voir
`../../docs/features/xdp-and-anti-dpi-assessment.md`.

## 2. Worker DoH de secours, avec réécritures explicites

Le `worker.mjs` reprend et durcit les bases `readDnsBody` et analyse de question
DNS de l'archive fournie par l'utilisateur, et le principe des profils DoH privés.
Archive fournie le 27 novembre 2026 : `dns-worker.zip`, SHA256
`9de5468e616b9b9fef09518bfccc78882cb6d701df641a34a18e2415922d9c5f`.
L'original est conservé localement dans `build/dns-emergency-review/`, sans
publication de sa configuration ni ajout de son gros catalogue à l'APK.

L'export fourni est un Worker de filtrage DoH/profils (et contient aussi des
fonctions de gestion, routage géographique et listes). Il ne contient pas le
moteur xdp. Le secours est donc une adaptation ciblée, pas une copie prétendument
équivalente au service xdp complet.

Fonctions : DoH binaire POST/GET, résolution via un upstream HTTPS autorisé,
remplacements explicites A/AAAA et hints HTTPS/SVCB pour un nom exact et une IP
source exacte, même famille seulement, TTL plafonné à 30 secondes si modifié.
Les réponses AAAA ne sont jamais supprimées. Les règles expirent ; la durée
future maximale acceptée est sept jours. Sans règle, la réponse reste intacte.

Le Worker ne sonde pas le réseau de l'abonné et ne choisit pas arbitrairement
l'IP voisine : l'administrateur doit vérifier les adresses de remplacement,
notamment TLS/SNI et HTTP pour le domaine voulu, depuis le réseau affecté.
Il ne suffit pas qu'une IP soit joignable depuis Cloudflare. Les règles peuvent
corriger certains blocages IP, pas garantir un contournement SNI/DPI/géographique.

## Protection et ressources

- Désactivé par défaut (`ENABLED=false`), aucune route, workers.dev et previews
  désactivés. Aucun téléchargement périodique ni KV/R2/IA/cron.
- URL `/dns-query/<jeton aléatoire de 256 bits>` : le jeton est un secret d'accès,
  pas simplement un chemin « introuvable ». Quiconque connaît l'URL peut l'utiliser.
  Ne pas le publier dans Git, captures ou rapports ; le renouveler en cas de fuite.
- Jeton dans un secret Worker, contrôle avant lecture du corps et requête amont.
  Limitation obligatoire par IP, échec fermé si le binding manque ; pas de logs
  applicatifs ni d'observabilité configurée, réponses no-store/noindex.
- 4096 octets en entrée, 8192 en sortie, validation des en-têtes/questions,
  compression et limites des enregistrements, vérification question/ID en retour,
  upstreams fixes autorisés, pas de redirects et délai amont de cinq secondes.
- Une adresse réécrite perd son bit AD. Le Worker ne valide pas DNSSEC lui-même ;
  les signatures amont ne sont plus valables sur les données changées. Un client
  validateur peut donc les rejeter. Ne pas promettre « réécriture + DNSSEC intact ».
- Une fois exposé, même les requêtes refusées consomment des invocations Worker.
  Le rate limiting est approximatif et local à chaque emplacement Cloudflare,
  pas un plafond global de facturation. Aucun coût nul n'est promis après activation.

## Vérifier avant activation

```sh
node --test tools/emergency-dns/worker.test.mjs
node tools/emergency-dns/live-smoke.mjs --live
wrangler deploy --dry-run --config tools/emergency-dns/wrangler.jsonc
```

Le test live n'envoie que des questions example.com A/AAAA, avec un jeton jetable
local. Il ne déploie rien. Wrangler 4.36+ est nécessaire au binding de limitation.

Pour une activation ultérieure : choisir un Worker et hostname dédiés, vérifier
que le namespace du limiteur ne partage pas un autre service, générer un secret
avec `openssl rand -hex 32` et le fournir via `wrangler secret put DNS_PROFILE_TOKEN`.
Ne pas copier une valeur d'exemple. Ajouter une route privée choisie, activer
ENABLED et déployer explicitement. Tester le endpoint réel avant d'ajouter son
URL comme profil DoH personnalisé dans PDM. Ces étapes ne sont pas exécutées ici.

`RULES_JSON` est une liste de `{name, from, to, expires}` ; `expires` est une date
en millisecondes Unix, au plus sept jours dans le futur. Une règle expirée est
ignorée. Les adresses privées, les changements de famille et les règles invalides
sont rejetés. Garder `[]` tant qu'aucune destination n'a été vérifiée.

Sources : https://github.com/Oihalitz/xdp-dns-evadeproxy et
https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/.

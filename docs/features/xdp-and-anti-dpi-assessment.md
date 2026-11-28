# Étude xdp et anti-DPI — 27 novembre 2026

Source examinée : Oihalitz/xdp-dns-evadeproxy, commit
`455427ea2af820ee30629f3d71814b2a7c273c32` (licence MIT).
Aucun script d'installation ni sonde de scan n'a été lancé. Aucune infrastructure
Cloudflare n'a été créée et aucune publication PDM n'a été déclenchée.
Les 24 tests Rust amont passent (`cargo +stable test --locked`), dont le test
qui confirme la suppression AAAA/HTTPS pour une redirection IPv4 seule.

## Fonctionnement réel

Ce projet est un proxy DNS Rust en espace utilisateur, pas un module eBPF/XDP.
Il reçoit une réponse d'Unbound, remplace certaines IP bloquées et renvoie la
réponse au client. Pour les plages officielles Cloudflare, il choisit une autre
adresse du même préfixe ; pour les autres CDN, il utilise des redirections
vérifiées par sondes TLS/SNI et HTTP depuis un datacenter et une connexion
résidentielle. Une IP absente d'une liste de blocage n'est toutefois pas une
preuve qu'elle fonctionnera chez chaque utilisateur.

Le trafic web continue directement du client vers l'IP choisie : aucun VPN de
sortie, masquage d'IP publique ou tunnel général n'est fourni. Cela peut aider
contre certains blocages d'IP, mais ne garantit pas de contourner un blocage SNI,
un contrôle géographique ou un refus du serveur lui-même.

Le catalogue PDM contient déjà `lite.xdp.es` (standard) et `dns.xdp.es`
(Adblock). Le README amont annonce la même évasion pour les deux profils : il
n'est donc pas nécessaire d'embarquer ce proxy pour utiliser le service xdp.
Ce choix confie les requêtes à xdp ; il ne conserve pas un autre fournisseur.

## Deux incompatibilités à traiter avant un portage local

* `suppress_unverified_family` vide les réponses AAAA, HTTPS et SVCB dans le cas
  d'une redirection vérifiée IPv4 sans équivalent IPv6. Ce comportement ne doit
  pas être activé implicitement dans PDM : il contredirait la conservation
  d'IPv6 demandée par l'utilisateur.
* La réécriture supprime le bit AD car les adresses ne correspondent plus aux
  signatures DNSSEC. Une validation locale doit précéder la réécriture ; un
  validateur en aval peut toujours rejeter les signatures devenues invalides.
  Ne pas présenter ces réponses modifiées comme authentifiées par DNSSEC.

## Choix d'architecture

1. **Immédiat : profil xdp standard existant.** Aucun Worker ou coût d'hébergement
   PDM supplémentaire. Les politiques du service xdp, dont les réécritures,
   s'appliquent en amont et restent hors du contrôle local de PDM.
2. **Local expérimental : après la résolution sélectionnée.** Adaptation de la
   logique au pipeline DNS PDM, désactivée par défaut, cibles vérifiées,
   conservation d'IPv6 et état DNSSEC explicite. Il faudrait borner les listes,
   leur ancienneté, les tentatives et le coût des sondes. Une liste distante ne
   suffit pas à déterminer les blocages propres au FAI de la box. Aucun moteur
   de réécriture automatique xdp n'est intégré à cette étape.
3. **Worker : pas nécessaire pour le chemin DNS.** Le daemon Rust/Unbound et les
   services systemd ne s'exécutent pas tels quels dans un Worker. Une adaptation
   DoH JS/Wasm serait un autre service, avec consommation de requêtes et un
   point d'observation Cloudflare qui ne représente pas le réseau de la box.
   Un hébergement de métadonnées mises en cache serait plus raisonnable qu'un
   passage de toutes les résolutions par un Worker. Ne pas promettre un coût
   nul ni un contournement depuis la seule connectivité du Worker.

## Ancienne tentative anti-DPI

Le dépôt privé `appstorefr/PerfectDNSManager-Advanced` annonce une intégration
ByeByeDPI dans sa description. L'arbre main examiné
`5913f9691a67142928c0a6072ecc26925470287a` ne contient cependant pas de moteur
ByeDPI/tun2socks retrouvé, ni de script anti-DPI identifié. Les recherches dans
les sessions Claude locales et les commits indexés n'ont pas permis de
retrouver le script évoqué. Ce dépôt est une piste, pas la preuve d'une
intégration récupérable.

ByeByeDPI est une autre approche : proxy SOCKS local et tunnel qui transporte
le trafic des applications, pas uniquement les requêtes DNS. Son intégration
système dans PDM exigerait un moteur de transfert du trafic TCP/UDP et une
gestion commune du VPN Android. Deux services VPN concurrents ne résolvent
pas le problème. Ne pas ajouter cette charge à toutes les anciennes box ni
activer ce routage sans option explicite et tests dédiés.

Sources :
- https://github.com/Oihalitz/xdp-dns-evadeproxy
- https://github.com/Oihalitz/xdp-dns-evadeproxy/blob/455427ea2af820ee30629f3d71814b2a7c273c32/src/main.rs
- https://developers.cloudflare.com/workers/runtime-apis/tcp-sockets/
- https://github.com/romanvht/ByeByeDPI/blob/master/README-en.md

## Mise à jour — bêta 2.4.15

L’option locale utilisant les moteurs ByeDPI et HEV est décrite dans
[Anti-DPI local](local-anti-dpi.md). Cette étude reste le contexte historique ;
le mode DoT système/ADB ne lance toujours pas de tunnel anti-DPI.

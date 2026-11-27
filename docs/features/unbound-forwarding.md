# Unbound local avec fournisseur DoT

Option expérimentale de la préparation 2.4.12-beta.1, désactivée par défaut.

Dans les paramètres DNS avancés, activer « Afficher les profils Unbound ».
Choisir ensuite un profil « Unbound · DoT » chez le fournisseur souhaité et
activer PDM. Afficher ces profils ne démarre pas le moteur. Il s'arrête lorsque
PDM est désactivé ou qu'un autre type de profil est activé.

Chemin des requêtes :

    Application → VPN DNS PDM → préfiltre éventuel → Unbound → fournisseur DoT

Unbound apporte un cache local partagé et vérifie DNSSEC pour les zones signées.
Le fournisseur continue à résoudre les noms et à appliquer ses politiques.
Ce mode n'est donc ni un résolveur autonome « neutre », ni un outil anti-DPI.
Le préfiltre suivi directement d'un fournisseur DoH/DoQ reste suffisant pour
bloquer des domaines localement avec un fournisseur au choix.

## Anciennes box

Cette intégration permet du DoT sans configuration ADB, dès Android 5, au moyen
du VPN PDM. Elle embarque une bibliothèque TLS et un magasin de certificats
récents, ce qui peut aider lorsque le vieux magasin système ne reconnaît plus
le certificat du fournisseur. Ce bénéfice appartient à cette intégration et
à ses dépendances, pas à une propriété magique d'Unbound. Les autres applications
et Android lui-même ne sont pas mis à jour par cette option.

Les connexions restent authentifiées : ne pas désactiver la vérification TLS
pour compenser une horloge incorrecte, un certificat expiré ou un fournisseur
injoignable. Le hostname du fournisseur est résolu une fois par le DNS du réseau physique
avant le démarrage du VPN (sans utiliser un ancien VPN en cours de fermeture) ; les requêtes des applications transmises par ce
moteur utilisent ensuite le DoT, sans repli DNS non chiffré.

Si le DoH existant fonctionne déjà bien, le bénéfice peut être limité. Unbound
consomme de la mémoire et grossit l'APK. Il occupe le VPN Android comme les autres
modes locaux de PDM ; le DoT natif Android/ADB reste le choix sans VPN.
Il ne répare pas la connectivité IPv6 d'une box ou d'un FAI.

## État des vérifications

Voir `../audit/2026-11-27-unbound-beta.md`. Les résultats sur émulateur ne remplacent
pas un essai sur les box physiques, notamment ARM 32 bits et réseaux changeants.
Aucune publication stable n'est autorisée par cette préparation.

## Unbound peut-il fonctionner sans fournisseur ?

Oui. Unbound sait être un résolveur récursif autonome : racine DNS → serveurs du
TLD → serveurs faisant autorité pour le domaine. Dans ce mode, il ne demande pas
la réponse complète à Cloudflare, ControlD ou un autre résolveur public.
DNSSEC authentifie les données des zones signées ; il ne chiffre pas ces échanges.
La récursion directe utilise généralement le DNS classique UDP/TCP 53, car les
serveurs faisant autorité ne proposent pas tous un transport chiffré utilisable.

PDM expose actuellement uniquement le mode forwarder avec DoT authentifié, pour
la demande de sortie chiffrée et de choix du fournisseur. Le JNI configure
`ub_ctx_set_fwd`, `ub_ctx_set_tls(1)` et `do-udp: no`. Un mode récursif autonome
serait une autre option explicite, avec des exigences réseau et de confidentialité
différentes ; il n’est pas activé dans cette bêta.

Documentation officielle : https://unbound.docs.nlnetlabs.nl/en/latest/

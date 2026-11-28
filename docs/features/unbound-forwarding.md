# Unbound local : autonome ou fournisseur DoT

Depuis 2.4.14-beta.1, les deux modes sont optionnels et désactivés à la première
installation. Dans **Paramètres DNS avancés**, activer **Afficher les profils
Unbound**, puis choisir le profil dans la liste DNS et activer PDM.

- **Unbound → Autonome** : résolution récursive directe, sans fournisseur DNS
  intermédiaire. Le moteur contacte les racines puis les serveurs autoritaires.
  Les échanges utilisent le DNS classique non chiffré en UDP/TCP 53 ; ce port
  doit être autorisé par le réseau. Le premier résultat peut prendre plus de temps.
- **Un fournisseur → profil Unbound** : cache et validation locale, avec
  transmission au fournisseur en DoT authentifié. La description du profil
  porte la mention « Unbound · DoT ». Aucun repli en DNS non chiffré.

Les deux modes utilisent le VPN DNS PDM, son éventuel préfiltre local, un cache
et la validation DNSSEC des zones signées. DNSSEC authentifie les données mais
ne chiffre pas les échanges. Le mode autonome ne garantit pas qu’un réseau
n’intercepte pas le port 53 ; la validation protège les zones signées, pas les
zones non signées. Il ne contourne pas une panne réseau ou un blocage du port 53.

Afficher les profils ne démarre aucun moteur. Unbound démarre uniquement quand
son profil est activé et s’arrête à la désactivation ou au changement de profil.
Les profils Unbound sont exclus du benchmark automatique. Les anciennes
configurations avec fournisseur DoT et les profils personnels restent valides.
Le choix du mode ne modifie ni le fournisseur enregistré ni les réglages IPv6.

## Anciennes box et IPv6

Android 5/API 21 reste pris en charge sur quatre architectures, avec alignement
16 Ko des bibliothèques natives. Le mode DoT embarque TLS et ses certificats CA,
ce qui peut aider sur une ancienne box dont le magasin système est périmé.
Ce bénéfice ne met pas à jour les autres applications ni Android lui-même.

IPv6 est autorisé par défaut. Si l’utilisateur active son blocage, Unbound utilise
IPv4 pour ses échanges amont, dans les deux modes. Il continue à répondre aux
questions AAAA : le choix du transport ne supprime pas les données DNS IPv6.

## Implémentation et limites

- Profil autonome explicite `unbound://recursive`, ID de preset 90000. Aucun
  hostname à résoudre au démarrage ; seules les ancres DNSSEC sont chargées.
- Hints racine intégrés à la version épinglée d’Unbound ; aucun chargement de
  `resolv.conf`, aucun forwarder et aucun bootstrap DNS système en mode autonome.
- Le JNI refuse un mode DoT sans adresse et un mode autonome avec des forwarders.
  Les profils mélangeant autonome et fournisseur en DNS secondaire sont rejetés.
- DoT : CA + SNI + nom authentifié ; le hostname du fournisseur est résolu sur
  le réseau physique, pas via un ancien VPN en cours de fermeture.
- Minimisation des noms interrogés, caches bornés, 128 ports/sockets UDP sortants
  maximum et 8 connexions TCP sortantes ; 64 requêtes natives en attente maximum.
  Délai natif 20 secondes pour une récursion à froid, 8 secondes pour le relais DoT.
- Aucun serveur d’écoute DNS ouvert sur le réseau local : la bibliothèque reçoit
  les requêtes du VPN dans le processus de PDM. La plage DNS synthétique du VPN
  est exclue des destinations amont pour empêcher une boucle locale.
- Il occupe le VPN Android. Le DoT natif Android/ADB reste le mode sans VPN.
  Ce n’est pas un moteur anti-DPI.

Documentation officielle : https://unbound.docs.nlnetlabs.nl/en/latest/manpages/libunbound.html

Cette version est une bêta. Aucune publication stable ni modification de Worker.

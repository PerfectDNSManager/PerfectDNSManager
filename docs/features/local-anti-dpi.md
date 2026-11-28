# Anti-DPI local optionnel (bêta)

Dans les paramètres DNS avancés, « Anti-DPI local (bêta) » est désactivé par
défaut. Il utilise les moteurs ByeDPI et hev-socks5-tunnel également employés
par ByeByeDPI ; l'application ByeByeDPI n'est pas lancée et aucun deuxième VPN
n'est créé.

## Fonctionnement

Avec un profil VPN PDM (DoH, DoQ, DNS classique ou Unbound), les paquets destinés
aux adresses DNS virtuelles restent dans le pipeline DNS PDM : préfiltre,
réécritures et fournisseur choisi. Le trafic TCP/UDP des autres applications
passe par un pont de paquets borné, HEV puis le proxy ByeDPI local sur loopback.
Le preset initial fractionne le début du nom SNI/Host (`--split 1+s`).

Ce preset est expérimental : il ne contourne pas tous les équipements DPI,
ne traite pas l'inspection QUIC par une stratégie de désynchronisation, ne
masque pas l'IP publique et n'ajoute pas de chiffrement. Le TLS des applications
reste de bout en bout ; aucun certificat utilisateur ou MITM n'est installé.
Le routage complet peut augmenter la consommation et affecter des protocoles
non TCP/UDP, dont ICMP. Une application exclue par le split tunneling continue
à contourner le VPN. L'application PDM elle-même est exclue pour éviter les
boucles des sockets natives et conserver l'accès aux fournisseurs DNS.

Le mode DoT système Android/ADB n'utilise pas le VPN PDM et n'active donc pas
l'anti-DPI. Choisir un profil VPN pour utiliser cette option. Le mode Unbound
avec fournisseur DoT, lui, utilise le VPN et peut être combiné avec l'anti-DPI.

IPv6 n'est pas désactivé implicitement : quand il est autorisé, il est transmis
par le moteur ; quand son blocage est explicitement choisi, les paquets IPv6
sont arrêtés par PDM. Les réponses DNS AAAA ne sont pas supprimées.

## Cycle de vie et bornes

- Moteur natif chargé uniquement dans un processus privé `:antidpi`, créé à
  l'activation et détruit à l'arrêt. Une session par processus évite la
  réutilisation des variables globales des bibliothèques après redémarrage.
- Le service Binder n'est pas exporté et vérifie l'UID appelant. Aucun serveur
  SOCKS n'est exposé au LAN ; le port loopback est attribué par le noyau.
  Comme les autres proxys SOCKS locaux sans authentification, il peut être
  atteint par une autre application locale disposant d'accès réseau.
- 64 connexions/sessions maximum, buffers bornés, délais de connexion et
  d'inactivité ; un pont saturé abandonne des paquets sans bloquer le lecteur DNS.
- La perte du moteur est détectée : le VPN est arrêté, l'état connecté est
  retiré et une notification explique l'échec. Ce n'est pas un kill switch :
  Android reprend son réseau normal après l'arrêt du VPN.
- Les traces de trafic ByeDPI sont supprimées à la compilation ; les journaux HEV
  vont vers `/dev/null`. Les notifications et erreurs génériques PDM restent disponibles.
- Aucun argument de ligne de commande arbitraire, script, téléchargement de
  stratégie ou changement des certificats de confiance n'est proposé.
- API 21 minimum, quatre ABI, alignement ELF 16 Ko. Les performances sur box
  ARM physiques nécessitent des essais matériels ; une compilation ne suffit
  pas à les garantir.

Les sources, licences et modifications locales sont décrites dans
`../../native/antidpi/README.md`. Les outils de test utilisent une application
avec un UID distinct : les moteurs PDM sont volontairement exclus du tunnel,
un simple test lancé depuis leur processus ne prouverait pas le routage.

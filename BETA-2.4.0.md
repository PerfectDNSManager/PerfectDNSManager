# 2.4.0-beta.2

Cette bêta reprend les changements non publiés de septembre et corrige les problèmes relevés pendant la reprise.

- Les actions de notification utilisent une activité privée. Un referrer fourni par une autre application ne peut plus autoriser un démarrage.
- Les transactions UDP utilisent des sockets connectés, un identifiant aléatoire et une validation de la question DNS. Les tests DNS partagent ces contrôles.
- Les règles de réécriture reconstruisent les messages DNS complets (CNAME, compression, EDNS). Les réponses tronquées ou étrangères sont refusées.
- Les imports sont validés entièrement avant les écritures ; les ressources Android externes sont neutralisées. Les exclusions respectent le choix « sans réglages » et les sections absentes sont conservées.
- Le service vérifie le DNS privé avant chaque démarrage, y compris après un redémarrage système. Les opérations de tunnel sont sérialisées ; les anciennes réponses ne peuvent pas rejoindre une nouvelle session.
- Délais réseau, nettoyage DoQ/ADB, HTTPS strict et validation TLS renforcés. Le résolveur choisi conserve son niveau de filtrage.
- Compatibilité API 21 : couleurs, focus, effets foreground et API TLS corrigés ; le démarrage attend le déverrouillage.
- Les APK téléchargés ont un fichier unique, une taille plafonnée, un nom de paquet et une signature vérifiés.
- Les partages bêta utilisent beta.perfectdnsmanager.app et un stockage distinct. Les liens complets stables restent importables.

Validation : tests JVM DNS/imports, compilation release et lint Android. Le worker dispose de tests de limite mémoire, expiration, panne du limiteur et collision atomique ; un aller-retour HTTPS/R2 réel avec des octets synthétiques est validé. Aucun test sur appareil TV/Fire TV n’a encore été effectué pour cette version.

Le mot de passe de partage à six caractères est conservé conformément au choix existant. L’expiration coupe l’accès serveur ; elle ne rend pas inutilisable une copie déjà téléchargée.

Installation : canal bêta de l’application ou code Downloader **2447296**. Conserver une exportation de configuration avant les essais ; Android peut refuser le retour à une version de code inférieur sans désinstallation.

Références des correspondances DoH : https://docs.quad9.net/services/ et https://developers.cloudflare.com/1.1.1.1/setup/.

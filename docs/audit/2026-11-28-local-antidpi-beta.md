# Anti-DPI local — 2.4.15-beta.1

Branche `codex/pdm-antidpi-beta`, bêta uniquement. Stable 2.4.11, main,
Worker et site non modifiés.

## Périmètre

Moteurs ByeDPI/HEV épinglés, compilés depuis les sources pour quatre ABI.
Processus privé à session unique, socket loopback attribuée par le noyau,
64 sessions et buffers bornés, aucune commande arbitraire ou stratégie distante.
IPv6 autorisé par défaut, réglage de blocage explicite conservé. Le préfiltre
et le fournisseur restent dans le pipeline DNS PDM. Le DoT système ne démarre
pas le moteur. Détails et limites : [fonctionnement](../features/local-anti-dpi.md).

## Contrôles déjà réalisés

- 56 tests unitaires : aucun échec ; 520 ressources dans 12 langues validées.
- Build et lint Android ; correction de l’appel `fcntlInt` indisponible sur API 21.
- Alignement des bibliothèques natives, quatre ABI et API 21 conservés.
- 199 entrées SOCKS malformées sur ByeDPI hôte avec ASan/UBSan : aucun diagnostic.
  Test borné, pas une preuve d’absence de vulnérabilités natives.
- Capture sur l’AVD moderne : deux connexions sortantes montrent le nom SNI
  réparti entre segments TCP de 126 et 132 octets, TLS valide sur une fixture locale.
- Correction du cycle arrêt/redémarrage : attente de mort du processus natif
  précédent avant une nouvelle liaison Binder.

Les résultats des validations finales et de publication seront ajoutés après
leur exécution. Aucun résultat matériel ARM ou contournement de DPI opérateur
réel n’est revendiqué. IPv6 est testé avec la fixture locale accessible par
l’alias IPv6 de QEMU, pas avec une connectivité Internet IPv6 publique.

## Défauts révélés par les tests et corrections

- Démarrage du worker : délai porté à 30 secondes pour les machines chargées.
- Android 5 : une lecture bloquante gardait une référence noyau au TUN après
  sa fermeture. Lecture non bloquante avec poll borné et arrêt par génération.
- Android 5 sur réseau physique IPv4 seul : les adresses IPv6 du fournisseur
  provoquaient un délai/échec initial avec le tunnel complet. L’amorçage filtre
  désormais les adresses selon les routes du réseau physique, jamais celles du
  VPN. Les réponses AAAA et le choix utilisateur de blocage restent intacts.
- Les premiers essais instrumentés avaient un état de préfiltre persistant et
  une sonde en arrière-plan (restrictions Android 16). Les tests restaurent leur
  état et lancent la sonde distincte au premier plan.

## Validation instrumentée finale (source du candidat)

- API 21 x86 : scénario complet réussi en 15,7 s avec Unbound/DoT Cloudflare,
  IPv6 autorisé. Sonde d’UID distinct : TCP, TLS avec certificat de fixture
  épinglé, UDP, DNS A/AAAA, préfiltre NXDOMAIN, désactivation/réactivation,
  changement de session et arrêt du moteur. Le test IPv6 local dédié est
  réservé à l’AVD moderne et ignoré sur cet AVD.
- API 36 x86_64/16 Ko : deux scénarios réussis en 63,9 s. DoH Cloudflare puis
  Unbound/DoT ; mêmes contrôles, IPv6 vers la fixture autorisé, puis bloqué
  uniquement sur demande explicite, sans supprimer AAAA.
- API 21 : tests Unbound natifs séparés réussis. Le certificat public DoH
  n’était pas accepté par l’ancien magasin de CA de l’image ; aucune validation
  TLS de production n’a été désactivée pour contourner cette limite.
- ARMv7 : les nouvelles sources C sont compilées avec VFPv3-D16, sans imposer
  NEON ; contrôle des attributs des objets compilés. Pas d’essai physique ARM.

## APK optimisé

- Build release, 56 tests unitaires et lint : réussite, zéro erreur lint.
- Alignement ELF/APK 16 Ko, API 21 et quatre ABI contrôlés ; les moteurs dans
  l’APK correspondent aux bibliothèques finales. Aucune fixture de test embarquée.
- SmokeRunner de framework sur API 21/4 Ko et API 36/16 Ko : réussite complète
  (Argon2, ADB lorsque disponible, import/export Gson, quatre écrans, anti-DPI,
  DNS A/AAAA, préfiltre, TCP/TLS/UDP ; IPv6 local sur API 36).
- Sur l’image 16 Ko, l’installation incrémentale ADB a causé un SIGBUS dans le
  chargeur ELF avant les tests. La réinstallation standard du même APK avec
  `adb install --no-incremental -r` a permis de réussir toute la suite. Aucun
  changement du code ou de l’alignement n’a été fait pour ce nouvel essai.
- Ces copies optimisées sont signées avec la clé de test Android uniquement
  pour l’instrumentation. Seul l’APK signé par la CI est destiné à la publication.

- Contrôle supplémentaire de l’APK optimisé sur API 36 : profil DoQ Control D
  (`quic://p0.freedns.controld.com`), sonde réseau distincte et préfiltre réussis.

## Confidentialité des moteurs tiers

La première fabrication CI a été annulée avant publication : le mode
`ANDROID_APP` de ByeDPI active les traces d’hôtes et de premiers octets même
avec debug à zéro. Le patch désactive `LOG` et `LOG_ENABLED` dans ce mode.
Les erreurs génériques de syscall et les états PDM restent disponibles.
HEV écrit vers `/dev/null`. Un contrôle de construction vérifie l’absence
des formats de traces sensibles dans les quatre bibliothèques ByeDPI.

Après désactivation des traces, l’APK optimisé passe à nouveau sur API 36/16 Ko.
La capture montre toujours le SNI découpé en segments de 126 et 132 octets ;
aucune trace proxy d’hôte ou de contenu n’est présente dans logcat.

La variante sans traces passe aussi le scénario réseau optimisé sur API 21
(Unbound/DoT, A/AAAA, préfiltre, TCP/TLS/UDP). Un redémarrage de l’AVD a été
nécessaire pour réinitialiser l’autorisation VPN après les désinstallations
successives de copies signées différemment ; le consentement a ensuite été
accordé via le dialogue Android normal. Logcat proxy ne contient aucune trace.

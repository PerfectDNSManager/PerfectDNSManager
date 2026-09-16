# Régression DoH de la bêta 6

Signalement : perte d’accès Internet après activation d’un profil DoH sur Fire Stick.

## Cause reproduite

L’APK public `2.4.0-beta.6` reproduit sur AVD Android 13 l’erreur `DoH err: IOException: Cannot protect DNS socket`. Le contrôle ajouté à la fabrique de sockets appelle `VpnService.protect(Socket)` avant la création du descripteur natif : le simple constructeur `Socket()` ne le crée pas sur Android. Le contrôle échoue, les connexions DoH sont refusées et le tunnel ne fournit plus de réponses DNS.

Le correctif lie le socket à une adresse/port local avant `protect()`, puis le connecte. Il conserve le refus de connexion si la protection échoue. Les surcharges avec une adresse locale demandée utilisent cette adresse dès le premier bind, sans double bind.

Référence Android : [implémentation VpnService.protect(Socket)](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/VpnService.java), qui utilise directement le descripteur natif.

## Pourquoi les validations précédentes étaient insuffisantes

Les 17 tests JVM et le lint ne lançaient pas de VPN Android réel. La revue précédente ne constituait donc pas une validation fonctionnelle du transport. Un ping par `adb shell` ne suffit pas non plus : l’UID shell peut contourner le VPN et la réponse peut venir du cache système.

Le nouveau test `DohVpnInstrumentedTest` s’exécute dans l’application sur un AVD jetable. Il active le VPN, adresse deux requêtes DNS explicitement à son serveur virtuel `192.0.2.2`, vérifie les réponses et ouvre `https://example.com/`. Il est limité aux émulateurs ranchu/goldfish et nécessite un accès Internet. Il modifie l’autorisation VPN, désactive le DNS privé système et modifie les préférences réseau de PDM sur cet AVD : ne pas utiliser sur un environnement contenant des données à préserver.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/PerfectDNSManager-v2.4.0-beta.7-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class app.perfectdnsmanager.DohVpnInstrumentedTest -e dohEndpoint https://one.one.one.one/dns-query app.perfectdnsmanager.test/androidx.test.runner.AndroidJUnitRunner
```

L’installation de debug ne peut pas remplacer l’APK public signé : utiliser un AVD jetable et désinstaller uniquement PDM si nécessaire. Un résultat sur Android/Android TV ne remplace pas l’essai sur Fire OS physique.

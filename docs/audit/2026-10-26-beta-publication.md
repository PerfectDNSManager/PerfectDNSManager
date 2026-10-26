# Publication bêta — 26 octobre 2026

Publication du canal Android bêta autorisée par l’utilisateur. Version 2.4.1-beta.design.1, versionCode 20401, paquet release app.perfectdnsmanager. La stable 2.4.0 reste la dernière release stable. La prévisualisation du site reste locale dans cette publication Android.

## Correction DoT système

Le panneau du démarrage automatique consultait uniquement selected_profile_json et affichait « Aucun DNS configuré » pour un DNS privé posé directement via ADB/Android. Il lit désormais le mode et le hostname système à chaque retour sur les paramètres, affiche le hostname et explique sa persistance Android. Le contrôle de relance VPN est indisponible dans cet état, sans écraser la préférence ni importer artificiellement un profil. Dès que le DNS privé est désactivé, le contrôle redevient disponible.

Le récepteur de démarrage préserve aussi un DNS privé strict configuré au niveau système, au lieu de le désactiver pour rétablir un ancien choix VPN. Le changement manuel de profil VPN conserve son parcours explicite habituel.

Les corrections réseau et catalogue sont détaillées dans 2026-10-25-ipv6-catalog.md. Le workflow vérifie tests/lint, signature de production et publie une prerelease avec le pointeur latest-beta.

Test Android TV API 31 : DoT externe sans profil enregistré, préférence conservée, puis retour à l’écran après désactivation système — réussi (24,6 s).

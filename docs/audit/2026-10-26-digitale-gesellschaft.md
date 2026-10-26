# Digitale Gesellschaft — ajout au catalogue

Source officielle consultée le 26 octobre 2026 : https://www.digitale-gesellschaft.ch/dns/

Trois presets Standard sans Adblock : DoH https://dns.digitale-gesellschaft.ch/dns-query, DoT dns.digitale-gesellschaft.ch:853, DoQ quic://dns.digitale-gesellschaft.ch:853. Aucun preset DNS classique : le fournisseur ne propose pas le port 53. Adresses IPv4/IPv6 résolues par le bootstrap existant, noms TLS conservés ; aucun pinning de certificat ajouté.

DoH est visible par défaut, DoT et DoQ suivent leurs réglages. IDs 1800–1802 réservés après vérification des identifiants existants ; la synchronisation ProfileManager ajoute les presets manquants aux installations existantes. Ordre Cloudflare, ControlD, dns.sb conservé.

Tests réseau hôte : DoH et DoT renvoient des réponses valides A et AAAA pour example.com, sans troncature et avec rcode 0. Ceci valide la résolution des enregistrements IPv6 ; cela ne constitue pas une mesure de connectivité IPv6 publique du réseau de l’utilisateur.

Le test DoQ initial a révélé une incompatibilité réelle dans PDM : Agent15 signalait « Missing RSASSA-PSS support ». Un second client (dnspython/aioquic) a confirmé le bon fonctionnement DoQ A/AAAA des deux IP officielles. Correction : initialiser PlatformMapping.Platform.Android au démarrage, avant les connexions QUIC ; dépendance Agent15 3.1 déclarée explicitement (version déjà apportée à l’exécution par Kwik). Cette configuration sélectionne les noms d’algorithmes RSA-PSS Android ; aucune modification des contrôles TLS ou des autorités de confiance. Le journal temporaire de diagnostic a été retiré.

Après correction : handshake DoQ validé. Premier essai intensif : un timeout en seconde reconnexion (SERVFAIL). Nouvelle exécution complète : 3 connexions à froid × 8 requêtes A/AAAA simultanées réussies, 8,035 s. Contrôle ControlD : même test réussi, 5,605 s. Ces tests ponctuels ne garantissent pas l’absence de délais réseau. Build debug/test, 30 tests JVM et lint réussis. Site : tests des 12 langues, catalogue et typecheck réussis ; modifications locales uniquement.

Publication : workflow 40314034782 réussi, v2.4.3-beta.1 et latest-beta concordants. APK SHA-256 50365b68800fe681231f47ff249d37e6150856025b1613ff1b5b41d75c1ca1e8 (identique pour les deux liens). Signature de production inchangée, certificat SHA-256 c7fd0c977b2c8911c32268e3fa15e9fe50b14dccfae18f7232b28a9faa858f6d. Installation de l’APK publié réussie sur Android TV API31, versionCode 20403 / versionName 2.4.3-beta.1. Dernière stable toujours v2.4.0. Site non déployé.

# R8/ProGuard désactivé volontairement (isMinifyEnabled = false dans
# build.gradle.kts) — projet open source, on privilégie la lisibilité du
# bytecode pour audit communautaire et reproductibilité des releases.
#
# Si tu réactives un jour isMinifyEnabled = true, ajoute au minimum :
#   -dontobfuscate
#   -dontoptimize
# pour ne shrink que le code mort sans renommer ni réordonner.

# Hex

Hex est l'assistant personnel de Rod. Il transforme un Nothing Phone (1) en appareil dédié : il écoute « Hey Hex », répond à voix haute avec Claude, se souvient de tout, et parle en points sur l'écran et avec les Glyph au dos.

- Cahier des charges : https://claude.ai/artifact/GejppNzC8gbgBbB9Emuiop
- Maquette de l'écran : https://claude.ai/artifact/GS9MVxPW4DWQBUELEdYJCW

## Guide d'installation

Compte environ une heure la première fois, dont la moitié pour créer les comptes.

### 1. Créer les comptes (sur l'ordinateur)

| Service | Ce qu'il faut récupérer | Où |
| --- | --- | --- |
| Claude (obligatoire) | Une clé API qui commence par `sk-ant-`. Ajoute quelques euros de crédit. | [platform.claude.com](https://platform.claude.com), menu API Keys |
| Google Cloud (facultatif, pour la voix réaliste) | Une clé API, avec l'API « Cloud Text-to-Speech » activée | [console.cloud.google.com](https://console.cloud.google.com) |

**« Hey Hex » :** rien à créer. Au premier démarrage, Hex télécharge un petit modèle vocal libre (Vosk, environ 40 Mo) et détecte ensuite son nom sans Internet.

**Google Cloud :** il demande une carte bancaire même pour l'offre gratuite. Pour sécuriser la clé, limite-la à l'API Text-to-Speech. Sans cette clé, Hex parle avec la voix d'Android, moins naturelle.

### 2. Préparer l'ordinateur

1. Installe les **Android platform-tools** (la commande `adb`) depuis [developer.android.com/tools/releases/platform-tools](https://developer.android.com/tools/releases/platform-tools).
   - Mac : `brew install android-platform-tools`
   - Windows : décompresse le zip, puis lance les commandes depuis ce dossier.
2. Télécharge ce projet, puis l'APK depuis l'onglet **Releases** du dépôt GitHub (fichier `hex.apk`). Pose `hex.apk` dans le dossier `tools`.
3. Dans `tools`, copie `hex-config.exemple.txt` en `hex-config.txt` et remplis tes clés.

### 3. Préparer le téléphone

Le mode kiosque exige un téléphone **sans aucun compte**. La remise à zéro efface tout ce qu'il contient.

1. Réglages > Système > Réinitialiser > Effacer toutes les données.
2. À l'assistant de démarrage : choisis le français, connecte le Wi-Fi, puis **passe l'étape du compte Google**.
3. Réglages > À propos du téléphone : tape 7 fois sur « Numéro de build » pour activer les options développeur.
4. Réglages > Système > Options pour les développeurs : active le **Débogage USB**.
5. Pour que « Hey Hex » et les minuteurs marchent sans Wi-Fi : ouvre l'appli Google > Paramètres > Voix > Reconnaissance vocale hors connexion, et télécharge le **français**.
6. Branche le téléphone à l'ordinateur et accepte la demande de débogage.

### 4. Installer Hex

Dans le dossier `tools` :

- Mac ou Linux : `./install.sh hex.apk`
- Windows : `install.bat hex.apk`

Le script installe Hex, le rend propriétaire du téléphone (mode kiosque), donne les permissions des Glyph et envoie tes clés. Hex se présente ensuite et lance la découverte : environ quinze minutes de discussion pour faire connaissance.

### Au quotidien

| Pour… | Fais… |
| --- | --- |
| Parler à Hex | « Hey Hex », un appui sur **volume bas**, ou un toucher sur l'écran |
| Lui couper la parole | Pareil, pendant qu'il parle |
| Écrire au lieu de parler | Glisse vers le haut |
| Ajouter un Wi-Fi | Appui long sur l'écran |
| Ouvrir les réglages cachés | 5 tapes rapides dans le coin en haut à gauche, puis ton code |
| **Revenir à Android, sans écran ni code** | **5 appuis rapides sur volume haut** |

Les réglages cachés permettent d'ajouter un Wi-Fi, de voir la dépense du mois, d'effacer ou de restaurer la mémoire, de voir et annuler les rappels, de couper la recherche web, ou de quitter le mode kiosque.

### Ce que Hex sait faire

- **Se souvenir** : après chaque conversation, il note ce qui a changé (faits sur toi, projets, décisions, journal). Il ne réécrit jamais toute sa mémoire, il ajoute ou corrige point par point. Une sauvegarde est faite chaque jour (14 jours gardés). Si Internet manque, la conversation est gardée et mémorisée plus tard.
- **Suivre tes projets** : il voit les échéances proches ou dépassées, et les projets dont tu ne lui as pas parlé depuis dix jours.
- **Rappels et minuteurs** : « rappelle-moi demain à 9 h d'appeler le comptable », « minuteur de 10 minutes ». Ils survivent à un redémarrage.
- **Running** : dis-lui « j'ai couru 8 km », il les compte tout de suite et te dit où tu en es (semaine du lundi au dimanche, ou mois).
- **Chercher sur le web** : actualité, horaires, résultats… (environ 1 centime par recherche, compté dans le budget, coupé automatiquement à 80 % du budget).
- **Réfléchir plus fort** : commence par « réfléchis bien » ou « prends le temps » et il passe sur un modèle plus puissant pour cette réponse.
- **Garder le fil** : si tu reviens dans les trois heures, il se souvient de ce que vous venez de dire.

### Sorties de secours

Hex ne doit jamais te bloquer. Il y a plusieurs façons de revenir à Android :

1. **5 appuis rapides sur volume haut** : le mode kiosque se coupe tout de suite et les réglages d'Android s'ouvrent.
2. Le menu caché (coin en haut à gauche, puis ton code) : « Quitter le mode kiosque », ou « Rendre le téléphone normal » pour retirer Hex complètement.
3. **Appui long sur Power** : le menu Éteindre / Redémarrer reste disponible.
4. Par câble : `adb shell am start -n com.rod.hex/.SetupActivity --es kiosque off`.
5. Tant que Hex n'a jamais eu Internet, ou s'il n'a pas de clé Claude, le mode kiosque ne s'active pas du tout.

Pour remettre le mode kiosque : menu caché, « Revenir en mode kiosque ».

### Mettre à jour

À chaque modification du code, GitHub fabrique un nouvel APK dans **Releases**. Pour l'installer : branche le téléphone et relance `adb install -r hex.apk`. La mémoire de Hex est conservée.

### En cas de souci

- **« Not allowed to set the device owner »** : il reste un compte sur le téléphone. Refais une remise à zéro et passe l'étape Google.
- **Les Glyph ne s'allument pas** : relance `adb shell settings put global nt_glyph_interface_debug_enable 1`. Hex relance ce mode tout seul toutes les 6 heures, mais seulement si l'étape 4 du script a réussi.
- **Hex ne réagit pas à « Hey Hex »** : au premier démarrage, il doit être en Wi-Fi pour télécharger son modèle. Le bouton volume bas marche dans tous les cas.

## Technique

- App Android en Kotlin, sans bibliothèque d'interface, pour Android 13 et plus (le Phone 1 est sous Android 15).
- Claude via le SDK Java officiel d'Anthropic (modèle par défaut : `claude-haiku-5-5`), avec un compteur qui bloque à 10 € par mois.
- Mot d'activation : Vosk (libre, sur le téléphone, modèle anglais small). Transcription : reconnaissance vocale d'Android, en français.
- Voix : Google Cloud Text-to-Speech (`fr-FR-Chirp3-HD-Charon`), avec la voix d'Android en secours.
- Glyph : SDK Glyph de Nothing, téléchargé par la CI et appelé par réflexion (Hex marche sans).
- Outils pendant la conversation : recherche web (outil serveur d'Anthropic), noter, course, rappel, minuteur, annuler.
- « Réfléchis bien » : `claude-sonnet-5-5` pour ce tour (réglable avec `--es deep <modèle>` envoyé à `SetupActivity`).
- Mémoire : `memory.json` dans le stockage privé de l'app (faits numérotés, projets, journal, archive mensuelle, running). Après chaque conversation, Claude renvoie une liste d'opérations appliquées par le code. Sauvegardes quotidiennes dans `memory-backups/`, conversations en attente dans `memory-pending.json`.
- Rappels : `reminders.json`, vérifiés toutes les 15 secondes.
- Météo : Open-Meteo, sans clé.

| Fichier | Rôle |
| --- | --- |
| `HexEngine.kt` | Chef d'orchestre : réveil, écoute, réflexion, parole, relance, minuteurs |
| `Brain.kt` | Personnalité, appels à Claude, outils, mise à jour de la mémoire |
| `Memory.kt` | Les carnets de Hex, sauvegardes, file d'attente |
| `Reminders.kt` | Rappels et minuteurs |
| `Voice.kt` | Voix réaliste et voix de secours, niveau sonore pour l'animation |
| `Listener.kt` / `WakeWord.kt` | Transcription et « Hey Hex » |
| `DotMatrixView.kt` / `DotFont.kt` | L'écran en points |
| `Glyphs.kt` | Les lumières au dos |
| `Kiosk.kt` | Mode kiosque (propriétaire de l'appareil) |
| `WifiPanel.kt` | Ajout d'un Wi-Fi |
| `tools/install.sh`, `tools/install.bat` | Installation en une commande |

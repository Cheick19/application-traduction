# Traduction Instantanée (Android)

Application Android de traduction de conversation en temps réel.

- Votre interlocuteur parle (anglais par défaut) → l'application affiche **et lit à voix haute** la traduction en français.
- Vous parlez en français → l'application traduit et lit la phrase dans la langue de votre interlocuteur
  (anglais, espagnol, allemand, italien, portugais, arabe, chinois, japonais, coréen, russe, turc, néerlandais, hindi).

## Installer sur votre téléphone

1. Sur votre téléphone, ouvrez la page **Releases** du dépôt GitHub (`cheick19/application-traduction` → *Releases* → *latest*).
2. Téléchargez `TraductionInstantanee.apk`.
3. Ouvrez le fichier. Android vous demandera d'autoriser l'installation d'« applications inconnues » pour votre navigateur : acceptez.
4. Lancez **Traduction Instantanée** et autorisez l'accès au micro.

Au premier lancement, l'application télécharge les langues choisies (environ 30 Mo par langue, une seule fois).
Ensuite la traduction fonctionne même **hors ligne**.

## Utilisation

| Bouton | Action |
| --- | --- |
| **Moi** (bleu) | Parlez dans votre langue, la traduction est lue dans la langue de l'interlocuteur |
| **Interlocuteur** (vert) | Il/elle parle, vous entendez la traduction dans votre langue |
| **Mode conversation automatique** | Le micro passe tout seul de l'un à l'autre après chaque traduction |
| ⇄ | Inverser les deux langues |
| Toucher une bulle / 🔊 | Réécouter la traduction |
| Partager (icône sur une bulle) | Envoyer la traduction en **message vocal** ou en texte via WhatsApp, SMS, e-mail… |
| ✏️ sur une bulle | Corriger une traduction : l'appli s'en souvient pour la suite |
| ＋ (en haut) | Nouvelle discussion (l'ancienne reste enregistrée) |
| 🕘 (en haut) | Discussions enregistrées : ouvrir, partager ou supprimer |
| Menu ⋮ | Partager la discussion, ce que l'appli a appris, ajouter un mot / nom, vitesse de la voix |

Appuyez une deuxième fois sur le micro actif pour terminer la phrase tout de suite.

## Rapidité

- La traduction est calculée **pendant que la personne parle** : elle est prête dès la fin de la phrase.
- Les petites pauses ne coupent plus la phrase : l'appli continue d'écouter et n'envoie qu'après une vraie pause
  (menu › *Fin de phrase* : 1,5 s, 3 s, ou **manuel** — envoi seulement quand vous touchez le micro).
- Touchez le micro actif (« ✓ Toucher pour envoyer ») pour envoyer immédiatement.
- Menu › *Reconnaissance vocale* : **Précise** (par défaut, Internet, meilleure avec du bruit) ou **Rapide** (hors ligne si
  installée : *Paramètres › Google › Reconnaissance vocale hors connexion*).
- Une voix est préparée à l'avance pour chaque langue ; la vitesse de la voix se règle dans le menu.

## Ce que l'appli apprend

Tout reste sur le téléphone :
- **Corrections** : corrigez une traduction avec ✏️ ; la même phrase sera ensuite traduite comme vous l'avez écrite.
- **Vocabulaire** : ajoutez des prénoms, lieux ou mots (menu › « Ajouter un mot / nom ») ; ils sont utilisés dans les
  traductions et aident la reconnaissance vocale (Android 13 et plus).

## Conseils

- La reconnaissance vocale utilise le service Google du téléphone. Si rien ne se passe, mettez à jour l'application **Google**.
- Si une voix n'est pas disponible : *Paramètres › Accessibilité › Synthèse vocale* (ou *Paramètres › Système › Langues*) et installez la voix de la langue concernée.

## Technique

Kotlin, Android 8.0+ (API 26). Reconnaissance vocale `SpeechRecognizer`, traduction hors ligne Google ML Kit,
synthèse vocale `TextToSpeech`. L'APK est construit automatiquement par GitHub Actions (`.github/workflows/build.yml`).

Construction locale : `./gradlew assembleRelease` (nécessite le SDK Android).

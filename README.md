# FMcall Serval — Application Android Mesh

> **"Communiquer partout, même sans Internet."**

Application de communication basée sur un réseau maillé (Mesh Network) permettant les appels vocaux, la messagerie et le partage de fichiers **sans Internet ni serveur central**.

---

## 🏗️ Architecture

```
FMcallServal/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/fmcall/serval/
│       │   ├── FMcallApplication.kt          # App Hilt
│       │   ├── data/
│       │   │   ├── FMcallDatabase.kt         # Room DB + DAOs
│       │   │   └── model/Models.kt           # Entités de données
│       │   ├── di/AppModule.kt               # Injection Hilt
│       │   ├── mesh/MeshNetworkManager.kt    # Moteur Mesh (Wi-Fi, BT)
│       │   ├── security/CryptoManager.kt     # Ed25519 + AES-256-GCM
│       │   ├── service/
│       │   │   ├── MeshService.kt            # Service foreground
│       │   │   └── VoiceCallService.kt       # Appels vocaux
│       │   ├── ui/
│       │   │   ├── main/MainActivity.kt      # Navigation principale
│       │   │   ├── calls/CallActivity.kt     # Écran d'appel
│       │   │   ├── messages/MessagesFragment # Chat + conversations
│       │   │   ├── nodes/NodesFragment       # Réseau Mesh
│       │   │   └── settings/SettingsFragment # Paramètres
│       │   └── utils/PreferencesManager.kt  # DataStore
│       └── res/
│           ├── layout/                       # Layouts XML
│           ├── navigation/nav_graph.xml      # Navigation
│           ├── values/                       # Couleurs, strings, thèmes
│           └── xml/file_paths.xml            # FileProvider
└── build.gradle
```

---

## 🔧 Installation avec Android Studio

### Prérequis
- Android Studio Hedgehog (2023.1.1) ou plus récent
- JDK 17
- Android SDK 34

### Étapes

1. **Créer un nouveau projet** dans Android Studio
   - `File → New → New Project → Empty Activity`
   - Package : `com.fmcall.serval`
   - Minimum SDK : API 26 (Android 8)

2. **Copier les fichiers** dans la structure du projet

3. **Synchroniser Gradle** :
   ```
   File → Sync Project with Gradle Files
   ```

4. **Ajouter les drawables manquants** (icônes vectorielles) :
   - `ic_home`, `ic_call`, `ic_message`, `ic_mesh_signal`
   - `ic_call_end`, `ic_mic_off`, `ic_speaker`, `ic_send`
   - `ic_attach`, `ic_arrow_back`, `ic_stop`
   - Tous disponibles dans Material Symbols

5. **Compiler** :
   ```
   Build → Generate Signed Bundle/APK
   ```

---

## 🌐 Technologies Mesh

| Transport     | Usage                    | Portée     |
|---------------|--------------------------|------------|
| Wi-Fi Direct  | Communication principale | ~200m      |
| Wi-Fi Mesh    | Réseau étendu            | ~100m/nœud |
| Bluetooth BLE | Fallback basse énergie   | ~50m       |
| Hotspot local | Pont/relais              | ~50m       |

### Protocole de routage
- **Découverte** : Beacons UDP broadcast toutes les 5 secondes
- **Routage** : Greedy multi-hop avec TTL=8 (max 8 sauts)
- **Déduplication** : Cache de 10 000 IDs de paquets
- **Chiffrement** : Ed25519 signatures + AES-256-GCM messages

---

## 🔒 Sécurité

| Couche         | Algorithme              |
|----------------|-------------------------|
| Identité nœud  | Ed25519 (clé publique)  |
| Messages       | AES-256-GCM             |
| Échange clés   | ECDH X25519             |
| Stockage clés  | Android Keystore        |
| Signatures     | Ed25519                 |

---

## 📱 Compatibilité

- Android 8.0 (API 26) → Android 15 (API 35)
- Téléphones & tablettes
- Mode portrait
- Appareils robustes (Rugged devices)

---

## 🚀 Fonctionnalités

- [x] Découverte automatique des nœuds
- [x] Routage multi-sauts
- [x] Appels vocaux chiffrés
- [x] Messagerie chiffrée E2E
- [x] Partage de fichiers
- [x] Service foreground persistant
- [x] Thème sombre #000000 / #00C853
- [x] Interface FR + EN
- [x] Carnet de contacts
- [x] Journal des appels

---

## 📦 Dépendances principales

```gradle
// Réseau & Mesh
implementation 'org.bouncycastle:bcprov-jdk15on:1.70'  // Crypto

// Audio
implementation 'org.webrtc:google-webrtc:1.0.32006'    // Voice

// Base de données
implementation 'androidx.room:room-runtime:2.6.1'

// Injection
implementation 'com.google.dagger:hilt-android:2.48'

// Sécurité
implementation 'androidx.security:security-crypto:1.1.0-alpha06'
```

---

*FMcall Serval — Open Mesh Protocol v1.0.0*

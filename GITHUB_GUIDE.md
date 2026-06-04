# 🚀 Comment obtenir l'APK FMcall Serval via GitHub

## Étapes (5 minutes)

### 1. Crée un compte GitHub gratuit
→ https://github.com/signup

### 2. Crée un nouveau dépôt
- Clique sur **"New repository"**
- Nom : `FMcallServal`
- Visibilité : **Public** (gratuit) ou Private
- Clique **"Create repository"**

### 3. Upload le projet
**Option A — Interface web (plus simple) :**
1. Sur la page du dépôt, clique **"uploading an existing file"**
2. Décompresse le ZIP `FMcallServal_Android_v2.zip`
3. Glisse-dépose **tout le contenu** du dossier `FMcallServal/`
4. Clique **"Commit changes"**

**Option B — Git en ligne de commande :**
```bash
cd FMcallServal
git init
git add .
git commit -m "FMcall Serval v1.0"
git remote add origin https://github.com/TON_USERNAME/FMcallServal.git
git push -u origin main
```

### 4. GitHub compile automatiquement
- Va dans l'onglet **"Actions"** de ton dépôt
- Tu verras le workflow **"Build FMcall Serval APK"** se lancer
- Attends ~5 minutes ⏳

### 5. Télécharge l'APK
**Méthode 1 — Artifacts :**
- Dans Actions → clique sur le workflow terminé ✅
- Télécharge **"FMcallServal-debug-apk"**

**Méthode 2 — Releases :**
- Va dans **"Releases"** sur la page principale du dépôt
- Télécharge **"FMcallServal-debug.apk"**

---

## 📱 Installer l'APK sur Android

1. Transfère l'APK sur ton téléphone (USB, email, Drive...)
2. Sur Android : **Paramètres → Sécurité → Sources inconnues** → Activer
   - Android 8+ : Paramètres → Applications → autoriser cette source
3. Ouvre le fichier APK et installe
4. Lance **FMcall Serval** ✅

---

## ❓ Problèmes fréquents

| Erreur | Solution |
|--------|----------|
| Build failed - missing SDK | GitHub Actions installe le SDK automatiquement |
| "Application non installée" | Désinstalle une version précédente d'abord |
| Permissions refusées | Accepte toutes les permissions au 1er lancement |
| Aucun nœud détecté | Assure-toi que 2 appareils ont l'app et sont proches |

---

*FMcall Serval — Open Mesh Protocol v1.0*

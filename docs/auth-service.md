# Auth Service — guide complet (jalon M5)

> Port **8087** · package `tn.finrisk.authservice` · **80 tests**
> Le service d'identité : login, JWT, gestion des comptes, trace `AUTH`.

## 1. Objectif et périmètre

`auth-service` répond à trois questions : *qui est-vous ?*, *avez-vous le
droit ?* (non — c'est le gateway qui l'applique sur les routes métier,
voir [api-gateway.md](api-gateway.md)), et *quel compte existe-t-il ?*.

| Responsabilité | Endpoint |
|---|---|
| Connexion (mot de passe → paire de tokens) | `POST /api/auth/login` |
| Rafraîchissement (refresh → nouvelle paire) | `POST /api/auth/refresh` |
| Profil de l'utilisateur connecté | `GET /api/auth/me` |
| Administration des comptes (ADMIN) | `GET/POST /api/users`, `GET/PATCH /api/users/{id}` |
| Réinitialisation d'un mot de passe oublié (ADMIN) | `POST /api/users/{id}/password` |

Ce qu'il **ne** fait pas : pas de table de sessions (tout est dans le JWT),
pas de `DELETE` dur sur les comptes (les FK `case_comments`/`audit_logs`
interdisent la suppression → désactivation `isActive=false`), pas de
détermination de droits sur les routes métier (matrice = gateway).

## 2. Démarrage

```powershell
cd backend\auth-service
    mvn test               # 80 tests (unitaires + WebMvc + intégration finrisk_test)
mvn spring-boot:run    # http://localhost:8087
```

Dépendance notable : **`spring-security-crypto` seul** (BCrypt) — pas de
`spring-boot-starter-security` : aucun filtre Spring Security ne s'arme
ici, le service reste un service REST classique. La gachette 401/403 du
projet vit au gateway ; le service se re-vérifie quand même sur `/me` et
sur `/api/users` (**défense en profondeur**, §6).

## 3. Comptes initiaux

Le compte `admin` des bases `finrisk` et `finrisk_demo` utilise
`FINRISK_BOOTSTRAP_ADMIN_PASSWORD` à la première initialisation. Le rôle
analyste se provisionne depuis la page Utilisateurs. Les mots de passe
applicatifs sont stockés sous forme de hash BCrypt.

Les identifiants du schéma de test sont réservés à `finrisk_test`.
Consulter la [configuration des secrets](deploiement-secrets-jwt.md).

## 4. API — exemples

### 4.1 Login

```powershell
Invoke-RestMethod -Uri http://localhost:8087/api/auth/login -Method Post `
  -ContentType 'application/json' `
  -Body (@{ username = "admin"; password = $env:FINRISK_BOOTSTRAP_ADMIN_PASSWORD } | ConvertTo-Json)
```

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9…",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9…",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": { "id": 1, "username": "admin", "email": "admin@finrisk.tn",
             "role": "ADMIN", "active": true }
}
```

Échecs :

| Cas | Code | Message |
|---|---|---|
| Utilisateur inconnu **ou** mot de passe faux | **401** | `Identifiants invalides` (message **unique** — on n'apprend jamais si le compte existe) |
| Compte désactivé (mot de passe correct) | **403** | `Compte desactive` |
| Corps incomplet | 400 | erreurs par champ |

### 4.2 Refresh et Me

```powershell
# refresh : renvoie une NOUVELLE paire (rotation), meme si le compte a bouge
Invoke-RestMethod -Uri http://localhost:8087/api/auth/refresh -Method Post `
  -ContentType 'application/json' -Body '{"refreshToken":"…"}'

# me : verifie l'access ENCORE ICI (pas besoin de faire confiance au gateway)
Invoke-RestMethod -Uri http://localhost:8087/api/auth/me `
  -Headers @{ Authorization = "Bearer $accessToken" }
```

Un `refreshToken` rejoué comme access est refusé (`Type de token incorrect`)
: les deux portent le claim `typ`.

### 4.3 Administration (rôle ADMIN exigé, vérifié 2×)

```powershell
# creation (201 + Location)
Invoke-RestMethod -Uri http://localhost:8087/api/users -Method Post `
  -Headers @{ Authorization = "Bearer $admin" } -ContentType 'application/json' `
  -Body '{"username":"fatou","email":"f@finrisk.tn","password":"motdepasse1","role":"ANALYST"}'

# modification partielle (au moins un champ : role | active | password)
Invoke-RestMethod -Uri http://localhost:8087/api/users/3 -Method Patch `
  -Headers @{ Authorization = "Bearer $admin" -ContentType='application/json' } `
  -Body '{"role":"MANAGER","active":true}'

# mot de passe perdu par l'utilisateur : le SERVEUR genere le mot de
# passe (14 car., 1 min + 1 maj + 1 chiffre + 1 symbole, alphabet sans
# 0/O ni 1/l/I) et le rend en clair UNE SEULE FOIS
(Invoke-RestMethod -Uri http://localhost:8087/api/users/3/password -Method Post `
  -Headers @{ Authorization = "Bearer $admin" }).password
```

Garde-fous (409) : username/email déjà pris ; **un admin ne peut ni se
désactiver ni changer son propre rôle** (anti-lockout : on ne verrouille
jamais la seule porte ADMIN).

**Réinitialisation vs `PATCH password`.** Les deux changent le mot de
passe, mais pas dans le même sens. `PATCH /api/users/{id}` avec
`password` **impose** le mot de passe : c'est l'admin qui choisit, et il
n'y a rien à afficher. `POST /api/users/{id}/password` **génère** le
mot de passe : c'est le cas « l'analyste n'a plus son mot de passe, il
vient voir l'admin ». Le clair retourné existe alors **qu'une fois** —
il n'est ni stocké (la table `users` ne garde que le hash BCrypt) ni
journalisé (`audit_logs` ne reçoit que `mot de passe reinitialise par
l'admin`). Console : bouton **RÉINITIALISER** dans la colonne Actions de
`/users` (2e clic pour confirmer), le mot de passe s'affiche ensuite
dans un bandeau avec un bouton COPIER.

Garde-fous : 403 pour un non-ADMIN (contrôle fait **dans** le service,
donc aussi appelé hors gateway), 404 si l'utilisateur n'existe pas, et
deux réinitialisations successives donnent deux mots de passe
différents.

## 5. Le JWT (fait main, zéro dépendance)

`security/JwtSupport` — HMAC-SHA256 en JDK pur (`javax.crypto.Mac`) :

```
base64url(header) "." base64url(payload) "." base64url(HMAC-SHA256(header.payload))
```

| Claim | Contenu |
|---|---|
| `sub` | `userId` (injecté ensuite en `X-User-Id` par le gateway) |
| `usr` | username |
| `rol` | `ADMIN` / `ANALYST` / `MANAGER` |
| `typ` | `access` (15 min) ou `refresh` (7 j) |
| `iat` / `exp` | epoch secondes |
| `iss` | `finrisk360` |
| `jti` | uuid |

Défenses vérifiées à la lecture : algorithme **imposé HS256** (rejet de
`alg: none` et des confusions de schémas), comparaison de signature
**constante** (`MessageDigest.isEqual`, pas `==`), `exp` et `iss`.
Le même fichier, **copie conforme**, existe dans `api-gateway` : c'est le
contrat partagé (même secret `auth.jwt.secret` des deux côtés — en
production, variable d'environnement `AUTH_JWT_SECRET` uniquement).

## 6. Double verrou (le point de la soutenance M5)

```
client ──▶ gateway :8080 ── verifie token + matrice ──▶ service :808X
                 │ 401 / 403                              │
                 └───────────── et SUR TOUT : le service lui-meme
                               re-verifie sur /me et /api/users
```

Appel **direct** à `:8087` en contournant le gateway, avec un token
`ANALYST` parfaitement valide : `GET /api/users` → **403** (`Acces reserve
aux administrateurs`). Le test d'intégration `doubleGarde_directSur8087`
le couvre.

## 7. Trace `AUTH` (audit_logs)

Le service est le **deuxième écrivain** de `audit_logs` (après
alert-case-service — un écrivain par domaine, règle M0 §2) :

| Action | Quand | Détail |
|---|---|---|
| `LOGIN_SUCCESS` | connexion acceptée | `newValue = role=…`, IP = `X-Forwarded-For` |
| `LOGIN_FAILED` | mot de passe faux / compte inconnu / désactivé | message non sensible |
| `USER_CREATED` | `POST /api/users` | `newValue = username=…, role=…` |
| `USER_UPDATED` | `PATCH /api/users/{id}` | `oldValue` → `newValue` (jamais le mot de passe) |
| `USER_PASSWORD_RESET` | `POST /api/users/{id}/password` | `newValue = mot de passe reinitialise par l'admin` — **jamais le mot de passe** |

**Astuce `REQUIRES_NEW`** : `login()` lève le 401 *dans* sa transaction →
rollback général → une trace écrite en `MANDATORY` partirait avec. Les
traces de **refus** passent donc par `AuditService.recordRefus`
(`REQUIRES_NEW`) : le refus est annulé, la preuve de la tentative, non.
Les traces de **succès** restent en `MANDATORY` (atomicité avec la
mutation qu'elles documentent).

Lecture : `GET /api/audit` sur **alert-case-service** (owner de la
lecture), ex. via le gateway avec un rôle MANAGER/ADMIN.

## 8. Codes d'erreur renvoyés

| Code | Signification |
|---|---|
| 401 | token absent/malforme/forgé/expiré, identifiants faux |
| 403 | token valide mais rôle insuffisant **ou** compte désactivé |
| 404 | `GET /api/users/{id}` inconnu |
| 409 | unicité violée, auto-destruction refusée, contrainte SQL |
| 400 | validation Bean Validation, PATCH sans champ, JSON illisible |

La forme est celle du projet : `{"status", "error", "message", "path",
"errors":[{field,message}]}`.

## 9. Tests (69)

| Classe | Nb | Ce qu'elle prouve |
|---|---|---|
| `JwtSupportTest` | 6 | round-trip, payload modifié → signature invalide, expiré, `alg:none`, mauvais émetteur, malformé |
| `TokenServiceTest` | 4 | claims du contrat, refresh≠access, expiration |
| `AuthServiceTest` | 11 | login 200/401/403 + traces, refresh, me |
| `UserAdminServiceTest` | 16 | garde ADMIN (403 même en lecture), CRUD, doublons 409, anti-lockout, aucun hash dans les réponses |
| `AuthApiTest` (WebMvc) | 15 | codes HTTP + forme des corps (401/403/404/409/400, Location) |
| `AuthIntegrationTest` (finrisk_test) | 16 | boucle réelle BCrypt+JWT, double garde, désactivation → 403, traces en base, hashs uniques |
| `AuthServiceApplicationTests` | 1 | contexte + `ddl-auto: validate` |

## 10. Limites assumées (à dire en soutenance)

- **Refresh stateless** : pas de table de révocation — un refresh volé vit
  7 jours ; la révocation serveur (liste noire `jti`) est l'évolution
  naturelle, hors périmètre M5.
- Pas de verrouillage de compte après N échecs (le rate limiting est une
  extension citée en §9 de l'architecture).
- Secret de démo en dur dans le yml avec fallback `${AUTH_JWT_SECRET:…}` —
  en production, **uniquement** la variable d'environnement.

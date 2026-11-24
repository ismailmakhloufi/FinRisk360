# API Gateway — guide complet (jalon M5)

> Port **8080** · package `tn.finrisk.gateway` · **22 tests**
> Point d'entrée unique : vérifie le JWT, applique la matrice RBAC,
> proxye vers les services avec une identité de **confiance**.

## 1. Objectif — le critère M5

> *401 sans token, 403 si rôle insuffisant.*

Toute requête ` /api/** ` passe d'abord par `GatewayAuthFilter`. Les
routes ne sont **jamais** atteintes sans contrôle : même une URL inconnue
répond 404 **avant** d'avoir touché un backend.

Choix d'architecture : **Spring MVC classique** (pas Spring Cloud Gateway
ni WebFlux) — même stack que le reste du projet, proxy lisible ligne à
ligne, zéro dépendance supplémentaire (ni JPA, ni security starter : le
HMAC vient du JDK).

## 2. Démarrage

```powershell
cd backend\api-gateway
mvn test               # 22 tests
mvn spring-boot:run    # http://localhost:8080  (les backends derrière doivent tourner)
```

Les URLs des backends vivent toutes dans `gateway.upstreams`
(`application.yml`) — une seule occurrence par service.

## 3. La matrice des routes (RBAC **matriciel**, §18)

Aucune hiérarchie de rôles : chaque route porte ses propres listes.
`["*"]` = *tout utilisateur authentifié*. Lecture = `GET/HEAD/OPTIONS`,
écriture = tout le reste.

| Chemin | Service | Lecture | Écriture |
|---|---|---|---|
| `/api/customers/**` | customer (8081) | `*` | MANAGER, ADMIN |
| `/api/accounts/**` | account (8082) | `*` | MANAGER, ADMIN |
| `/api/transactions/**` | transaction (8083) | `*` | ANALYST, ADMIN |
| `/api/evaluations/**` | risk (8084) | `*` | ANALYST, ADMIN |
| `/api/rules/**` | risk (8084) | `*` | **ADMIN** |
| `/api/alerts/**` | alert-case (8085) | `*` | ANALYST, ADMIN |
| `/api/cases/**` | alert-case (8085) | `*` | ANALYST, ADMIN |
| `/api/audit/**` | alert-case (8085) | **MANAGER, ADMIN** | ADMIN |
| `/api/users/**` | auth (8087) | ADMIN | ADMIN |
| `/api/auth/**` | auth (8087) | *publique* | *publique* |

Lectures croisées typiques de la soutenance :
- ANALYST → `GET /api/audit` : **403** (les traces ne sont pas pour
  tout le monde), mais MANAGER → **200** ;
- ANALYST → `POST /api/rules` : **403** (écriture ADMIN) ;
- MANAGER → `POST /api/transactions` : **403** (écriture ANALYST/ADMIN) —
  le rôle « le plus haut » n'existe pas, c'est bien une **matrice**.

`/api/auth/**` est en `public-paths` : le gateway laisse passer, le
`auth-service` se protège lui-même (login public, `/me` et `/refresh`
vérifient leur token — défense en profondeur).

## 4. Cycle d'une requête (GatewayAuthFilter)

```
1. /actuator/** ou /error      → passe (local, non proxyé)
2. aucune route ne matche       → 404 JSON « Route inconnue : … »
3. wrapper anti-forge          → X-User-Id / X-User-Role / X-Forwarded-For
                                  du CLIENT sont SUPPRIMÉS
4. chemin dans public-paths    → passe (sans identité)
5. Authorization absent/mauvais→ 401 (message précis : manquant,
                                  format, malforme, signature, expiré…)
6. matrice lecture/écriture    → 403 « Rôle insuffisant : MANAGER ou ADMIN
                                  requis (votre rôle : ANALYST) »
7. injection d'identité        → attributs finrisk.gateway.userId/role
8. ProxyController             → envoi vers la cible + headers de confiance
```

**401 vs 403** : 401 = *je ne sais pas qui vous êtes* (token absent,
forgé, expiré). 403 = *je sais qui vous êtes et vous n'avez pas le droit*
(token valide, rôle absent de la matrice) ou compte désactivé côté auth.

## 5. Anti-spoofing (le piège classique)

Sans précaution, un client enverrait `X-User-Id: 1` à la main et
usurperait l'admin sur les services cibles (alert-case lit ce header pour
l'auteur des commentaires). Deux filets :

- `TrustedHeadersWrapper` (filtre) **retire** du côté client les headers
  `X-User-Id`, `X-User-Role`, `X-Forwarded-For` — ce qui sort du wrapper
  ne peut venir que du gateway ;
- `ProxyController` **reinjecte** `X-User-Id`/`X-User-Role` depuis les
  attributs posés *après* vérification du token, et
  `X-Forwarded-For` depuis l'adresse TCP réelle.

Le test `antispoofing_lesHeadersClientSontEcrases` envoie les trois
headers falsifiés et vérifie que le backend reçoit bien les valeurs du
token.

## 6. Le proxy (ProxyController)

Conservé : méthode, chemin, query string, corps, headers utiles
(y compris `Authorization` — les services peuvent aussi le vérifier).
Retiré : headers *hop-by-hop* (`Connection`, `Transfer-Encoding`,
`Content-Length`… — RestClient les refabrique).
Ajouté : les trois headers de confiance ci-dessus.

Distinction des erreurs :

| Situation | Réponse |
|---|---|
| Le service cible renvoie 4xx/5xx **métier** | proxyé **tel quel** (c'est lui qui a raison sur sa métier) |
| La connexion échoue (service arrêté) | **503** `Service cible indisponible : http://localhost:808X` |
| Chemin sans route | **404** émis par le gateway (jamais transmis) |
| Bug du gateway | 500 JSON, jamais de stacktrace |

## 7. JWT : copie conforme assumée

`security/JwtSupport` est la copie de celui d'auth-service (même secret,
même issuer, même code : sign utile pour les tests du gateway). Sans
module commun partagé (monorepo de comms hors périmètre), le contrat vit
en deux exemplaires — les deux se mettent à jour ensemble, c'est
documenté dans les deux guides. Voir [auth-service.md §5](auth-service.md)
pour les claims et les défenses (`alg:HS256` imposé, comparaison
constante, `exp`, `iss`).

## 8. Tests (22)

| Classe | Nb | Ce qu'elle prouve |
|---|---|---|
| `GatewayJwtSupportTest` | 5 | la copie se comporte comme l'original (forgé, expiré, `alg:none`, secret différent) |
| `GatewayApiTest` | 16 | **le jalon** : 401 sans token / format / forge / expiré ; 403 matrice (`/api/audit`, `/api/rules`) ; passthrough avec identité ; anti-spoofing ; query + méthode conservés ; route publique sans token ; 404 route ; 503 cible morte (après contrôle du token) ; actuator local |
| `GatewayApplicationTests` | 1 | contexte + chargement des routes |

Le sous-serveur de test est un **`com.sun.net.httpserver.HttpServer`**
du JDK (zéro dépendance) : il rejoue le backend, enregistre méthode,
chemin, query et headers reçus — les assertions regardent *exactement* ce
que le proxy a envoyé. Une route pointe vers `http://localhost:9` pour le
503, via `@DynamicPropertySource` sur `gateway.upstreams.*` (cles
scalars : l'override d'index de liste `- [0]` reste fragile côté Binder
Spring, les upstreams nommés tournent partout).

## 9. Limites assumées (à dire en soutenance)

- Pas de rate limiting / circuit breaker (extensions M0 §9 : Redis,
  Resilience4j).
- Pas de TLS au gateway (démo en local) — en production, le gateway
  termine le HTTPS.
- Lecture seule de la matrice : elle est en `application.yml`, pas en
  base — changer un droit = redémarrer (choix M0 « routes statiques »).
- SOAP `/ws` n'est **pas** routé : les clients appellent le
  transaction-service directement (legacy hors chaîne M5).

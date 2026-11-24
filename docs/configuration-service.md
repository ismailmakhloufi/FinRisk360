# Configuration du `customer-service`

> Petit guide pour comprendre **où** se configure un service Spring Boot, **comment** Spring décide de la valeur d'une option, et **à quoi sert** chaque bloc de notre `application.yml`.

---

## 1. Où vit la configuration ?

| Fichier | Rôle |
|---|---|
| `pom.xml` | les **bibliothèques** et leurs versions (ce qu'on *inclut*) |
| `src/main/resources/application.yml` | le **comportement** du service (port, base, logs…) |
| `src/main/resources/application-<profil>.yml` | variante par environnement (`dev`, `prod`, `test`) — à venir |
| Variable d'environnement | valeur **externe**, permet de changer sans toucher au code ni au git |

Notre service : `backend/customer-service/src/main/resources/application.yml`.

---

## 2. L'ordre de priorité de Spring

Spring cherche la valeur d'une option **du plus prioritaire au moins prioritaire** :

```
1. ligne de commande      --server.port=9090
2. variable d'environ.    SERVER_PORT=9090   (relie à spring.server.port)
3. application-<profil>.yml
4. application.yml        ← notre fichier
5. valeur par défaut de Spring
```

**Conséquence pratique** : on peut tout changer **sans rouvrir le code**. Le mot de passe en est l'exemple type :

```yaml
password: ${POSTGRES_PASSWORD}
```
La variable `POSTGRES_PASSWORD` est obligatoire au démarrage. Elle est fournie par l'environnement.

---

## 3. Bloc par bloc

### `server`
```yaml
server:
  port: 8081
```
Port d'écoute HTTP. **8081 = plan de ports de `docs/architecture-services.md`** (Gateway 8080, Transaction 8082…). Si le port est pris → `Port 8081 was already in use`.

### `spring.application`
```yaml
spring:
  application:
    name: customer-service
```
Identité du service : visible dans Actuator, dans les logs, et utilisée par le Gateway pour identifier l'appelant.

### `spring.datasource` — la connexion à PostgreSQL
```yaml
  datasource:
    url: jdbc:postgresql://localhost:5432/finrisk
    username: finrisk
    password: ${POSTGRES_PASSWORD}
```
| Élément | Sens |
|---|---|
| `jdbc:postgresql://` | protocole + **driver** |
| `localhost:5432` | hôte et port du conteneur Docker `finrisk-postgres` |
| `finrisk` | **nom de la base** (pas le rôle !) |
| `username` / `password` | rôle applicatif `finrisk` créé par `01-init.sql` |

À l'immobilisation, Spring crée un **pool Hikari** : au lieu d'ouvrir/fermer une connexion à chaque requête, il garde N connexions prêtes → `HikariPool-1 - Start completed` dans les logs.

### `spring.jpa` — l'ORM
```yaml
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties.hibernate.jdbc.time_zone: UTC
```

**`ddl-auto` est l'option la plus importante du projet.** Elle dit à Hibernate ce qu'il a le droit de faire avec le **schéma** de la base :

| Valeur | Hibernate… | Verdict |
|---|---|---|
| **`validate`** ✅ | **vérifie** que chaque `@Entity` correspond à une table réelle, **ne modifie rien** | la base est **verrouillée** : le SQL reste la vérité |
| `update` | **ajoute** colonnes/tables manquantes | interdit ici : le schéma changerait tout seul et silencieusement |
| `create` | **crée** les tables puis les remplit | usage démo uniquement |
| `create-drop` | crée au démarrage, **supprime à l'arrêt** | tests uniquement |
| `none` | ne fait rien | pas de vérification du tout |

> Avec `validate`, un simple renommage de colonne côté Java → **le service refuse de démarrer** avec une erreur explicite. C'est notre filet de sécurité entre `02-schema.sql` et le Java.

- `open-in-view: false` → une seule connexion/transaction par requête HTTP, pas de Session JPA ouverte pendant le rendu du JSON (évite les fuites de performance).
- `jdbc.time_zone: UTC` → tout est stocké/lut en UTC (conventions M0).

### `spring.jackson` — la sérialisation JSON
```yaml
  jackson:
    time-zone: UTC
```
Les `OffsetDateTime` sortent en **ISO-8601** (`2026-09-27T10:15:30Z`) — c'est le format que le front Angular consommera.
> Note Boot 4 : l'ancienne option `write-dates-as-timestamps: false` a **disparu** (Jackson 3 : ISO par défaut) — c'était l'erreur de démarrage qu'on a corrigée.

### `management` — Actuator
```yaml
management:
  endpoints.web.exposure.include: health,info
  endpoint.health.show-details: always
```
Expose **uniquement** `/actuator/health` et `/actuator/info` (on n'expose jamais `env`, `beans`, `shutdown` en production). `show-details: always` détaille les composants : c'est comme ça qu'on voit `"db":{"database":"PostgreSQL","status":"UP"}`.

### `springdoc` — Swagger
```yaml
springdoc:
  swagger-ui.path: /swagger-ui.html
```
Génère automatiquement, à partir des `@RestController` :
- `/swagger-ui.html` → **interface web** pour explorer et tester les API ;
- `/v3/api-docs` → le fichier **OpenAPI 3.1** (documentable, partageable avec le front).

### `logging` — les logs
```yaml
logging:
  level:
    tn.finrisk: INFO                 # notre code : à partir de INFO
    org.hibernate.SQL: DEBUG          # chaque SQL exécuté est affiché
```
En développement, on veut **voir le SQL** produit par JPA : c'est le moyen le plus rapide de comprendre ce que Hibernate fait réellement. En production : `org.hibernate.SQL: WARN`.

---

## 4. Vérifier la configuration

```powershell
curl.exe -s --noproxy "*" http://127.0.0.1:8081/actuator/health
curl.exe -s --noproxy "*" http://127.0.0.1:8081/v3/api-docs
```

| Symptôme | Cause probable |
|---|---|
| `Port 8081 was already in use` | un ancien `java -jar` tourne encore → le tuer |
| `Failed to configure a DataSource ... url attribute is not specified` | bloc `datasource` absent ou mal indenté |
| `password authentication failed` | mauvais rôle (`finrisk`) ou base (`finrisk`) |
| `Schema-validation: missing table [...]` | `ddl-auto: validate` : une `@Entity` pointe vers une table inexistante → **c'est voulu**, corriger l'entité ou le SQL |
| `Unable to access jarfile C:\...\projet` | chemin avec espace non protégé par guillemets |

---

## 5. Ce qui vient plus tard (M5 et +)

- `application-dev.yml` / `application-prod.yml` + `--spring.profiles.active=prod`
- secrets en variables d'environnement (jamais dans le git)
- exposition Actuator restreinte + health check vérifié par Docker (`healthcheck` du conteneur)

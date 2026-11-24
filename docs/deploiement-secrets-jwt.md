# Configuration de sécurité de FinRisk360

Injecter les secrets dans l'environnement des processus. Pour Docker Compose, ils
peuvent aussi être fournis par un fichier `.env` local protégé et ignoré par Git.
Les processus Java et Flask lancés depuis PowerShell ont besoin de leurs variables
dans leur propre environnement.

| Variable | Processus | Usage |
| --- | --- | --- |
| AUTH_JWT_SECRET | auth-service et api-gateway | Même clé aléatoire d'au moins 32 octets dans les deux services |
| POSTGRES_ADMIN_PASSWORD | Docker Compose | Mot de passe du rôle PostgreSQL postgres |
| POSTGRES_PASSWORD | Docker Compose et services Spring métier | Mot de passe distinct du rôle PostgreSQL finrisk |
| AI_DB_PASSWORD | Docker Compose et ai-assistant-service | Mot de passe distinct du rôle PostgreSQL finrisk_ro |
| FINRISK_BOOTSTRAP_ADMIN_PASSWORD | Docker Compose et migration 19 | Mot de passe du compte admin FinRisk360, choisir au moins 16 caractères et au plus 72 octets |
| ATLAS_SECRET_KEY | Simulateur Flask | Clé aléatoire indépendante d'au moins 32 octets |
| ATLAS_ADMIN_PASSWORD | Simulateur Flask | Mot de passe local d'au moins 12 caractères pour le premier démarrage et la migration du compte historique |
| FINRISK_USER et FINRISK_PASSWORD | Simulateur Flask | Identifiants du compte dédié BANK_SIMULATOR |
| ATLAS_COOKIE_SECURE | Simulateur Flask | Définir à 1 lorsque le navigateur accède au simulateur via HTTPS |

Générer les clés avec un générateur cryptographique. Distribuer les valeurs
via un gestionnaire de secrets. Les profils Java hors test rejettent la clé JWT
historique. La rotation JWT invalide les jetons existants et impose une nouvelle
connexion. La console Angular demande aussi une nouvelle connexion après rechargement.

## Base existante

Docker Compose applique les scripts d'initialisation uniquement sur un volume
vierge. Pour un volume existant, sauvegarder la base et procéder pendant une
fenêtre de maintenance. Conserver le volume, puis appliquer les étapes suivantes.

1. Préparer les secrets et recréer les conteneurs via `docker compose up -d`.
   Cela applique les nouvelles restrictions sur les ports. Changer les mots de
   passe des rôles `postgres` et `finrisk` dans PostgreSQL via `\password`
   dans une session psql administrateur, puis injecter les mêmes nouvelles valeurs
   dans les processus concernés. Une variable Docker seule ne modifie pas un mot
   de passe enregistré dans un volume existant.
2. Appliquer les scripts `10-add-ro-user.sql`, `18-bank-simulator-role.sql`
   `19-rotate-demo-users.sql` et `20-night-agent-completion-proof.sql` dans `finrisk` et, si elle existe,
   `finrisk_demo`. Les deux scripts qui lisent des secrets doivent être exécutés
   avec `AI_DB_PASSWORD` et `FINRISK_BOOTSTRAP_ADMIN_PASSWORD` présents dans
   l'environnement de psql. Exemple dans le conteneur après recréation :

   ~~~powershell
   docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d finrisk -f /opt/finrisk/sql/10-add-ro-user.sql -f /opt/finrisk/sql/17-night-agent-validation-intents.sql -f /opt/finrisk/sql/18-bank-simulator-role.sql -f /opt/finrisk/sql/19-rotate-demo-users.sql -f /opt/finrisk/sql/20-night-agent-completion-proof.sql
   ~~~

   Le script 19 remplace le mot de passe du compte admin issu du seed et désactive
   le compte analyste si son hash correspond encore au mot de passe public du seed.
   Les identifiants de test restent réservés à `finrisk_test`.
3. Recompiler et redémarrer les services Java. Se connecter en admin dans la
   console FinRisk360, créer un utilisateur avec le rôle `BANK_SIMULATOR` depuis
   la page Utilisateurs et récupérer son mot de passe généré. Fournir ces
   identifiants à Flask, puis démarrer le simulateur.

Les services Spring et PostgreSQL écoutent sur la boucle locale par défaut.
Pour un déploiement réparti, placer les backends sur un réseau privé et limiter
leur accès à la passerelle et aux services autorisés avant de modifier
`SERVER_BIND_ADDRESS`. Terminer TLS au point d'entrée exposé.


## Agent nocturne

Appliquer la migration 17 avant la migration 20 sur toute base qui ne dispose
pas encore des intentions nocturnes. Après vérification des tests de reprise,
fournir NIGHT_AGENT_AUTO_VALIDATION_ENABLED=true au service alert-case.
La fenêtre utilise Africa/Tunis et comprend 17 h 00, avec arrêt à 8 h 00.
Le rapport reste accessible hors de cette fenêtre. Les intentions inachevées
sont reprises aux balayages suivants pendant la fenêtre.
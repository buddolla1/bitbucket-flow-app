# Bitbucket Flow App

Standalone Spring Boot and React application for syncing Bitbucket pull request data into MySQL.

## Requirements

- Java 25
- Node.js 20+
- MySQL 8+

## MySQL

The app creates the `bitbucket_flow` database automatically when the configured MySQL user has permission.

```bash
export MYSQL_URL='jdbc:mysql://localhost:3306/bitbucket_flow?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
export MYSQL_USERNAME='root'
export MYSQL_PASSWORD=''
```

The schema is initialized from `backend/src/main/resources/schema.sql` on startup.

## Bitbucket

```bash
export BITBUCKET_BASE_URL='https://bitbucket.example.com'
export BITBUCKET_USERNAME='your.username'
export BITBUCKET_API_TOKEN='your-token'
```

Optional tuning:

```bash
export BITBUCKET_PAGE_SIZE=100
export BITBUCKET_SYNC_CONCURRENCY=5
export BITBUCKET_CATALOG_TTL_HOURS=24
export BITBUCKET_PARTICIPANT_BATCH_SIZE=25
```

## Run

Backend:

```bash
./gradlew bootRun
```

Frontend:

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5174`. The Vite dev server proxies `/api` to the Spring Boot backend on `http://localhost:8081`.

For a single packaged app:

```bash
./gradlew build
java -jar build/libs/bitbucket-flow-app-0.0.1-SNAPSHOT.jar
```

## Workflow

1. Create an application project.
2. Add one or more SSOs to the project.
3. Refresh the Bitbucket repository catalog.
4. Select SSOs, choose an optional date range, and sync pull requests.


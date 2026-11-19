# Contact Manager — Simple CRUD App

A simple GUI-based CRUD (Create, Read, Update, Delete) web app built with **Flask**, using **PostgreSQL** (or SQLite as a fallback), packaged with **Docker**.

## Features
- ➕ Create contacts (name, email, phone)
- 📋 List & search contacts
- ✏️ Edit contacts
- 🗑️ Delete contacts (with confirmation)
- 🐘 PostgreSQL support via environment variables (SQLite fallback when no DB is configured)

## Database configuration
Set these environment variables (see `.env.example`):

| Variable | Example | Notes |
|---|---|---|
| `DB_HOST` | `crud-app-postgresql.env-fpemontcg1` | Postgres host |
| `DB_PORT` | `5432` | |
| `DB_NAME` | `postgres` | |
| `DB_USER` | `postgres` | |
| `DB_PASSWORD` | — | keep it secret, never commit it |
| `DATABASE_URL` | `postgresql://user:pass@host:5432/db` | optional; overrides the `DB_*` values |
| `DB_PATH` | `/data/app.db` | used only if no Postgres is configured (SQLite) |

The `items` table is created automatically on startup. Check `/health` to see which DB is in use.

## Run with Docker Compose (recommended)
```bash
cp .env.example .env   # then fill in DB_PASSWORD etc.
docker compose up -d --build
```
Open http://localhost:5000

Stop:
```bash
docker compose down
```

## Run with plain Docker
```bash
docker build -t crud-app .
docker run -d -p 5000:5000 --env-file .env --name crud-app crud-app
```

## Run locally (without Docker)
```bash
pip install -r requirements.txt
DB_HOST=localhost DB_PASSWORD=secret python app.py   # or DB_PATH=./app.db for SQLite
```

## Project structure
```
.
├── app.py              # Flask app + routes
├── templates/          # HTML (Jinja2) GUI
├── requirements.txt
├── Dockerfile
└── docker-compose.yml
```

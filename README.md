# Contact Manager — Simple CRUD App

A simple GUI-based CRUD (Create, Read, Update, Delete) web app built with **Flask** + **SQLite**, packaged with **Docker**.

## Features
- ➕ Create contacts (name, email, phone)
- 📋 List & search contacts
- ✏️ Edit contacts
- 🗑️ Delete contacts (with confirmation)
- 💾 Data persisted in a Docker volume

## Run with Docker Compose (recommended)
```bash
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
docker run -d -p 5000:5000 -v crud-data:/data --name crud-app crud-app
```

## Run locally (without Docker)
```bash
pip install -r requirements.txt
DB_PATH=./app.db python app.py
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

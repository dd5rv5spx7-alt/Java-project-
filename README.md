# Agro-CTI – Threat Intelligence Framework for Precision Agriculture Cyber-Physical Systems

A Java web application that detects cyber attacks on smart-farm sensors and irrigation valves
and lets the farm security team manage farm nodes, threat indicators, incidents, response
actions and user accounts in **MySQL through JDBC**. It runs in the browser.

**Course:** CS213 Advanced JAVA Programming, SRM University–AP, Semester III, 2026–27
**Guide:** Mr. S. Gavaskar, Assistant Professor, Department of CSE

| Team member | Register number | Module |
|---|---|---|
| M. Sai Chandogya (team leader) | AP25110090046 | 1 – Farm node registry |
| N. Sri Varalaxmi | AP25110090062 | 2 – Threat indicators |
| M. Mohana Manogna | AP25110090170 | 3 – Incident management |
| M. Mukesh Vasudhar | AP25110090048 | 4 – Response actions |
| Sarwan | AP25110090060 | 5 – User accounts and login |

---

## Repository layout

```
AgroCTI_AP25110090046/
├── Dataset/            simulated smart-farm dataset (CSV + threat feed JSON)
├── Implementation/
│   └── AgroCTI/        Java source code, web pages, SQL scripts, MySQL JDBC driver
├── Documentation/      setup and user guide (PDF), README, database schema
├── Report/             project report (.docx and .pdf)
├── PPT/                presentation (.pptx and .pdf)
└── README.md           this file
```

Inside `Implementation/AgroCTI`:

```
src/com/agrocti/
  Main.java        entry point (commands: demo, serve, evaluate, selftest, generate)
  model/           Record<K> (abstract) and FarmNode, ThreatIndicator, Alert, ResponseAction, UserAccount
  module/          AgroCTI_FarmNode, AgroCTI_ThreatIndicator, AgroCTI_Incident,
                   AgroCTI_ResponseAction, AgroCTI_User  (CRUD modules), CrudModule, Form
  db/              Database (MySQL), Schema, Dao<T,K>, CrudRepository<T,K>, RunDao
  server/          DashboardServer (HTTP + JSON API), SessionManager, LiveSimulator
  cti/ data/ ml/ eval/ export/ exception/ util/ config/ test/
web/               login.html, index.html (live monitor), records.html (CRUD), *.js, style.css
sql/               setup.sql (run once as root), schema.sql (table definitions)
lib/               mysql-connector-j-9.1.0.jar
firmware/          ESP32 C version of the TinyML detector + PC test
db.properties      MySQL URL, user and password
run.sh / run.bat   compile and run
```

## Features

- **Login with roles** – ADMIN (everything), ANALYST (all records except users), FARMER (view only).
  Passwords stored as salted PBKDF2 hashes, HttpOnly session cookie, lock after 5 wrong passwords.
- **Five CRUD modules** – create, retrieve (search + sort by any column), update and delete, with
  validation in the browser and in Java, business rules and a delete confirmation.
  Methods follow the course convention, e.g. `AP25110090046_farmnode_create`, `_retrive`, `_update`, `_delete`.
- **MySQL through JDBC** – 6 InnoDB tables with primary/foreign keys, UNIQUE and CHECK constraints,
  `PreparedStatement` everywhere, transactions with commit/rollback.
- **Live monitor** – 14 days of farm data replayed live; an int8 TinyML autoencoder (3.9 KB, fits an
  ESP32) plus physical context and threat intelligence raise incidents automatically.
- **Detector comparison** – Isolation Forest, CNN-LSTM and the TinyML framework, all written in Java.

## Requirements

- Java JDK 17 or newer
- MySQL Server 8 or newer (running on `localhost:3306`)
- A web browser

The MySQL JDBC driver is already in `Implementation/AgroCTI/lib/`.

## How to run

```bash
cd Implementation/AgroCTI
mysql -u root -p < sql/setup.sql      # once: creates databases agro_cti, agro_cti_test and user agrocti
./run.sh                              # Windows: run.bat
```

Open **http://localhost:8090** and log in:

| Username | Password | Role |
|---|---|---|
| admin | Admin@2026 | ADMIN |
| analyst | Analyst@2026 | ANALYST |
| farmer | Farmer@2026 | FARMER |

These are demo accounts for a local install – change the passwords on the Users page.
To use your own MySQL account, edit `db.properties`.

Other commands: `./run.sh selftest` (20 automatic tests, 6 of them on MySQL),
`./run.sh evaluate` (train and compare the detectors), `./run.sh serve --port 9090`.

## Results (simulated dataset, 14 test days, 86 attacks)

| Detector | F1 | False-alarm rate | Memory |
|---|---|---|---|
| Isolation Forest | 3.6 % | 2.04 % | 268.4 KB |
| CNN-LSTM | 5.2 % | 1.72 % | 32.0 KB |
| TinyML int8 autoencoder only | 42.9 % | 6.02 % | 3.9 KB |
| **Agro-CTI (full)** | **59.3 %** | **0.74 %** | **3.9 KB** |

The full framework found 81 of the 86 attacks. The datasets named in the project brief were not
available, so the program generates a realistic simulated dataset (see `Dataset/README.txt`).

## Push this folder to GitHub

Create an **empty** repository on GitHub (no README, no .gitignore), then inside this folder:

```bash
git init
git add .
git commit -m "Agro-CTI project: code, dataset, documentation, report and PPT"
git branch -M main
git remote add origin https://github.com/<your-user>/<repo-name>.git
git push -u origin main
```

Then the team leader adds the repository link to the class Google Sheet.

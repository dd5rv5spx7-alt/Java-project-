AGRO-CTI: A THREAT INTELLIGENCE FRAMEWORK FOR PRECISION AGRICULTURE
CYBER-PHYSICAL SYSTEMS
CS213 Advanced JAVA Programming, SRM University-AP
Guide: Mr. S. Gavaskar, Assistant Professor, Department of CSE
Team: M. Sai Chandogya (AP25110090046), N. Sri Varalaxmi (AP25110090062),
      M. Mohana Manogna (AP25110090170), M. Mukesh Vasudhar (AP25110090048),
      Sarwan (AP25110090060)

------------------------------------------------------------------
1. WHAT IT IS
------------------------------------------------------------------
A Java web application, used in the browser, that detects cyber attacks
on smart-farm sensors and irrigation valves and manages the security
records in MySQL through JDBC.
  - Login with three roles: ADMIN, ANALYST, FARMER (view only).
  - Five CRUD modules (create, retrieve with search and sort, update,
    delete), one per team member:
      1 Farm nodes         AgroCTI_FarmNode        (AP25110090046)
      2 Threat indicators  AgroCTI_ThreatIndicator (AP25110090062)
      3 Incidents          AgroCTI_Incident        (AP25110090170)
      4 Response actions   AgroCTI_ResponseAction  (AP25110090048)
      5 Users and login    AgroCTI_User            (AP25110090060)
  - Live monitor: 14 days of farm data replayed live; a TinyML
    autoencoder (int8, 3.9 KB, fits an ESP32) plus physical context and
    threat intelligence raise incidents automatically.
  - Comparison with Isolation Forest and a CNN-LSTM.

------------------------------------------------------------------
2. REQUIREMENTS
------------------------------------------------------------------
- Java JDK 17 or newer (javac and java on the PATH). Check: java -version
- MySQL Server 8 or newer, running on localhost:3306.
- A modern web browser.
- The MySQL JDBC driver is included: lib/mysql-connector-j-9.1.0.jar
  (MySQL Connector/J 9.1.0 from Maven Central).
- Optional: a C compiler to check the ESP32 code on a PC.

------------------------------------------------------------------
3. DATABASE SETUP (once)
------------------------------------------------------------------
Start MySQL, then in this AgroCTI folder run as the MySQL root user:

  mysql -u root -p < sql/setup.sql

This creates the databases agro_cti and agro_cti_test and the user
agrocti / agrocti123. The program creates its six tables itself the
first time it starts (sql/schema.sql shows the same tables).
To use your own MySQL account instead, edit db.properties.

------------------------------------------------------------------
4. HOW TO COMPILE AND RUN
------------------------------------------------------------------
Open a terminal INSIDE this AgroCTI folder, then:

  macOS / Linux:  ./run.sh
  Windows:        run.bat

The first run trains and compares the detectors (about 20 seconds),
then starts the web server:

  Dashboard running at http://localhost:8090

Open http://localhost:8090 and log in with a demo account:

  admin   / Admin@2026     (everything, including user accounts)
  analyst / Analyst@2026   (all records except user accounts)
  farmer  / Farmer@2026    (view only)

Change these passwords on the Users page after the first login.
Stop the server with Ctrl+C.

Other commands (add after run.sh / run.bat):
  evaluate     train and compare the detectors only, print the table
  serve        start the web server with the saved model
  selftest     run the 20 built-in tests (6 of them use MySQL)
  generate     create the simulated dataset again

Options:
  --port 9090        use another port if 8090 is busy
  --delay 100        milliseconds per 15-minute step in the live replay
  --db file          use another db.properties file
  --seed 7           generate a different simulated dataset (with 'generate')
  --data dir         use another data folder (default: data)

Manual commands (what the scripts do):
  javac -d out -cp "lib/*" $(find src -name "*.java")
  java -cp "out:lib/*" com.agrocti.Main
  (on Windows use ; instead of : in the class path)

------------------------------------------------------------------
5. DATA
------------------------------------------------------------------
The datasets named in the project brief were not available to us, so
the program generates a realistic simulated dataset. Files in data/:
  telemetry.csv          one row per node per 15 minutes (92,160 rows)
  actuator_log.csv       every valve command with its source IP address
  attack_episodes.csv    the attacks of the test days
  threat_feed.json       external CTI feed (32 indicators); loaded into
                         MySQL the first time, then edited in the browser
  results.json           evaluation results (shown on the live monitor)
  models/agro_cti_model.bin   trained model

------------------------------------------------------------------
6. RESULTS (seed 2026, 14 test days, 32,256 windows, 86 attacks)
------------------------------------------------------------------
  Detector                        F1      FPR     Memory
  Isolation Forest                3.6 %   2.04 %  268.4 KB
  CNN-LSTM                        5.2 %   1.72 %   32.0 KB
  TinyML int8 autoencoder only   42.9 %   6.02 %    3.9 KB
  Proposed TinyML Agro-CTI       59.3 %   0.74 %    3.9 KB
The proposed framework found 81 of the 86 attacks. These values are
lower than the targets in the brief (which assumed real datasets).

------------------------------------------------------------------
7. FOLDER STRUCTURE
------------------------------------------------------------------
  src/com/agrocti/     Java source (74 files in 14 packages)
    Main.java          entry point and commands
    model/             Record<K> (abstract) and the records: FarmNode,
                       ThreatIndicator, Alert, ResponseAction, UserAccount
    module/            the five AgroCTI_* CRUD modules, CrudModule, Form
    db/                Database (MySQL), Schema, Dao<T,K>, CrudRepository<T,K>
    server/            DashboardServer, SessionManager, LiveSimulator
    cti/ data/ ml/ eval/ export/ exception/ util/ config/ test/
  web/                 login.html, index.html, records.html, *.js, style.css
  sql/                 setup.sql (run once), schema.sql (reference)
  lib/                 mysql-connector-j-9.1.0.jar
  firmware/            ESP32 C code, generated headers, PC test
  db.properties        MySQL URL, user and password
  run.sh / run.bat     compile + run

------------------------------------------------------------------
8. TROUBLESHOOTING
------------------------------------------------------------------
- "cannot reach MySQL"         -> start the MySQL server.
- "MySQL refused user agrocti" -> run sql/setup.sql as root, or put your
                                  own user / password in db.properties.
- "Port 8090 is already in use" -> ./run.sh serve --port 9090
- To start the records again from scratch: DROP DATABASE agro_cti; then
  run sql/setup.sql again. To regenerate the data, delete data/.

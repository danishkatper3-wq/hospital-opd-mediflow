# MediFlow - Hospital OPD Management System

A complete out-patient department (OPD) system for a hospital. Patients are registered at the
reception, receive a token and are called by the doctors one after the other. Emergency patients
are called first. Everything is stored in a real SQL database and managed from a modern web dashboard.

**Built with:** Java (JDK 11+), embedded H2 SQL database, built-in HTTP server, HTML/CSS/JavaScript UI.
No frameworks and no internet connection are needed.

## Features
- **Dashboard** - live statistics, one card per doctor with the patient currently inside, and the patients waiting
- **Register patient** - name, age, gender, phone, complaint, doctor, emergency flag; prints a token slip with position and estimated waiting time
- **Call next** - the doctor calls the next patient; the previous consultation is closed automatically
- **Find / edit** - search by token, name, phone or complaint; edit details, move a patient to another doctor, or cancel a token
- **History** - every served or cancelled visit
- **Activity log** - every action (register, call, update, cancel ...) with time and details
- **Display board** - waiting-room screen showing the token being served by each doctor
- **Doctors** - add doctors with department, room and average consultation time
- Input validation, full-capacity handling, light / dark theme, demo data button
- All data is saved in `data/` and is still there after the program is closed and opened again

## Run
**Requirement:** JDK 11 or newer (check with `javac -version`).

- **Windows:** double-click `run.bat`
- **Linux / Mac:** `./run.sh`
- **VS Code:** open this folder, open `src/opd/Main.java` and press Run (`lib/h2.jar` is added automatically)

Then open **http://localhost:8080** (it opens automatically). Press **Load demo data** to fill the system with sample patients.
Always start the program from the project folder (the one that contains `web`, `src` and `lib`).

## Project layout
```
HospitalOPD-Pro/
  lib/h2.jar                       database driver
  web/index.html                   web interface
  src/opd/Main.java                program start
  src/opd/model/                   Patient, Doctor
  src/opd/db/Database.java         SQL / JDBC layer
  src/opd/service/OPDService.java  application logic
  src/opd/ds/ArrayQueue.java       storage for waiting patients
  src/opd/web/                     HTTP server and JSON
```

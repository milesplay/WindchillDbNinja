# Manual Windows installation for beginners

A step-by-step walk-through for new Windchill customization engineers:
download the package from GitHub in a browser, compile it in the Windchill
shell, deploy it to Windchill and verify it. It uses only the Windchill shell,
Command Prompt (`cmd.exe`), SQL*Plus and a web browser: **no Git and no
PowerShell**.

**Development and test environments only. Do not install or run DB Ninja in
production.** Stop if the environment classification is unknown.

| | |
|---|---|
| Target | Windchill Services12.1.2.23 build38 (12.1.2.0 CPS23), Corretto 11.0.19, Oracle 19c, Windows x64, traditional `codebase`, local NTFS |
| Package | Tag [`v0.2.0-wc121-win1`](https://github.com/milesplay/WindchillDbNinja/tree/v0.2.0-wc121-win1) (Java package `com.custom.dbcapture`) |
| Route | Target build with PTC's CCD `compile`, then PTC's `swmaint.xml`, `xconfmanager` and `jsfrag_combine.xml` by hand |

> [!NOTE]
> This guide is a manual alternative to the tool-driven procedure in
> [INSTALL.md](INSTALL.md) (`node tools/dbninja.mjs build`, `plan`, `apply`).
> It performs the equivalent PTC steps one by one, so that a beginner can see
> and check each change. Its commands were derived from the package's code and
> PTC's scripts; they have **not** been run end-to-end on a Windows server.
> Rehearse on a disposable test VM first. For background read
> [COMPATIBILITY.md](COMPATIBILITY.md), [CUSTOMIZATION.md](CUSTOMIZATION.md) and
> [DATABASE-SETUP.md](DATABASE-SETUP.md).

## Contents

- [Overview: the flow at a glance](#overview-the-flow-at-a-glance)
- [Phase 0. Before you start](#phase-0-before-you-start)
- [Phase 1. Prepare the server (read-only)](#phase-1-prepare-the-server-read-only)
- [Phase 2. Download the source from GitHub](#phase-2-download-the-source-from-github)
- [Phase 3. Prepare the database user (with the DBA)](#phase-3-prepare-the-database-user-with-the-dba)
- [Phase 4. Compile in the Windchill shell](#phase-4-compile-in-the-windchill-shell)
- [Phase 5. Deploy to Windchill](#phase-5-deploy-to-windchill)
- [Phase 6. Start Windchill and verify](#phase-6-start-windchill-and-verify)
- [Appendix A. Roll back](#appendix-a-roll-back)
- [Appendix B. Troubleshooting](#appendix-b-troubleshooting)

## Overview: the flow at a glance

| Phase | What you do | Windchill |
|---|---|---|
| 1. Prepare | Open two terminals, create a settings file, check the server (read-only) | Running |
| 2. Download | Download the source ZIP from GitHub in a browser and extract it | Running |
| 3. Database user | The DBA grants a few Oracle privileges (can be done days earlier) | Running |
| 4. Compile | Stop Windchill, back up, compile with PTC's tool in the Windchill shell | **Stopped** |
| 5. Deploy | Stage and install 28 files, register the configuration, rebuild JavaScript, create 4 tables | **Stopped** |
| 6. Verify | Start Windchill, check the log, test in the browser, run a first capture | Running |

Windchill is down from Phase 4 to the start of Phase 6, about 1.5 to 2 hours.
If a test in Phase 6 fails, use [Appendix A](#appendix-a-roll-back).

> [!IMPORTANT]
> **Why Windchill is stopped before compiling, not only before deploying:**
> PTC's compiler (CCD) does not only build a JAR in the package folder. It also
> writes 7 model files (`*.ClassInfo.ser`) and updates
> `codebase\*Registry.properties` inside the Windchill installation. So the
> backup and the stop must come first.

## Phase 0. Before you start

### 0.1 Check that your server fits this package

| Item | Required |
|---|---|
| Windchill | **12.1.2.x** (qualified: 12.1.2.23 = 12.1.2.0 CPS23) with a traditional `codebase` folder (not `codebase.war`) |
| JDK used by Windchill | **Java 11** (qualified: Corretto 11.0.19). Servlet API: `javax.servlet` |
| Database | **Oracle 19c** only |
| Disk | Windchill on a local **NTFS** volume (no network share) |
| DB Ninja package | **v0.2.0-wc121-win1** (Git tag of the Windows port) |

> [!CAUTION]
> **Stop if** your server does not match, for example Windchill 13.x on
> Windows, Windchill+ or cloud, a `codebase.war` layout, or SQL Server, Azure
> SQL or PostgreSQL. Talk to a senior engineer. Other CPS/SDK combinations
> require a rebuild and qualification: continue only with a senior engineer's
> approval, use Route B in 5.13, and run all of Phase 6.

### 0.2 What you need

- Written confirmation from the environment owner that this is a
  **development or test** system (never production), and an agreed
  maintenance window.
- The Windows account that installed and runs Windchill (the "installation
  owner"). Use it for every step.
- A DBA for Phase 3 and for reviewing the table creation in Phase 5.
- A Windchill site administrator login (for example `wcadmin`) and, if
  possible, one ordinary test user.
- **Node.js 22 or newer** on the Windchill server (used only by DB Ninja's
  helper tools) and **SQL*Plus** on the `PATH`.
- A web browser with access to `github.com` (on the server, or on your PC if
  you can copy files to the server).

### 0.3 Rules

1. **Development and test environments only.** Do not install or run DB Ninja
   in production.
2. Use only the Windchill shell, Command Prompt, SQL*Plus and the browser.
3. **Never edit PTC's own files.** Never hand-edit `wt.properties`,
   `service.properties` or `presentation.properties`; they are generated. Use
   `xconfmanager`.
4. Deploy **only the 28 files** listed in 5.2, through the Safe Area
   (`wtSafeArea\siteMod`) and PTC's `swmaint.xml`.
5. From PTC's customization tool (CCD, `bin\customizationTools\build.xml`) use
   **only** `compile`. Never run `deploy`, `undeploy` or `all`: `undeploy`
   deletes the whole `custom` and `codebase\custom` folders.
6. **Never type a password on a command line.** Let SQL*Plus ask for it.
7. If a check shows `exit=` with a value other than `0`, `STOP`, or a result
   that differs from **Expected result**, stop and look in
   [Appendix B](#appendix-b-troubleshooting).

### 0.4 How to use this guide

- **Two terminals.** PTC's tools (`windchill`, `ant`, `xconfmanager`) need
  **Terminal A (Windchill shell)**. DB Ninja's helper tools fail with the
  Windchill shell's `CLASSPATH`, so they run in **Terminal B (Command
  Prompt)**, a normal Command Prompt without `CLASSPATH`. Each block says where
  to run it.
- **To open a Command Prompt:** sign in as the installation owner, open the
  Start menu, type `cmd` and press <kbd>Enter</kbd>. Do not use "Run as
  administrator" unless a step says so.
- **To run a block:** copy it (GitHub shows a copy button on each block),
  right-click inside the Command Prompt window (or press
  <kbd>Ctrl</kbd>+<kbd>V</kbd>) to paste, and press <kbd>Enter</kbd> if the
  last line has not started. Then compare the output with **Expected result**.
- **`echo exit=%ERRORLEVEL%`** prints the exit code of the line before it.
  `exit=0` means success.
- **SQL*Plus:** a `sqlplus` line is always the last line of its block. Type the
  password, and paste nothing else until SQL*Plus shows `SQL>` or has finished.
- Replace every `<PLACEHOLDER>`, including the angle brackets. Keep the work
  folder path **free of spaces**.

## Phase 1. Prepare the server (read-only)

You create the two terminals and a small settings file, and confirm that the
server matches the package before you change anything.

### 1.1 Open Terminal A (Windchill shell)

**Where:** Terminal A. Open a Command Prompt and start the Windchill shell.
Change the path to your Windchill folder.

```bat
cd /d D:\ptc\Windchill_12.1\Windchill\bin
windchill shell
```

At the new prompt of the Windchill shell, show the JDK that Windchill uses:

```bat
cd ..
xconfmanager -d wt.jdk
```

**Expected result:** under `Values:` you see a JDK folder, for example
`C:\Program Files\Amazon Corretto\jdk11.0.19_7`. Write it down; you need it
in 1.2.

### 1.2 Open Terminal B and create the settings file

**Where:** Terminal B. Open a **second** Command Prompt as the installation
owner. Create the work folder and open a new settings file in Notepad:

```bat
mkdir "%USERPROFILE%\dbninja\work"
notepad "%USERPROFILE%\dbninja-env.bat"
```

Notepad asks whether to create the file: click **Yes**. Paste the following
text, change the `WT_HOME` and `JAVA_HOME` lines, then save and close Notepad.
If your user profile path contains a space, change `DBN_ROOT` to a folder such
as `D:\dbninja` and create that folder instead.

```bat
@echo off
rem CHANGE: Windchill folder (contains codebase and site.xconf)
set "WT_HOME=D:\ptc\Windchill_12.1\Windchill"
rem CHANGE: value shown by "xconfmanager -d wt.jdk" in 1.1
set "JAVA_HOME=C:\Program Files\Amazon Corretto\jdk11.0.19_7"
rem CHANGE at 1.7
set "WC_CONNECT=<DB_USER>@//<DB_HOST>:<DB_PORT>/<DB_SERVICE>"
rem CHANGE at 4.5 only if the ClassInfo files are in custom\ser
set "CLASSINFO_DIR=%WT_HOME%\codebase\com\custom\dbcapture"
rem No change below this line
set "DBN_ROOT=%USERPROFILE%\dbninja"
set "DBN_HOME=%DBN_ROOT%\WindchillDbNinja-0.2.0-wc121-win1"
set "DBN_WORK=%DBN_ROOT%\work"
set "DBN_LIST=%DBN_WORK%\dbninja-files.txt"
set "DBN_BACKUP=%DBN_WORK%\backup"
set "SM=%WT_HOME%\wtSafeArea\siteMod"
set "MOD=%DBN_HOME%\customization\DbCapture\main"
set "WEB=%MOD%\src_web\custom\DbCapture"
set "PATH=%JAVA_HOME%\bin;%WT_HOME%\bin;%WT_HOME%\ant\bin;%PATH%"
```

After saving, load the settings in Terminal B:

```bat
call "%USERPROFILE%\dbninja-env.bat"
set CLASSPATH=
echo WT_HOME=%WT_HOME% JAVA_HOME=%JAVA_HOME%
java -version
```

**Expected result:** both values are printed and neither is empty, and
`java -version` shows version 11.

### 1.3 Load the settings in Terminal A

**Where:** Terminal A.

```bat
call "%USERPROFILE%\dbninja-env.bat"
echo WT_HOME=%WT_HOME% CLASSINFO_DIR=%CLASSINFO_DIR%
```

> [!TIP]
> **Whenever you open a terminal again later:** Terminal A: the first block of
> 1.1, then 1.3. Terminal B: the last block of 1.2. After every change to the
> settings file, run the `call` line again in both terminals (Terminal B also
> `set CLASSPATH=`). Keep both terminals open until Phase 6 is finished.

### 1.4 Check the Windchill release

**Where:** Terminal A.

```bat
windchill version | findstr /c:" wnc."
```

**Expected result:** a row with `wnc.12.1.2.23` is the qualified baseline.
Any other release: see the Stop box in 0.1.

### 1.5 Check Java, Node.js and SQL*Plus

**Where:** Terminal A.

```bat
java -version
javac -version
```

**Where:** Terminal B.

```bat
node --version
where sqlplus
```

**Expected result:** Java 11 (11.0.19 on the baseline); `javac` prints a
version, so this is a JDK and not only a JRE; Node.js is v22 or newer; `where`
prints the path of `sqlplus.exe`. If Node.js is missing or older, install
Node.js 22 LTS, then open Terminal B again.

### 1.6 Check the installation layout

**Where:** Terminal A.

```bat
cd /d "%WT_HOME%"
if not exist codebase.war echo OK: traditional codebase
jar tf tomcat\lib\servlet-api.jar | find /c "javax/servlet/http/HttpServletRequest.class"
fsutil fsinfo volumeinfo %WT_HOME:~0,2% | find "NTFS"
for %f in (bin\xconfmanager.bat bin\swmaint.xml bin\jsfrag_combine.xml bin\tools.xml bin\customizationTools\build.xml) do @if exist %f (echo OK %f) else (echo MISSING %f)
```

**Expected result:** `OK: traditional codebase`, then `1`, then a line with
`NTFS`, then five `OK` lines. If `fsutil` is refused, run only that line in a
Command Prompt opened with "Run as administrator".

### 1.7 Read the database connection and finish the settings file

**Where:** Terminal A.

```bat
xconfmanager -d "wt.pom.dbUser,wt.pom.jdbc.host,wt.pom.jdbc.port,wt.pom.jdbc.service,wt.db.dataStore"
```

**Expected result:** values for the database user, host, port and service, and
`wt.db.dataStore` = `Oracle`. The command deliberately does not read the
password.

**Where:** Terminal B. Open the settings file, set `WC_CONNECT` from these
values (for example `wcuser@//dbhost.example.com:1521/WNDB`), save and close
Notepad:

```bat
notepad "%USERPROFILE%\dbninja-env.bat"
```

Then load the settings again in both terminals. Terminal B:

```bat
call "%USERPROFILE%\dbninja-env.bat"
set CLASSPATH=
echo %WC_CONNECT%
```

Terminal A:

```bat
call "%USERPROFILE%\dbninja-env.bat"
```

### 1.8 Check the current configuration

**Where:** Terminal A. `xconfmanager -d` (describe) shows a property's value,
where it is declared and its target file. It changes nothing.

```bat
cd /d "%WT_HOME%"
xconfmanager -d "wt.services.service.905000,wt.db.maxBytesPerChar,wt.java.classpath,netmarkets.presentation.cssFiles"
```

| Property | Expected on a fresh system | What to do |
|---|---|---|
| `wt.services.service.905000` | `No information available for property.` | If it has a value, stop: another customization uses this service slot. |
| `wt.db.maxBytesPerChar` | `3` | Any other value means you use Route B in 5.13. |
| `wt.java.classpath` | A list of values | Nothing to do; the Windows package adds `custom\lib\*`. |
| `netmarkets.presentation.cssFiles` | Often empty | Write down any existing values. They must still be there after 5.10. |

## Phase 2. Download the source from GitHub

The complete, versioned package contains the Java source, the web files, the
database script and the helper tools. Always take the whole package of the
Windows tag, never single files. No Git is needed.

### 2.1 Open the Windows version's page

**Where:** browser.

1. Open <https://github.com/milesplay/WindchillDbNinja/tree/v0.2.0-wc121-win1>.
2. Above the file list, the branch/tag button must show **`v0.2.0-wc121-win1`**.
   The latest-commit line shows `a6309ff`.

> [!WARNING]
> **Do not download the default branch.** The repository's start page shows the
> branch `main`. That is the **Linux** version (v0.1.1 for Windchill 13.0, Java
> package `com.ptc.dbcapture`). It does not work on Windchill 12.1. Download
> only from the `v0.2.0-wc121-win1` page.

### 2.2 Download the ZIP

**Where:** browser.

1. Click the green **Code** button, then **Download ZIP**. The browser saves
   `WindchillDbNinja-0.2.0-wc121-win1.zip` (about 0.7 MB), normally in your
   `Downloads` folder. Direct link with the same file:
   <https://github.com/milesplay/WindchillDbNinja/archive/refs/tags/v0.2.0-wc121-win1.zip>
2. If you downloaded on your PC, copy the ZIP to the Windchill server (for
   example with copy and paste in Remote Desktop) into the installation owner's
   `Downloads` folder or directly into the work folder.

**Where:** Terminal B. Move the ZIP into the work folder (skip the `move` line
if it is already there):

```bat
move "%USERPROFILE%\Downloads\WindchillDbNinja-0.2.0-wc121-win1.zip" "%DBN_ROOT%\"
dir "%DBN_ROOT%\WindchillDbNinja-0.2.0-wc121-win1.zip"
```

**Expected result:** `dir` lists the ZIP. If the name differs (for example
`WindchillDbNinja-main.zip`), you downloaded the wrong branch: delete it and
repeat 2.1.

> [!NOTE]
> The Windows port is published as a Git tag without a release ZIP or
> `SHA256SUMS` file, so there is no published SHA-256 to compare. The package
> checks in 2.4 and 5.13 verify its content instead.

### 2.3 Unblock and extract the ZIP

**Where:** File Explorer.

1. Open `%USERPROFILE%\dbninja` (type it in the address bar and press
   <kbd>Enter</kbd>).
2. Right-click the ZIP > **Properties**. On the **General** tab, if you see
   "This file came from another computer...", tick **Unblock** and click **OK**.
3. Right-click the ZIP > **Extract All...**. In the destination box, **delete
   the trailing `\WindchillDbNinja-0.2.0-wc121-win1`**, so that the box shows
   only the work folder, for example `C:\Users\wcadmin\dbninja`. Click
   **Extract**.

> [!NOTE]
> **Why delete the folder name:** the ZIP already contains the folder
> `WindchillDbNinja-0.2.0-wc121-win1`. With Explorer's default destination you
> get the folder twice, and every later command fails. Alternative without
> Explorer, in Terminal B: `cd /d "%DBN_ROOT%"` and then
> `tar -xf WindchillDbNinja-0.2.0-wc121-win1.zip`.

**Where:** Terminal B. Check the result:

```bat
dir /b "%DBN_HOME%"
dir "%DBN_HOME%\tools\dbninja.mjs"
```

**Expected result:** the first command lists, among others, `customization`,
`deployment`, `prebuilt`, `sql`, `tools` and `README.md`. The second shows one
file.

> [!CAUTION]
> **Stop if** you see `File Not Found`: the ZIP was extracted into an extra
> folder. Delete the extracted folder and repeat step 3 of the list above.

### 2.4 Check the package against your server

**Where:** Terminal B.

```bat
cd /d "%DBN_HOME%"
node tools\dbninja.mjs preflight
echo exit=%ERRORLEVEL%
node tools\schema-package.mjs verify
echo exit=%ERRORLEVEL%
```

**Expected result:** `PASS: Windows filesystem/toolchain checks. ...` and
`PASS: four module tables, four primary keys, fourteen secondary indexes ...`,
each followed by `exit=0`. Both commands are read-only; they do not connect to
Oracle.

> [!CAUTION]
> **Stop if** `preflight` does not print `PASS`, or any `exit` is not `0`. Read
> the `ERROR:` line and see [Appendix B](#appendix-b-troubleshooting).

## Phase 3. Prepare the database user (with the DBA)

At **Start** and **Stop**, DB Ninja reads Oracle's system change number (SCN)
and flushes table-monitoring counters; then it compares rows with Flashback
queries on Windchill's own tables. These run as the Windchill database user,
which needs a few extra privileges. This phase can be done days before the
maintenance window. [DATABASE-SETUP.md](DATABASE-SETUP.md) is the full runbook.

### 3.1 DBA: check what the user already has (read-only)

**Where:** DBA, SQL*Plus. The DBA connects as a DBA to the database or
pluggable database (PDB) that holds the Windchill schema, for example on the
database server, and replaces `WCUSER` with the user from 1.7.

```bat
sqlplus / as sysdba
```

```sql
ALTER SESSION SET CONTAINER = <WINDCHILL_PDB>;   -- skip for a non-CDB database
DEFINE WCUSER = 'WCUSER'
SET LINESIZE 200 PAGESIZE 100 VERIFY OFF
SELECT version_full FROM product_component_version WHERE product LIKE 'Oracle Database%';
SELECT username, default_tablespace FROM dba_users WHERE username = UPPER('&WCUSER');
SELECT granted_role FROM dba_role_privs WHERE grantee = UPPER('&WCUSER') ORDER BY 1;
SELECT privilege FROM dba_sys_privs WHERE grantee = UPPER('&WCUSER') ORDER BY 1;
SELECT grantee, table_name, privilege FROM dba_tab_privs
 WHERE owner = 'SYS' AND grantee IN (UPPER('&WCUSER'), 'PUBLIC')
   AND table_name IN ('DBMS_FLASHBACK','DBMS_STATS','V_$DATABASE','V_$PARAMETER','V_$UNDOSTAT')
 ORDER BY table_name, grantee;
SELECT tablespace_name, max_bytes FROM dba_ts_quotas WHERE username = UPPER('&WCUSER');
SELECT tablespace_name, status, contents FROM dba_tablespaces WHERE tablespace_name = 'INDX';
SELECT name, value FROM v$parameter WHERE name IN ('statistics_level', 'undo_retention');
```

**Expected result:** typical for a user created with PTC's `create_user.sql`:
roles `CONNECT` and `RESOURCE` (includes `CREATE TABLE`) and
`UNLIMITED TABLESPACE`. `INDX` is `ONLINE` and `PERMANENT`.
`statistics_level` is `TYPICAL` or `ALL`.

### 3.2 DBA: grant only what is missing

**Where:** DBA, SQL*Plus.

| Needed for | Privilege |
|---|---|
| Reading the SCN at Start and Stop | `EXECUTE ON SYS.DBMS_FLASHBACK` (not granted to PUBLIC by default) |
| SCN fallback and diagnostics | `SELECT ON SYS.V_$DATABASE`, `SYS.V_$PARAMETER`, `SYS.V_$UNDOSTAT` |
| Monitoring flush at Start and Stop | `ANALYZE ANY`. Oracle requires it for `DBMS_STATS.FLUSH_DATABASE_MONITORING_INFO`. It is broad, so the DBA must approve it explicitly. |
| Creating the tables (Phase 5) | `CREATE TABLE` and space in the default tablespace and in `INDX` (usually present already) |
| Flashback reads of Windchill's own tables | Nothing: the owner already has them. **Do not** grant `FLASHBACK ANY TABLE`. |

In the same SQL*Plus session as 3.1, as SYS or an equivalent grantor, keep
only the lines that the inventory shows are missing:

```sql
GRANT EXECUTE ON SYS.DBMS_FLASHBACK TO &WCUSER;
GRANT SELECT  ON SYS.V_$DATABASE   TO &WCUSER;
GRANT SELECT  ON SYS.V_$PARAMETER  TO &WCUSER;
GRANT SELECT  ON SYS.V_$UNDOSTAT   TO &WCUSER;
GRANT ANALYZE ANY TO &WCUSER;
-- Only if missing:
-- GRANT CREATE TABLE TO &WCUSER;
-- ALTER USER &WCUSER QUOTA <SIZE>M ON INDX;
-- ALTER USER &WCUSER QUOTA <SIZE>M ON <DEFAULT_TABLESPACE>;
EXIT
```

**Expected result:** `Grant succeeded.` for each line. Write down which grants
were added; you need the list for an uninstall.

> [!WARNING]
> **Never grant** the `DBA` role, `SELECT ANY DICTIONARY`,
> `FLASHBACK ANY TABLE` or `CREATE ANY TABLE/INDEX`. Never change
> `UNDO_RETENTION` or other Oracle parameters for this tool.

### 3.3 Check as the Windchill database user

**Where:** Terminal B, SQL*Plus as the Windchill database user. This read-only
script from the package runs as the Windchill database user. SQL*Plus asks for
the password.

```bat
cd /d "%DBN_HOME%\sql"
sqlplus -L %WC_CONNECT% @oracle-check.sql
```

When SQL*Plus has finished:

```bat
echo exit=%ERRORLEVEL%
```

**Expected result:** `SESSION_USER` and `CURRENT_SCHEMA` are both the
Windchill user; the privileges include `ANALYZE ANY` and `CREATE TABLE`; `INDX`
is `ONLINE PERMANENT`; two SCN numbers are shown; no rows for the
`DBCAPTURE...` tables (fresh installation); `exit=0`.

Then test the monitoring flush:

```bat
sqlplus -L %WC_CONNECT%
```

```sql
EXEC DBMS_STATS.FLUSH_DATABASE_MONITORING_INFO
EXIT
```

**Expected result:** `PL/SQL procedure successfully completed.` An `ORA-20000`
or `ORA-01031` error means that `ANALYZE ANY` is missing.

<details>
<summary>Optional (DBA-approved): Flashback probe on a throwaway table</summary>

This proves that `AS OF SCN` and `VERSIONS BETWEEN` work for the Windchill
user. It creates and drops one small table in the Windchill schema. Connect
with `sqlplus -L %WC_CONNECT%` and paste:

```sql
SET NUMWIDTH 20
CREATE TABLE DBN_FLASHBACK_PROBE (id NUMBER PRIMARY KEY, val VARCHAR2(10 BYTE));
EXEC DBMS_SESSION.SLEEP(15)
COLUMN s NEW_VALUE START_SCN
SELECT DBMS_FLASHBACK.GET_SYSTEM_CHANGE_NUMBER AS s FROM dual;
INSERT INTO DBN_FLASHBACK_PROBE VALUES (1, 'A');
COMMIT;
UPDATE DBN_FLASHBACK_PROBE SET val = 'B' WHERE id = 1;
COMMIT;
COLUMN e NEW_VALUE END_SCN
SELECT DBMS_FLASHBACK.GET_SYSTEM_CHANGE_NUMBER AS e FROM dual;
SELECT COUNT(*) AS rows_at_start FROM DBN_FLASHBACK_PROBE AS OF SCN &START_SCN;
SELECT val AS value_at_end FROM DBN_FLASHBACK_PROBE AS OF SCN &END_SCN;
SELECT versions_operation, val FROM DBN_FLASHBACK_PROBE VERSIONS BETWEEN SCN &START_SCN AND &END_SCN;
DROP TABLE DBN_FLASHBACK_PROBE PURGE;
EXIT
```

**Expected result:** `ROWS_AT_START` = 0, `VALUE_AT_END` = B, the versions
query shows `I A` and `U B`, then `Table dropped.`

</details>

**Checkpoint:** the check script ended with `exit=0`, the flush succeeded, and
the added grants are written down.

## Phase 4. Compile in the Windchill shell

PTC's CCD tool (`bin\customizationTools\build.xml`, target `compile`) compiles
the Java and resource-bundle source with PTC's annotation processors. For DB
Ninja's four persistable (modeled) classes it generates the base classes,
writes 7 `ClassInfo.ser` files and registers the model in
`codebase\*Registry.properties`. That is why modeled classes are always
compiled on the target system and not copied from elsewhere.

### 4.1 Stop Windchill (the maintenance window starts)

**Where:** Terminal A.

```bat
windchill stop
```

**Expected result:** the command finishes. If your site runs Windchill as a
Windows service, stop it with the site's normal service procedure instead.
Apache and the LDAP directory server can keep running. Never kill Java
processes by hand.

### 4.2 Back up the files that the deployment changes

**Where:** Terminal B.

```bat
if exist "%DBN_BACKUP%" (echo STOP: %DBN_BACKUP% already exists) else (mkdir "%DBN_BACKUP%\files")
```

> [!CAUTION]
> **Stop if** `STOP` is printed: an older backup exists. Change `DBN_BACKUP` in
> the settings file to a new name (for example `%DBN_WORK%\backup2`), load the
> settings again and repeat this block.

```bat
set "BLOG=%DBN_BACKUP%\backup.log"
robocopy "%WT_HOME%" "%DBN_BACKUP%\files" site.xconf declarations.xconf /R:0 /NP /LOG+:"%BLOG%"
if errorlevel 8 echo STOP: robocopy failed
robocopy "%WT_HOME%\codebase" "%DBN_BACKUP%\files\codebase" wt.properties presentation.properties service.properties modelRegistry.properties descendentRegistry.properties associationRegistry.properties customroleaccessprefs.xml /R:0 /NP /LOG+:"%BLOG%"
if errorlevel 8 echo STOP: robocopy failed
robocopy "%WT_HOME%\codebase\netmarkets\javascript\util" "%DBN_BACKUP%\files\codebase\netmarkets\javascript\util" *.js /R:0 /NP /LOG+:"%BLOG%"
if errorlevel 8 echo STOP: robocopy failed
if exist "%WT_HOME%\custom\xconf" robocopy "%WT_HOME%\custom\xconf" "%DBN_BACKUP%\files\custom\xconf" /E /R:0 /NP /LOG+:"%BLOG%"
if errorlevel 8 echo STOP: robocopy failed
if exist "%WT_HOME%\wtSafeArea" robocopy "%WT_HOME%\wtSafeArea" "%DBN_BACKUP%\files\wtSafeArea" /E /R:0 /NP /LOG+:"%BLOG%"
if errorlevel 8 echo STOP: robocopy failed
dir /s /b /a-d "%DBN_BACKUP%\files" > "%DBN_BACKUP%\manifest.txt"
type "%DBN_BACKUP%\manifest.txt" | find /c /v ""
```

**Expected result:** no `STOP` line, and a file count at the end (dozens).
`robocopy` writes its details to `backup.log`, so the window stays quiet. On a
fresh system `customroleaccessprefs.xml`, `custom\xconf` and `wtSafeArea`
usually do not exist yet; that is fine. Also make sure that the site's normal
file-system and database backups are recent.

### 4.3 Compile

**Where:** Terminal A.

```bat
type nul > "%DBN_WORK%\compile.marker"
cd /d "%WT_HOME%\bin\customizationTools"
ant -f build.xml clean validate.folder.structure compile "-Dwt.customizationSource.dir.path=%DBN_HOME%\customization"
echo exit=%ERRORLEVEL%
```

Keep the double quotes around the `-D...` argument. The first line only
creates an empty time marker for 4.5.

**Expected result:**

- `Java Classes Compiled and Deployed on Jar File [...\customization\temp\lib\DbCapture.jar]`
- `rbInfo files compiled and Deployed in Jar File [...]`
- `Compile is successful for following modules:` followed by `DbCapture`, then
  `exit=0`.
- A "JasperReports ... Compilation skipped" line and compiler notes about
  deprecated APIs are normal. The full log is
  `%WT_HOME%\buildlogs\customizationLogs\customizationInstallLogs_<time>.log`.

### 4.4 Check the JAR

**Where:** Terminal A.

```bat
jar tf "%DBN_HOME%\customization\temp\lib\DbCapture.jar" | findstr /l /c:"com/custom/dbcapture/StandardDbCaptureService.class" /c:"com/custom/dbcapture/_DbCaptureSession.class" /c:"com/custom/dbcapture/dbCaptureActionResource.class" /c:"com/custom/dbcapture/dbcaptureResource.RB.ser" /c:"META-INF/ptc.listeners.lst"
```

**Expected result:** exactly 5 lines. `_DbCaptureSession.class` proves that
PTC's annotation processing ran, and `dbcaptureResource.RB.ser` proves that the
resource bundle was compiled.

### 4.5 Find the 7 ClassInfo files and check the model registry

**Where:** Terminal A. The compiler writes the ClassInfo files to
`codebase\com\custom\dbcapture` or, on sites that use PTC's custom
schema-artifact folder, to `custom\ser\com\custom\dbcapture`.

```bat
dir /t:w "%DBN_WORK%\compile.marker"
dir /t:w "%WT_HOME%\codebase\com\custom\dbcapture\*.ClassInfo.ser"
dir /t:w "%WT_HOME%\custom\ser\com\custom\dbcapture\*.ClassInfo.ser"
```

**Expected result:** exactly one of the two folders has 7 `*.ClassInfo.ser`
files with a time at or after `compile.marker`. On a fresh system this is the
`codebase` folder, which is already the default of `CLASSINFO_DIR`.

Only if the 7 files are in `custom\ser`: in Terminal B run
`notepad "%USERPROFILE%\dbninja-env.bat"`, change `CLASSINFO_DIR` to
`%WT_HOME%\custom\ser\com\custom\dbcapture`, save, and load the settings again
in both terminals. Then check:

```bat
echo %CLASSINFO_DIR%
cd /d "%WT_HOME%\codebase"
findstr /l /b /c:"com.custom.dbcapture=" modelRegistry.properties | find /c /v ""
findstr /l /c:"=com.custom.dbcapture." descendentRegistry.properties | find /c /v ""
findstr /l /b /c:"com.custom.dbcapture." associationRegistry.properties | find /c /v ""
```

**Expected result:** `CLASSINFO_DIR` is the folder with the 7 files, and the
counts are `7`, `7` and `6`.

> [!CAUTION]
> **Stop if** no folder or both folders have 7 new files, or a count is 0.
> Never use ClassInfo files that this compile did not produce.

## Phase 5. Deploy to Windchill

PTC keeps every site-customized file in `wtSafeArea\siteMod\<same path>` and
installs it with `swmaint.xml`, so customizations survive a CPS update. DB
Ninja only adds files; it replaces no PTC file. After the files,
`xconfmanager` registers the configuration, `jsfrag_combine.xml` rebuilds the
JavaScript bundles, and the 4 database tables are created.

### 5.1 Create the Safe Area

**Where:** Terminal A.

```bat
cd /d "%WT_HOME%"
ant -f bin\swmaint.xml createSafeArea
echo exit=%ERRORLEVEL%
dir /s /b /a-d "%SM%" 2>nul | find /c /v ""
```

**Expected result:** `BUILD SUCCESSFUL`, `exit=0`, then `0` on a fresh system.

> [!CAUTION]
> **Stop if** the count is not 0: the Safe Area already holds other
> customizations, and `installSiteChanges` would reinstall them too. Ask a
> senior engineer.

### 5.2 Write the list of the 28 files

**Where:** Terminal B. All later checks and the roll back use this list.

```bat
(
echo custom\lib\DbCapture.jar
echo codebase\com\custom\dbcapture\DbCaptureAttrDelta.ClassInfo.ser
echo codebase\com\custom\dbcapture\DbCaptureChange.ClassInfo.ser
echo codebase\com\custom\dbcapture\DbCaptureChangeDeltaLink.ClassInfo.ser
echo codebase\com\custom\dbcapture\DbCaptureSession.ClassInfo.ser
echo codebase\com\custom\dbcapture\DbCaptureSessionChangeLink.ClassInfo.ser
echo codebase\com\custom\dbcapture\DbCaptureSessionTableLink.ClassInfo.ser
echo codebase\com\custom\dbcapture\DbCaptureTableChange.ClassInfo.ser
echo codebase\netmarkets\jsp\dbcapture\dbCaptureAdmin.jsp
echo codebase\netmarkets\jsp\dbcapture\dbCaptureState.jsp
echo codebase\netmarkets\jsp\dbcapture\editDescription.jsp
echo codebase\netmarkets\jsp\dbcapture\captureDiagnostics.jsp
echo codebase\netmarkets\jsp\dbcapture\objectDetails.jsp
echo codebase\config\actions\DbCapture-actions.xml
echo codebase\config\actions\DbCapture-actionModels.xml
echo codebase\config\mvc\DbCapture-configs.xml
echo codebase\config\urlValidators\DbCapture-validators.xml
echo codebase\customroleaccessprefs.xml
echo custom\DbCapture\xconf\DbCapture.service.properties.xconf
echo custom\xconf\DbNinja.xconf
echo codebase\custom\DbCapture\dbCapture-v2026092003.css
echo codebase\custom\DbCapture\dbCaptureHeader-v2026092007.js
echo codebase\custom\DbCapture\dbCaptureCsv-v2026092003.js
echo codebase\custom\DbCapture\dbCaptureDiagnostics-v20260920.js
echo codebase\custom\DbCapture\dbNinjaActionIcons-v2026092101.js
echo codebase\netmarkets\images\dbcapture\dbNinjaTrick-v20260921.png
echo codebase\netmarkets\images\dbcapture\dbNinjaStealth-v20260921.png
echo codebase\netmarkets\javascript\util\jsfrags\dbNinja.jsfrag
) > "%DBN_LIST%"
type "%DBN_LIST%" | find /c /v ""
```

**Expected result:** `28`

<details>
<summary>What the 28 files are</summary>

| # | Kind | Runtime path (under `WT_HOME`) | Registered by |
|---|---|---|---|
| 1 | Java JAR (built in 4.3) | `custom\lib\DbCapture.jar` | `wt.java.classpath` (5.10) |
| 2-8 | Model metadata (built in 4.3) | `codebase\com\custom\dbcapture\*.ClassInfo.ser` | Windchill introspection |
| 9-13 | JSP pages | `codebase\netmarkets\jsp\dbcapture\` | Action URL convention |
| 14-15 | Actions and action models | `codebase\config\actions\` | `customActions`, `customActionModels` (5.10) |
| 16 | MVC (Spring) builders | `codebase\config\mvc\` | Imported as `*-configs.xml` |
| 17 | URL validators | `codebase\config\urlValidators\` | Windchill URL validation |
| 18 | UI component (merged in 5.6) | `codebase\customroleaccessprefs.xml` | Merged with PTC's `roleaccessprefs.xml` |
| 19-20 | XCONF declarations | `custom\DbCapture\xconf\`, `custom\xconf\` | `xconfmanager -i` (5.10) |
| 21 | CSS | `codebase\custom\DbCapture\` | `netmarkets.presentation.cssFiles` (5.10) |
| 22-25 | JavaScript | `codebase\custom\DbCapture\` | Page script or source of the fragment |
| 26-27 | 16 x 16 icons | `codebase\netmarkets\images\dbcapture\` | Action resource bundle |
| 28 | JavaScript fragment (created in 5.5) | `codebase\netmarkets\javascript\util\jsfrags\dbNinja.jsfrag` | `jsfrag_combine.xml` (5.12) |

</details>

### 5.3 Check that the XML files are well-formed

**Where:** Terminal B. The package's helper runs on your JDK and does not load
DTDs.

```bat
cd /d "%DBN_HOME%"
java tools\ConfigurationFiles.java check "%MOD%\src_web\config\actions\DbCapture-actions.xml" unused
echo exit=%ERRORLEVEL%
java tools\ConfigurationFiles.java check "%MOD%\src_web\config\actions\DbCapture-actionModels.xml" unused
echo exit=%ERRORLEVEL%
java tools\ConfigurationFiles.java check "%MOD%\src_web\config\mvc\DbCapture-configs.xml" unused
echo exit=%ERRORLEVEL%
java tools\ConfigurationFiles.java check "%WEB%\overlay\DbCapture-validators.xml" unused
echo exit=%ERRORLEVEL%
```

**Expected result:** four times `exit=0`. A Java stack trace means that a file
is damaged: extract the package again (2.3).

### 5.4 Copy the files into the Safe Area

**Where:** Terminal B.

```bat
mkdir "%SM%\custom\lib" "%SM%\codebase\com\custom\dbcapture" "%SM%\codebase\netmarkets\jsp\dbcapture" "%SM%\codebase\config\actions" "%SM%\codebase\config\mvc" "%SM%\codebase\config\urlValidators" "%SM%\custom\DbCapture\xconf" "%SM%\custom\xconf" "%SM%\codebase\custom\DbCapture" "%SM%\codebase\netmarkets\images\dbcapture" "%SM%\codebase\netmarkets\javascript\util\jsfrags"
copy /y "%DBN_HOME%\customization\temp\lib\DbCapture.jar" "%SM%\custom\lib\"
for %c in (DbCaptureAttrDelta DbCaptureChange DbCaptureChangeDeltaLink DbCaptureSession DbCaptureSessionChangeLink DbCaptureSessionTableLink DbCaptureTableChange) do @copy /y "%CLASSINFO_DIR%\%c.ClassInfo.ser" "%SM%\codebase\com\custom\dbcapture\"
for %j in (dbCaptureAdmin dbCaptureState editDescription captureDiagnostics objectDetails) do @copy /y "%WEB%\overlay\netmarkets\jsp\dbcapture\%j.jsp" "%SM%\codebase\netmarkets\jsp\dbcapture\"
copy /y "%MOD%\src_web\config\actions\DbCapture-actions.xml" "%SM%\codebase\config\actions\"
copy /y "%MOD%\src_web\config\actions\DbCapture-actionModels.xml" "%SM%\codebase\config\actions\"
copy /y "%MOD%\src_web\config\mvc\DbCapture-configs.xml" "%SM%\codebase\config\mvc\"
copy /y "%WEB%\overlay\DbCapture-validators.xml" "%SM%\codebase\config\urlValidators\"
copy /y "%MOD%\xconf\DbCapture.service.properties.xconf" "%SM%\custom\DbCapture\xconf\"
copy /y "%DBN_HOME%\deployment\DbNinja.xconf" "%SM%\custom\xconf\"
for %a in (dbCapture-v2026092003.css dbCaptureHeader-v2026092007.js dbCaptureCsv-v2026092003.js dbCaptureDiagnostics-v20260920.js dbNinjaActionIcons-v2026092101.js) do @copy /y "%WEB%\%a" "%SM%\codebase\custom\DbCapture\"
for %i in (dbNinjaTrick-v20260921.png dbNinjaStealth-v20260921.png) do @copy /y "%WEB%\icons\%i" "%SM%\codebase\netmarkets\images\dbcapture\"
```

**Expected result:** every `copy` prints `1 file(s) copied.` and there is no
error: 26 files in total. The other 2 files are created in 5.5 and 5.6. Do not
copy anything else, such as the whole `overlay` folder, `descriptor.xml`,
`custom.site.xconf`, `tools\` or `sql\`.

### 5.5 Create the global JavaScript fragment

**Where:** Terminal B. PTC's way to add JavaScript to every page is a new,
uniquely named `*.jsfrag` file, combined into the bundles in 5.12. This single
command builds `dbNinja.jsfrag` with the package's own function, so it is
identical to what the package's installer produces.

```bat
cd /d "%DBN_HOME%"
node --input-type=module -e "import fs from 'node:fs'; import path from 'node:path'; import {createJsfrag, assets, bundle} from './tools/dbninja.mjs'; const d = path.join(bundle, 'customization/DbCapture/main/src_web/custom/DbCapture'); const r = n => fs.readFileSync(path.join(d, n), 'utf8'); fs.writeFileSync(process.argv[1], createJsfrag(r(assets.header), r(assets.csv), r(assets.menuIcons))); console.log('Wrote ' + process.argv[1]);" "%SM%\codebase\netmarkets\javascript\util\jsfrags\dbNinja.jsfrag"
echo exit=%ERRORLEVEL%
certutil -hashfile "%SM%\codebase\netmarkets\javascript\util\jsfrags\dbNinja.jsfrag" SHA256
```

**Expected result:** `Wrote ...dbNinja.jsfrag`, `exit=0`, and the SHA256 hash
`bbc9776f375573aa899e561b8e71306adca4b9432d0a484e03bf5552e5039cb0` (upper or
lower case does not matter).

### 5.6 Register the UI component (customroleaccessprefs.xml)

**Where:** Terminal B. PTC's `roleaccessprefs.xml` must never be edited.
Customizations add UI components to `codebase\customroleaccessprefs.xml`, which
other customizations may share. The helper merges `DB_CAPTURE_ADMIN` into the
existing file, or uses the package template on a fresh system.

```bat
cd /d "%DBN_HOME%"
set "RAP_IN=%WT_HOME%\codebase\customroleaccessprefs.xml"
if not exist "%RAP_IN%" set "RAP_IN=%WEB%\overlay\customroleaccessprefs.xml"
echo %RAP_IN%
java tools\ConfigurationFiles.java role "%RAP_IN%" "%SM%\codebase\customroleaccessprefs.xml"
echo exit=%ERRORLEVEL%
find /c "DB_CAPTURE_ADMIN" "%SM%\codebase\customroleaccessprefs.xml"
```

**Expected result:** `exit=0` and a count of `1`. If the site already had a
`customroleaccessprefs.xml`, all of its original entries must still be in the
new file.

### 5.7 Review the Safe Area

**Where:** Terminal B.

```bat
for /f "usebackq delims=" %f in ("%DBN_LIST%") do @if not exist "%SM%\%f" echo MISSING %f
dir /s /b /a-d "%SM%" | find /c /v ""
```

**Where:** Terminal A. Let PTC's script list what it will install:

```bat
cd /d "%WT_HOME%"
ant -f bin\swmaint.xml listSiteChanges
```

**Expected result:** no `MISSING` line, the count `28`, and `listSiteChanges`
lists exactly these 28 files.

### 5.8 Install the files with swmaint.xml

**Where:** Terminal A.

```bat
cd /d "%WT_HOME%"
ant -f bin\swmaint.xml installSiteChanges
echo exit=%ERRORLEVEL%
```

**Expected result:** `Installing site changes.`, `Copying 28 files to ...`,
`Site changes install complete.`, `BUILD SUCCESSFUL` and `exit=0`.

### 5.9 Check that every file arrived

**Where:** Terminal B.

```bat
(for /f "usebackq delims=" %f in ("%DBN_LIST%") do @(fc /b "%SM%\%f" "%WT_HOME%\%f" >nul 2>&1 && echo OK   %f || echo DIFF %f)) > "%DBN_WORK%\install-check.txt"
findstr /b /c:"OK " "%DBN_WORK%\install-check.txt" | find /c /v ""
findstr /b /c:"DIFF" "%DBN_WORK%\install-check.txt"
```

**Expected result:** `28` and no `DIFF` lines. No permission changes are
needed on Windows: the files inherit the folder permissions.

### 5.10 Register the configuration with xconfmanager

**Where:** Terminal A. `-i` adds DB Ninja's declaration file to
`declarations.xconf`, `--add` appends the CSS file to a multi-valued property
in `site.xconf`, and `-p` (propagate) regenerates the `*.properties` files.
This registers the service (slot 905000), the actions, the validation filters
and data utilities, the CSS, and `custom\lib\*` on the Java class path.

```bat
cd /d "%WT_HOME%"
xconfmanager --validateasdecl "%WT_HOME%\custom\DbCapture\xconf\DbCapture.service.properties.xconf"
echo exit=%ERRORLEVEL%
xconfmanager --validateasdecl "%WT_HOME%\custom\xconf\DbNinja.xconf"
echo exit=%ERRORLEVEL%
xconfmanager -i custom/xconf/DbNinja.xconf
echo exit=%ERRORLEVEL%
xconfmanager --add "netmarkets.presentation.cssFiles=custom/DbCapture/dbCapture-v2026092003.css"
echo exit=%ERRORLEVEL%
xconfmanager -p
echo exit=%ERRORLEVEL%
xconfmanager --validateassite "%WT_HOME%\site.xconf"
echo exit=%ERRORLEVEL%
```

**Expected result:** six times `exit=0`. The wrapper first echoes the Java
command it runs; that is normal. Propagation can take a minute.

### 5.11 Check the generated configuration

**Where:** Terminal A.

```bat
cd /d "%WT_HOME%"
findstr /l /b /c:"wt.services.service.905000=" /c:"com.ptc.netmarkets.util.misc.customActions=" /c:"com.ptc.netmarkets.util.misc.customActionModels=" /c:"com.custom.dbcapture." codebase\wt.properties
find /c "com.custom.dbcapture." codebase\service.properties
findstr /l /b /c:"netmarkets.presentation.cssFiles=" codebase\presentation.properties
windchill which com/custom/dbcapture/StandardDbCaptureService.class
```

**Expected result:**

- Six `wt.properties` lines: the three module settings
  (`correlateLogs=false`, `excludeTables=`, `maxRowsPerTable=5000`),
  `customActionModels=...DbCapture-actionModels.xml`,
  `customActions=...DbCapture-actions.xml` and
  `wt.services.service.905000=...DbCaptureService/...StandardDbCaptureService`.
- A count of `4` for `service.properties` (two validation filters and two data
  utilities).
- The `cssFiles` line contains `custom/DbCapture/dbCapture-v2026092003.css`,
  plus any values you wrote down in 1.8.
- `windchill which` prints a URL inside `custom\lib\DbCapture.jar`. If it
  prints nothing, the JAR is not on the class path; check that
  `xconfmanager -i` and `-p` succeeded.

### 5.12 Rebuild the JavaScript bundles

**Where:** Terminal A. `jsfrag_combine.xml` rebuilds `main.js` and
`windchill-all.js` from all fragments: PTC's own plus `dbNinja.jsfrag`. The
bundles are outputs; never edit them.

```bat
cd /d "%WT_HOME%\bin"
ant -f jsfrag_combine.xml combine_jsfrag_files compress
echo exit=%ERRORLEVEL%
cd /d "%WT_HOME%\codebase\netmarkets\javascript\util"
findstr /m /l installDbNinja main.js windchill-all-debug.js windchill-all.js
```

**Expected result:** `BUILD SUCCESSFUL`, `exit=0`, and the three file names
`main.js`, `windchill-all-debug.js` and `windchill-all.js`.

### 5.13 Create the database tables: choose the route

DB Ninja stores its captures in 4 tables. They must exist before Windchill
starts the DB Ninja service. Windchill is still stopped. See also
[the Oracle schema notes](sql/oracle/README.md).

| Your server | Route |
|---|---|
| Exactly the qualified release (1.4), `wt.db.maxBytesPerChar=3` (1.8) and `INDX` present (3.1) | **Route A**: the bundled, guarded script `sql\oracle\create-db-ninja.sql` |
| Another approved CPS level, or `maxBytesPerChar` is not 3 | **Route B**: generate the DDL on your server with PTC's `tools.xml sql_script` |
| No `INDX` tablespace | Stop. The DBA and a senior engineer decide how to continue. |

**Route A. Where:** Terminal B. Check the bundled script against your server's
profile. This does not connect to the database.

```bat
cd /d "%DBN_HOME%"
node tools\schema-package.mjs verify --target
echo exit=%ERRORLEVEL%
```

**Expected result:** `PASS: matching ... prebuilt target and
declared/propagated wt.db.maxBytesPerChar=3.`, then
`PASS: four module tables ...` and `exit=0`. If this fails, use Route B. Never
edit checksums or settings to force a pass.

**Route B. Where:** Terminal A. Generate the DDL files with PTC's generator
(this does not touch the database). `gen.classpath_add` is needed because the
generator does not read `custom\lib`.

```bat
cd /d "%WT_HOME%"
ant -f bin\tools.xml sql_script "-Dgen.input=com.custom.dbcapture.*" "-Dgen.classpath_add=%WT_HOME%\custom\lib\DbCapture.jar"
echo exit=%ERRORLEVEL%
dir /s /b "%WT_HOME%\db\create_DbCapture*.sql"
```

**Expected result:** `exit=0` and 8 files in the Oracle folder that matches
your `wt.db.maxBytesPerChar`; for 3 this is normally
`db\sql3\com\custom\dbcapture`. Files in other database folders are not used.

**Where:** Terminal B. Assemble a guarded, create-only script from that
folder. The assembler refuses destructive statements and never overwrites a
file:

```bat
cd /d "%DBN_HOME%"
node tools\create-schema.mjs "%WT_HOME%\db\sql3\com\custom\dbcapture" "%DBN_WORK%\create-db-ninja.target.sql"
echo exit=%ERRORLEVEL%
```

**Expected result:** `exit=0`. The DBA reviews the result, including the
tablespaces it names, and checks that the session's `NLS_LENGTH_SEMANTICS` is
`BYTE`. Use this file instead of the bundled script in 5.14.

### 5.14 Run the script as the Windchill database user

**Where:** Terminal B, SQL*Plus as the Windchill database user. SQL*Plus does
not expand Command Prompt variables, so print the full paths first, then
connect:

```bat
echo Route A: %DBN_HOME%\sql\oracle\create-db-ninja.sql
echo Route B: %DBN_WORK%\create-db-ninja.target.sql
cd /d "%DBN_WORK%"
sqlplus -L %WC_CONNECT%
```

At the `SQL>` prompt, run the script of your route **once**, using the path
printed above:

```sql
SPOOL create-db-ninja.log
@<FULL_PATH_PRINTED_ABOVE>
SPOOL OFF
```

**Expected result:** `PL/SQL procedure successfully completed.` (the safety
check), then `Table created.` 4 times, `Comment created.` 4 times and
`Index created.` 14 times.

> [!CAUTION]
> **Stop if** you see any `ORA-` error. SQL*Plus exits immediately, and tables
> created before the error stay, because DDL commits. Do not run the script
> again and do not drop anything. Give `%DBN_WORK%\create-db-ninja.log` to the
> DBA; see [Appendix B](#appendix-b-troubleshooting).

### 5.15 Check the tables

**Where:** SQL*Plus as the Windchill database user, in the same session:

```sql
SELECT COUNT(*) AS tables_ok FROM user_tables
 WHERE table_name IN ('DBCAPTURESESSION','DBCAPTURECHANGE','DBCAPTUREATTRDELTA','DBCAPTURETABLECHANGE');
SELECT COUNT(*) AS pk_ok FROM user_constraints
 WHERE constraint_type = 'P' AND status = 'ENABLED' AND validated = 'VALIDATED'
   AND table_name IN ('DBCAPTURESESSION','DBCAPTURECHANGE','DBCAPTUREATTRDELTA','DBCAPTURETABLECHANGE');
SELECT index_type, status, COUNT(*) AS n FROM user_indexes
 WHERE table_name IN ('DBCAPTURESESSION','DBCAPTURECHANGE','DBCAPTUREATTRDELTA','DBCAPTURETABLECHANGE')
 GROUP BY index_type, status ORDER BY 1;
EXIT
```

**Expected result:** `TABLES_OK` = 4 and `PK_OK` = 4; `NORMAL VALID 18`
(4 primary-key indexes and 14 secondary indexes, in `INDX` for Route A);
`LOB VALID 3` (Oracle creates one per CLOB column; expected).

## Phase 6. Start Windchill and verify

### 6.1 Start Windchill and check the log

**Where:** Terminal A.

```bat
windchill start
```

When the Windchill login page loads (after a few minutes), check the newest
MethodServer log:

```bat
for /f "delims=" %f in ('dir /b /o:d "%WT_HOME%\logs\MethodServer-*-log4j.log"') do @set "MSLOG=%WT_HOME%\logs\%f"
echo %MSLOG%
findstr /l /c:"ClassNotFoundException" /c:"NoClassDefFoundError" /c:"InfoNotFoundException" /c:"ORA-00942" "%MSLOG%"
```

**Expected result:** no lines about `dbcapture`. The DB Ninja service starts
silently.

### 6.2 Test in the browser as a site administrator

**Where:** browser.

1. Log in as a site administrator and press
   <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>R</kbd> once to load the new
   JavaScript bundle.
2. Open `https://<WINDCHILL_HOST>/Windchill/netmarkets/jsp/dbcapture/dbCaptureState.jsp`
   without any `?op=` parameter. You get JSON with `"ok":true`,
   `"stateKnown":true`, `"running":false`, `"administrator":true` and
   `"canStart":true`.
3. The **Quick Links** menu shows **Ninja Trick** (enabled) and **Ninja
   Stealth** (greyed out while nothing runs). The header height is unchanged.
4. **Site** has a new **DB Ninja** tab next to **Utilities**. It opens an empty
   capture list.
5. Click **Ninja Trick**, then **Cancel**. Nothing starts: the state URL still
   shows `"running":false`.

> [!TIP]
> **Alert "DB Ninja controls are unavailable":** the browser still uses the old
> cached `windchill-all.js`. Close the alert and press
> <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>R</kbd>.

### 6.3 Test as an ordinary user

**Where:** browser. In a private browser window, log in as a user who is not
an administrator. The state URL shows `"administrator":false` and
`"canStart":false`, and the user cannot open DB Ninja or start a capture. If
you have no such user, write down "not tested".

### 6.4 First capture on your own test data

**Where:** browser.

1. Preparation (not captured): create a test library, for example
   `DBN Training Library`.
2. **Quick Links > Ninja Trick**, confirm, and enter the description
   `Training: create DBN-TEST-0001`. Wait for the Ninja banner in the header.
3. In the test library, create one document named `DBN-TEST-0001`, without
   content.
4. **Quick Links > Ninja Stealth**, then confirm. Collecting can take a while;
   do not click again. The result opens automatically.
5. Read **Mode**, **Status** and **Warnings** first. Then look at the changed
   tables: you should see new rows in document tables such as `WTDOCUMENT` and
   `WTDOCUMENTMASTER`.
6. Clean up: delete the test document. Delete the capture with DB Ninja's
   **Delete** action if you do not need it.

### 6.5 Check the private evidence folder

**Where:** Terminal B. After the first capture, DB Ninja has created its
private evidence folder `.dbcapture-evidence` in the Windchill folder. Only the
Windchill account may use it:

```bat
icacls "%WT_HOME%\.dbcapture-evidence"
```

**Expected result:** only the Windchill account, `SYSTEM` and `Administrators`
are listed.

> [!IMPORTANT]
> **Deployment accepted when** the log is clean, the state URL returns
> `"ok":true`, the menu items and Cancel work, the ordinary user is refused,
> and the test capture showed the new rows. Tell users to press
> <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>R</kbd> once, and keep the work folder
> with its logs and the backup. Record which checks you ran and which you
> skipped; do not report a skipped check as passed.

## Appendix A. Roll back

For the first installation, in the same maintenance window. Use this when the
tests in Phase 6 fail and there is no time to fix the problem. It restores the
files and the configuration from 4.2. Run the blocks in order and stop at the
first `STOP` or failure.

### A.1 Stop Windchill

**Where:** Terminal A.

```bat
windchill stop
```

### A.2 Check the backup

**Where:** Terminal B.

```bat
type "%DBN_BACKUP%\manifest.txt" | find /c /v ""
dir /s /b /a-d "%DBN_BACKUP%\files" | find /c /v ""
```

> [!CAUTION]
> **Stop if** the two numbers differ, or they are 0. The backup is incomplete:
> change nothing and ask a senior engineer.

### A.3 Remove the 28 files and restore the backup

**Where:** Terminal B.

```bat
for /f "usebackq delims=" %f in ("%DBN_LIST%") do @del /f /q "%WT_HOME%\%f" "%SM%\%f" 2>nul
robocopy "%DBN_BACKUP%\files" "%WT_HOME%" /E /R:0 /NP /LOG:"%DBN_WORK%\rollback.log"
if errorlevel 8 (echo STOP: robocopy failed) else (echo restore OK)
```

Only if you changed `CLASSINFO_DIR` to `custom\ser` in 4.5, also run:

```bat
del /f /q "%WT_HOME%\custom\ser\com\custom\dbcapture\DbCapture*.ClassInfo.ser"
```

**Expected result:** `restore OK`. (`robocopy` exit codes 0 to 7 mean success,
8 or more mean failure; details are in `%DBN_WORK%\rollback.log`.)

### A.4 Regenerate the configuration and JavaScript, then start

**Where:** Terminal A. `-F` forces a full propagation, which is needed because
the restored files have older time stamps.

```bat
cd /d "%WT_HOME%"
xconfmanager -p -F
echo exit=%ERRORLEVEL%
cd /d "%WT_HOME%\bin"
ant -f jsfrag_combine.xml combine_jsfrag_files compress
echo exit=%ERRORLEVEL%
cd /d "%WT_HOME%"
xconfmanager -d wt.services.service.905000
```

**Expected result:** two times `exit=0`, then
`No information available for property.`

```bat
windchill start
```

> [!NOTE]
> **Database:** the 4 tables do no harm once the code has been removed. Keep
> them unless the environment owner approves removal; then the DBA drops them.
> If they are kept, a later re-installation skips 5.13 to 5.15. The DBA may
> revoke only the grants that 3.2 added.

> [!WARNING]
> **Later uninstall, or after a CPS:** this roll back is only valid in the same
> maintenance window as the installation. After other changes, or after a CPS,
> never restore an old backup. After a CPS: take a new backup (4.2), recompile
> (4.3 to 4.5), restage the JAR and ClassInfo (5.4), reinstall (5.7 to 5.9),
> rebuild JavaScript (5.12) and repeat Phase 6. See [INSTALL.md](INSTALL.md)
> and ask a senior engineer for a later uninstall.

## Appendix B. Troubleshooting

Start with the output of the step that failed and with the newest
`MethodServer-*-log4j.log` in `%WT_HOME%\logs` (6.1 shows how to find it).

| Symptom | Likely cause | Fix |
|---|---|---|
| `'ant'`, `'xconfmanager'` or `'windchill' is not recognized ...` | Run outside the Windchill shell | Run it in Terminal A (1.1 and 1.3). |
| `'node'` or `'sqlplus' is not recognized ...` | Node.js or the Oracle client is not installed, or not on the `PATH` | Install Node.js 22 LTS or ask the DBA for SQL*Plus, then open Terminal B again. |
| A DB Ninja helper (`node tools\...`, `ConfigurationFiles.java`) fails with `accessExternalDTD` or `-Werror` | It was run in Terminal A | Run it in Terminal B, which has no `CLASSPATH`. |
| 2.3: `File Not Found` for `tools\dbninja.mjs` | Extracted into an extra folder, or the `main` branch was downloaded | Check the ZIP name (2.2) and extract again with the correct destination (2.3). |
| 2.4: `ERROR: Set WT_HOME and JAVA_HOME explicitly ...` | The settings are not loaded in Terminal B | Run the last block of 1.2 again. |
| 2.4: `ERROR: This port requires the Windchill 12.1 javax.servlet API.` | The server is not Windchill 12.1 | Stop (0.1). |
| 2.4: `ERROR: Service slot 905000 is already owned ...` | Another customization uses slot 905000 | Stop and ask a senior engineer. |
| 4.3: CCD compile fails | Wrong JDK, not in the Windchill shell, wrong path, or missing quotes | Read `%WT_HOME%\buildlogs\customizationLogs\customizationInstallLogs_*.log`. Check `javac -version`. Rerun exactly `clean validate.folder.structure compile`. |
| 4.5: no folder with 7 new ClassInfo files | The compile did not finish | Rerun 4.3. Never reuse ClassInfo files from another system. |
| An `xconfmanager` command returns a non-zero exit | Wrong file path, a damaged xconf file, or a quoting error | Read the message and rerun it with `-v`. Never edit generated properties to work around it. |
| The MethodServer does not start, or `ClassNotFoundException ...dbcapture...` | The JAR is not on the Windchill class path | Run `windchill which com/custom/dbcapture/StandardDbCaptureService.class`. Check that `xconfmanager -i` and `-p` succeeded (5.10). Restart. |
| `InfoNotFoundException` for a DbCapture class | A ClassInfo file is missing | All 7 must be installed (5.9), then restart. |
| `ORA-00942` for `DBCAPTURESESSION` | The tables are missing or in another schema | Do 5.13 to 5.15 as the correct user, then restart. |
| No DB Ninja tab and no Ninja menu items | Not a site administrator, configuration not propagated, no restart, or an old browser cache | Check 5.11, restart Windchill, press <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>R</kbd>. |
| Many unrelated pages fail after the restart (MVC errors) | `DbCapture-configs.xml` is damaged | Compare it with the package copy, then repeat 5.3, 5.4 and 5.7 to 5.9. Restart. |
| DB Ninja page without styling | CSS not registered | Check the `cssFiles` line (5.11). |
| HTTP 500 on a `netmarkets/jsp/dbcapture/*.jsp` page | Classes not on the class path, or the package does not match the release | Fix the cause. Then, with Windchill stopped, find Tomcat's compiled copies with `dir /s /b /ad "%WT_HOME%\tomcat" \| findstr /i /e /l "\org\apache\jsp\netmarkets\jsp\dbcapture"`, delete only those folders with `rmdir /s /q "<folder>"`, and start again. |
| Ninja Trick fails with `ORA-01031`, `PLS-00201` or `ORA-00942` (V$DATABASE) | Missing grants | 3.2. Direct grants apply immediately. |
| Ninja Trick fails with `ORA-20000` from `DBMS_STATS` | `ANALYZE ANY` is missing | 3.2, with DBA approval. |
| A capture reports `ORA-01555` or `ORA-30052` | Oracle's undo history for that time window has expired | Keep captures short and ask the DBA. This is not an installation error. |
| The create script stops with `ORA-20001` | DB Ninja objects already exist | Stop. Never drop objects to retry. Ask the DBA and a senior engineer. |
| ... with `ORA-20002` | Wrong session: SYS or SYSTEM, or a changed `CURRENT_SCHEMA` | Reconnect directly as the Windchill user. |
| ... with `ORA-20003`, `ORA-01950` or `ORA-01536` | `INDX` is missing, or there is no quota | The DBA fixes the tablespace or quota and decides about the partly created objects. |
| Errors that mention `.dbcapture-evidence` | The evidence folder is not on local NTFS, is a junction or link, or is not owned by the Windchill account | Fix the folder so that it meets these rules. Never loosen its permissions. |

DB Ninja is community software under the MIT license. It is not a PTC product
and is not certified by PTC. This guide uses only PTC's documented, additive
customization extension points. See [README.md](README.md) for the project
overview and [WINDOWS-PORTING.md](WINDOWS-PORTING.md) for other Windchill
releases.

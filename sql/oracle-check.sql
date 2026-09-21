-- Run as the actual Windchill schema in a NEW SQL*Plus session.
-- No DDL, DML, GRANT, parameter changes or monitoring-statistics flush.
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET ECHO ON
SELECT SYS_CONTEXT('USERENV','SESSION_USER') AS session_user,
       SYS_CONTEXT('USERENV','DB_NAME') AS db_name,
       SYS_CONTEXT('USERENV','CON_NAME') AS container_name FROM dual;
SELECT DBMS_FLASHBACK.GET_SYSTEM_CHANGE_NUMBER AS current_scn FROM dual;
SELECT current_scn FROM v$database;
SELECT name, value FROM v$parameter WHERE name IN ('undo_retention', 'undo_tablespace');
SELECT COUNT(*) AS readable_undo_samples FROM v$undostat;
SELECT COUNT(*) AS readable_monitoring_rows FROM user_tab_modifications;
SELECT table_name FROM user_tables
 WHERE table_name IN ('DBCAPTURESESSION', 'DBCAPTURECHANGE',
                     'DBCAPTUREATTRDELTA', 'DBCAPTURETABLECHANGE')
 ORDER BY table_name;
ROLLBACK;
EXIT;

-- Run as the actual Windchill schema in a NEW SQL*Plus session.
-- No DDL, DML, GRANT, parameter changes or monitoring-statistics flush.
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET ECHO ON
SELECT SYS_CONTEXT('USERENV','SESSION_USER') AS session_user,
       SYS_CONTEXT('USERENV','CURRENT_SCHEMA') AS current_schema,
       SYS_CONTEXT('USERENV','DB_NAME') AS db_name,
       SYS_CONTEXT('USERENV','CON_NAME') AS container_name FROM dual;
SELECT privilege FROM session_privs
 WHERE privilege IN ('CREATE TABLE', 'ANALYZE ANY', 'FLASHBACK ANY TABLE', 'UNLIMITED TABLESPACE')
 ORDER BY privilege;
SELECT default_tablespace FROM user_users;
SELECT tablespace_name, bytes, max_bytes FROM user_ts_quotas ORDER BY tablespace_name;
SELECT tablespace_name, status, contents FROM user_tablespaces
 WHERE tablespace_name = 'INDX';
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

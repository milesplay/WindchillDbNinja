-- First installation only. Assembled from reviewed module CREATE scripts.
-- Use only the matching Windchill/Oracle/byte-width profile. Development and test only.
-- Review tablespaces, byte widths and all statements with the DBA.
-- Run as the Windchill schema. Oracle DDL commits; partial failure is not rollback-safe.
-- Use a fresh schema-owner session with no pending work; never run as SYS or SYSTEM.
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET DEFINE OFF
SET ECHO ON
DECLARE
   existing_count NUMBER;
   constraint_count NUMBER;
   tablespace_count NUMBER;
BEGIN
   IF SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') <> SYS_CONTEXT('USERENV', 'SESSION_USER')
      OR SYS_CONTEXT('USERENV', 'SESSION_USER') IN ('SYS', 'SYSTEM') THEN
      RAISE_APPLICATION_ERROR(-20002, 'Use a fresh connection as the actual Windchill schema owner without CURRENT_SCHEMA changes.');
   END IF;
   SELECT COUNT(*) INTO existing_count FROM user_objects WHERE object_name IN ('DBCAPTUREATTRDELTA', 'DBCAPTUREATTRDELTA$COMPOSITE0', 'DBCAPTUREATTRDELTA$COMPOSITE1', 'DBCAPTUREATTRDELTA$COMPOSITE2', 'DBCAPTUREATTRDELTA$COMPOSITE3', 'DBCAPTURECHANGE', 'DBCAPTURECHANGE$COMPOSITE0', 'DBCAPTURECHANGE$COMPOSITE1', 'DBCAPTURECHANGE$COMPOSITE2', 'DBCAPTURECHANGE$COMPOSITE3', 'DBCAPTURESESSION', 'DBCAPTURESESSION$COMPOSITE0', 'DBCAPTURESESSION$COMPOSITE1', 'DBCAPTURESESSION$COMPOSITE2', 'DBCAPTURETABLECHANGE', 'DBCAPTURETABLECHANGE$COMPOSI0', 'DBCAPTURETABLECHANGE$COMPOSI1', 'DBCAPTURETABLECHANGE$COMPOSI2', 'PK_DBCAPTUREATTRDELTA', 'PK_DBCAPTURECHANGE', 'PK_DBCAPTURESESSION', 'PK_DBCAPTURETABLECHANGE');
   SELECT COUNT(*) INTO constraint_count FROM user_constraints WHERE constraint_name IN ('PK_DBCAPTUREATTRDELTA', 'PK_DBCAPTURECHANGE', 'PK_DBCAPTURESESSION', 'PK_DBCAPTURETABLECHANGE');
   IF existing_count <> 0 OR constraint_count <> 0 THEN
      RAISE_APPLICATION_ERROR(-20001, 'DB Ninja object or constraint names already exist. Do not reinstall/reset; review a migration instead.');
   END IF;
   SELECT COUNT(*) INTO tablespace_count FROM user_tablespaces
      WHERE tablespace_name IN ('INDX') AND status = 'ONLINE' AND contents = 'PERMANENT';
   IF tablespace_count <> 1 THEN
      RAISE_APPLICATION_ERROR(-20003, 'Required index/data tablespace is unavailable. Ask the DBA to review the DDL profile.');
   END IF;
END;
/
-- Source: create_DbCaptureAttrDelta_Table.sql
CREATE TABLE DbCaptureAttrDelta (
   actionName   VARCHAR2(1200 BYTE),
   attributeName   VARCHAR2(600 BYTE),
   captureId   VARCHAR2(120 BYTE),
   classnamekeyA3   VARCHAR2(600 BYTE),
   idA3A3   NUMBER,
   changeScn   NUMBER,
   changeTime   DATE,
   changedBy   VARCHAR2(600 BYTE),
   className   VARCHAR2(600 BYTE),
   columnName   VARCHAR2(120 BYTE) NOT NULL,
   newValue   VARCHAR2(4000 BYTE),
   objectIdentity   VARCHAR2(1200 BYTE),
   oldValue   VARCHAR2(4000 BYTE),
   operation   VARCHAR2(60 BYTE),
   requestUri   VARCHAR2(1200 BYTE),
   tableName   VARCHAR2(120 BYTE),
   targetRowId   NUMBER,
   createStampA2   DATE,
   markForDeleteA2   NUMBER NOT NULL,
   modifyStampA2   DATE,
   classnameA2A2   VARCHAR2(600 BYTE),
   idA2A2   NUMBER NOT NULL,
   updateCountA2   NUMBER,
   updateStampA2   DATE,
   transactionId   VARCHAR2(120 BYTE),
   truncated   NUMBER(1),
 CONSTRAINT PK_DbCaptureAttrDelta PRIMARY KEY (idA2A2))
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 )
ENABLE PRIMARY KEY USING INDEX
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );
COMMENT ON TABLE DbCaptureAttrDelta IS 'Table DbCaptureAttrDelta created for com.custom.dbcapture.DbCaptureAttrDelta';

-- Source: create_DbCaptureChange_Table.sql
CREATE TABLE DbCaptureChange (
   actionName   VARCHAR2(1200 BYTE),
   changeScn   NUMBER,
   changeTime   DATE,
   changedBy   VARCHAR2(600 BYTE),
   changedColumns   VARCHAR2(4000 BYTE),
   className   VARCHAR2(600 BYTE),
   note   VARCHAR2(4000 BYTE),
   objectIdentity   VARCHAR2(1200 BYTE),
   operation   VARCHAR2(60 BYTE) NOT NULL,
   requestUri   VARCHAR2(1200 BYTE),
   classnamekeyA3   VARCHAR2(600 BYTE),
   idA3A3   NUMBER,
   stackTrace   CLOB,
   tableName   VARCHAR2(120 BYTE) NOT NULL,
   targetRowId   NUMBER,
   createStampA2   DATE,
   markForDeleteA2   NUMBER NOT NULL,
   modifyStampA2   DATE,
   classnameA2A2   VARCHAR2(600 BYTE),
   idA2A2   NUMBER NOT NULL,
   updateCountA2   NUMBER,
   updateStampA2   DATE,
   transactionId   VARCHAR2(120 BYTE),
   undoSql   CLOB,
 CONSTRAINT PK_DbCaptureChange PRIMARY KEY (idA2A2))
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 )
ENABLE PRIMARY KEY USING INDEX
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );
COMMENT ON TABLE DbCaptureChange IS 'Table DbCaptureChange created for com.custom.dbcapture.DbCaptureChange';

-- Source: create_DbCaptureSession_Table.sql
CREATE TABLE DbCaptureSession (
   captureId   VARCHAR2(120 BYTE) NOT NULL,
   captureMode   VARCHAR2(60 BYTE),
   createdCount   NUMBER,
   deletedCount   NUMBER,
   description   VARCHAR2(1200 BYTE),
   endScn   NUMBER,
   endTime   DATE,
   errorText   VARCHAR2(4000 BYTE),
   logicalDeletedCount   NUMBER,
   startScn   NUMBER,
   startTime   DATE,
   startedBy   VARCHAR2(600 BYTE),
   status   VARCHAR2(60 BYTE) NOT NULL,
   tablesChanged   NUMBER,
   createStampA2   DATE,
   markForDeleteA2   NUMBER NOT NULL,
   modifyStampA2   DATE,
   classnameA2A2   VARCHAR2(600 BYTE),
   idA2A2   NUMBER NOT NULL,
   updateCountA2   NUMBER,
   updateStampA2   DATE,
   updatedCount   NUMBER,
   warnings   VARCHAR2(4000 BYTE),
 CONSTRAINT PK_DbCaptureSession PRIMARY KEY (idA2A2))
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 )
ENABLE PRIMARY KEY USING INDEX
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );
COMMENT ON TABLE DbCaptureSession IS 'Table DbCaptureSession created for com.custom.dbcapture.DbCaptureSession';

-- Source: create_DbCaptureTableChange_Table.sql
CREATE TABLE DbCaptureTableChange (
   actionNames   VARCHAR2(4000 BYTE),
   captureId   VARCHAR2(120 BYTE),
   changedBy   VARCHAR2(1200 BYTE),
   className   VARCHAR2(600 BYTE),
   createdCount   NUMBER,
   deletedCount   NUMBER,
   details   CLOB,
   firstChangeTime   DATE,
   lastChangeTime   DATE,
   logicalDeletedCount   NUMBER,
   note   VARCHAR2(4000 BYTE),
   objectIdentities   VARCHAR2(4000 BYTE),
   operations   VARCHAR2(600 BYTE),
   rowsAffected   NUMBER,
   classnamekeyA3   VARCHAR2(600 BYTE),
   idA3A3   NUMBER,
   tableName   VARCHAR2(120 BYTE) NOT NULL,
   createStampA2   DATE,
   markForDeleteA2   NUMBER NOT NULL,
   modifyStampA2   DATE,
   classnameA2A2   VARCHAR2(600 BYTE),
   idA2A2   NUMBER NOT NULL,
   updateCountA2   NUMBER,
   updateStampA2   DATE,
   updatedCount   NUMBER,
 CONSTRAINT PK_DbCaptureTableChange PRIMARY KEY (idA2A2))
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 )
ENABLE PRIMARY KEY USING INDEX
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );
COMMENT ON TABLE DbCaptureTableChange IS 'Table DbCaptureTableChange created for com.custom.dbcapture.DbCaptureTableChange';

-- Source: create_DbCaptureAttrDelta_Index.sql
CREATE INDEX DbCaptureAttrDelta$COMPOSITE0 ON DbCaptureAttrDelta(idA3A3)
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );
CREATE INDEX DbCaptureAttrDelta$COMPOSITE1 ON DbCaptureAttrDelta(captureId)
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );
CREATE INDEX DbCaptureAttrDelta$COMPOSITE2 ON DbCaptureAttrDelta(captureId,tableName)
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );
CREATE INDEX DbCaptureAttrDelta$COMPOSITE3 ON DbCaptureAttrDelta(columnName)
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );

-- Source: create_DbCaptureChange_Index.sql
CREATE INDEX DbCaptureChange$COMPOSITE0 ON DbCaptureChange(idA3A3)
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );
CREATE INDEX DbCaptureChange$COMPOSITE1 ON DbCaptureChange(tableName)
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );
CREATE INDEX DbCaptureChange$COMPOSITE2 ON DbCaptureChange(idA3A3,tableName,targetRowId)
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );
CREATE INDEX DbCaptureChange$COMPOSITE3 ON DbCaptureChange(transactionId)
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );

-- Source: create_DbCaptureSession_Index.sql
CREATE INDEX DbCaptureSession$COMPOSITE0 ON DbCaptureSession(captureId)
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );
CREATE INDEX DbCaptureSession$COMPOSITE1 ON DbCaptureSession(status)
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );
CREATE INDEX DbCaptureSession$COMPOSITE2 ON DbCaptureSession(startTime)
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );

-- Source: create_DbCaptureTableChange_Index.sql
CREATE INDEX DbCaptureTableChange$COMPOSI0 ON DbCaptureTableChange(idA3A3)
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );
CREATE INDEX DbCaptureTableChange$COMPOSI1 ON DbCaptureTableChange(captureId)
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );
CREATE INDEX DbCaptureTableChange$COMPOSI2 ON DbCaptureTableChange(captureId,tableName)
 TABLESPACE INDX
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 );

SELECT table_name FROM user_tables WHERE table_name IN ('DBCAPTUREATTRDELTA', 'DBCAPTURECHANGE', 'DBCAPTURESESSION', 'DBCAPTURETABLECHANGE') ORDER BY table_name;
SELECT constraint_name, table_name, status, validated FROM user_constraints
   WHERE constraint_name IN ('PK_DBCAPTUREATTRDELTA', 'PK_DBCAPTURECHANGE', 'PK_DBCAPTURESESSION', 'PK_DBCAPTURETABLECHANGE') ORDER BY constraint_name;
SELECT index_name, table_name, status FROM user_indexes
   WHERE table_name IN ('DBCAPTUREATTRDELTA', 'DBCAPTURECHANGE', 'DBCAPTURESESSION', 'DBCAPTURETABLECHANGE') ORDER BY table_name, index_name;

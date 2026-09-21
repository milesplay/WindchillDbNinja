set echo on
REM Creating table DbCaptureChange for com.ptc.dbcapture.DbCaptureChange
set echo off
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
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 )
/
COMMENT ON TABLE DbCaptureChange IS 'Table DbCaptureChange created for com.ptc.dbcapture.DbCaptureChange'
/
REM @//com/ptc/dbcapture/DbCaptureChange_UserAdditions

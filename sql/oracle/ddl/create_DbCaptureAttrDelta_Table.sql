set echo on
REM Creating table DbCaptureAttrDelta for com.custom.dbcapture.DbCaptureAttrDelta
set echo off
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
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 )
/
COMMENT ON TABLE DbCaptureAttrDelta IS 'Table DbCaptureAttrDelta created for com.custom.dbcapture.DbCaptureAttrDelta'
/
REM @//com/custom/dbcapture/DbCaptureAttrDelta_UserAdditions

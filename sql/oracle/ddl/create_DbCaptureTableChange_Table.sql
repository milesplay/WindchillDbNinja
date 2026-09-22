set echo on
REM Creating table DbCaptureTableChange for com.custom.dbcapture.DbCaptureTableChange
set echo off
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
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 )
/
COMMENT ON TABLE DbCaptureTableChange IS 'Table DbCaptureTableChange created for com.custom.dbcapture.DbCaptureTableChange'
/
REM @//com/custom/dbcapture/DbCaptureTableChange_UserAdditions

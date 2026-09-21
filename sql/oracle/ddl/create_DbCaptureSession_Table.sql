set echo on
REM Creating table DbCaptureSession for com.ptc.dbcapture.DbCaptureSession
set echo off
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
 STORAGE ( INITIAL 20k NEXT 20k PCTINCREASE 0 )
/
COMMENT ON TABLE DbCaptureSession IS 'Table DbCaptureSession created for com.ptc.dbcapture.DbCaptureSession'
/
REM @//com/ptc/dbcapture/DbCaptureSession_UserAdditions

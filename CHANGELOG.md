# Changelog

## 0.3.0-wc1302-win1 - Windchill 13.0.2 Windows port candidate (not released)

- Create the separate `port/windows-wc1302` source line from the reviewed 12.1 Windows tag.
- Port active servlet references and target checks to Java 17 and Jakarta while
  preserving the `com.custom.dbcapture` persistence identity and Windows safety behavior.
- Verify Windchill 13.0.2.6 build 33 with Information Modeler 13.0.2.0 build
  396, the local NTFS volume, target XCONF DTD, service slot and installed
  profiler/Log4j/request APIs.
- After Information Modeler installation, target CCD generated a private JAR and
  seven matching ClassInfo files. The earlier failed compile's 24
  ClassInfo/configuration snapshots were checksum-verified; owner-confirmed
  MethodServer-count XCONF propagation was preserved, not restored over.
- Final CCD JAR: 281,066 bytes, SHA-256
  `741de4f629900a914450654a262b056191ba024c59e6a457444a823c2eb1ff9e`; refreshed
  source manifest and model baseline verified against target SDK/datecode.
- Pass 308 offline regression tests, 285 profiler assertions, 77 presentation
  assertions, target API linkage, JSP/web compilation, icons and source/candidate
  contract validation.
- Generate a private candidate manifest/model baseline verified against the
  13.0.2.6 SDK/datecode, and target sql3 DDL for 4 tables/4 PKs/14 indexes.
  Explicit BYTE semantics were added without changing 45 numeric VARCHAR2 widths;
  combined SQL SHA-256 is `03193942212e190a46a02983259191496e6e944312953bdd290d9fb0c457089a`.
- Apply the approved `SELECT ON SYS.V_$DATABASE` and `ANALYZE ANY` grants and
  target CREATE DDL; schema postchecks pass for four tables, four primary keys,
  18 normal indexes, three LOB indexes and 45 BYTE VARCHAR2 columns.
- Apply the private hash-bound 28-file deployment plan and later Wex XML-only
  menu repair. The user reports the menus fixed; an owner restart and
  post-restart acceptance after that repair are not recorded. A disposable
  capture, monitoring flush and multi-node/failover acceptance remain pending.

## 0.2.0-wc121-win1 - Windchill 12.1 Windows port

- Port the runtime and validation suite to Windchill 12.1.2.23, Java 11 and
  `javax.servlet` on Windows Server.
- Move custom persistent identities to `com.custom.dbcapture` for a confirmed
  fresh installation and regenerate the JAR, seven ClassInfo files and Oracle
  `sql3` DDL with the target SDK.
- Replace POSIX-only evidence storage checks with local-NTFS owner ACL,
  reparse-point and stable-identity validation.
- Require POST plus Windchill CSRF nonces for direct JSP mutations and use the
  installed container administrator API instead of localized group names.
- Add Windows-native build, package, schema, JSP, rollback and publication
  regression coverage, plus the reusable Windows porting guide for future
  Windchill 13.0.2 targets.
- Fix Windows metadata checkpoint replacement under concurrent reads with a
  guarded non-atomic replacement fallback and a large-metadata concurrency test.
- Verify the exact non-production target with a restarted MethodServer, a short
  empty capture, private NTFS evidence and a SELECT-only Oracle row check.
- Ordinary-user acceptance, multi-node/failover behavior and production-scale
  performance remain separate qualification work.

## 0.1.1 - Oracle schema and distribution patch

Windows x64 development/test preview, not a production release.

- Add the previously omitted DB Ninja CREATE TABLE/index DDL under
  [sql/oracle](sql/oracle/README.md): 4 tables, 4 primary keys, 14 secondary
  indexes and 4 comments, plus a guarded combined first-install script.
- Lock the bundled profile to the unchanged Windchill 12.1.2.23 generated model,
  Oracle 19c, `wt.db.maxBytesPerChar=3`, explicit VARCHAR2 BYTE semantics and
  the INDX index tablespace. Other profiles require target generation/DBA review.
- Refuse reserved table/index/constraint conflicts before the first CREATE,
  altered-current-schema or SYS/SYSTEM sessions, and unavailable explicit
  tablespaces. No automatic SQL execution, grants, repair or reset.
- Add offline schema integrity and read-only target width checks, first-install
  resource coverage, missing-DDL regressions, package/manifest version equality
  and a publication guard requiring every reviewed file in the Git index.
- Document both first-install schema paths, release ZIP verification and the
  distinction between offline checks and actual Oracle/runtime acceptance.
- Add an explicit [AI/DBA database setup runbook](DATABASE-SETUP.md) for approved
  privilege/quota changes, schema-owner reconnection, historical-query/monitoring
  verification, CREATE execution and table/key/index acceptance. The public
  grant template remains non-executing; no automatic database changes are added.

The runtime Java sources, custom JAR, seven ClassInfo files and generated-model
baseline are byte-identical to 0.1.0. The original compiler/build timestamp is
preserved. Existing installations do **not** run the CREATE script, and this
packaging-only update does not require a runtime redeployment or restart.

See [release downloads](https://github.com/milesplay/WindchillDbNinja/releases)
for the versioned full source/install ZIP and SHA256SUMS.
The ZIP includes the prebuilt package, source, schema, tools and English
documentation; it excludes private build output and licensed vendor libraries.

## 0.1.0 - Initial repository publication

- Publish custom source, compiled Windows x64 JAR/ClassInfo, deployment/validation
  tools and English documentation under MIT.
- Correct activity selection, frozen table scope, strict endpoint NET semantics
  and the confirmed deployment/publication guards.
- Include Ninja-only 16 x 16 Start/Stop icons and dynamic-menu binding.
- Provide schema-generation instructions and Oracle prerequisite checks, but
  not the generated CREATE scripts. Version 0.1.1 corrects that packaging gap.

The prior running-system icon deployment and offline full-release tests are
separate evidence. Read [the qualification record](LOCAL-INSTALL.md) before
making any claim about deployed collector behavior.

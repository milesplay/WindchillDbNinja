package com.custom.dbcapture.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Read-only audit assertions against installed shipped Oracle DDL, not table-name guesses. */
public final class MonitoringScopeDdlTest {
    private static int assertions;
    private static final Pattern ROW_ID = Pattern.compile("(?im)^\\s*idA2A2\\s+");

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: MonitoringScopeDdlTest <installed-wt-home>");
        Path home = Path.of(args[0]);
        Map<String, Boolean> facts = new TreeMap<>();
        for (String name : MonitoringScope.defaultExcludedTables()) {
            Path source = home.resolve(MonitoringScope.defaultSource(name));
            check(Files.isRegularFile(source), "shipped evidence exists for " + name + ": " + source);
            String ddl = Files.readString(source);
            check(Pattern.compile("(?i)\\bcreate\\s+table\\s+" + Pattern.quote(name) + "\\s*\\(")
                    .matcher(ddl).find(), "default is an exact physical table in its cited DDL: " + name);
            facts.put(name, ROW_ID.matcher(ddl).find());
        }
        String install = Files.readString(home.resolve(
                "db/sql3/wnc/Foundation/nonmodeled/tables/Make_nonmodeled_Foundation_tables.sql"));
        int nonmodeledOperational = 0;
        for (String line : install.lines().collect(Collectors.toList())) {
            if (!line.startsWith("@wnc/Foundation/nonmodeled/tables/")
                    || line.contains("/Drop_") || line.contains("WCTK_") || line.contains("CURR_CNT_TABLE")) continue;
            String name = Path.of(line.substring(1)).getFileName().toString().replace(".sql", "")
                    .toUpperCase(java.util.Locale.ROOT);
            check(MonitoringScope.defaultExcludedTables().contains(name), "every shipped Foundation monitoring table audited: " + name);
            check(!facts.get(name), "nonmodeled monitoring has no Windchill IDA2A2: " + name);
            nonmodeledOperational++;
        }
        check(nonmodeledOperational == 25, "all 25 Foundation telemetry/registry/handshake tables covered");

        TableFilter defaults = new TableFilter(new Properties(), "");
        MonitoringScope.Catalog catalog = new MonitoringScope.Catalog(facts);
        MonitoringScope.Selection selection = MonitoringScope.describe(new Properties(), "", catalog);
        check(selection.getIncluded().isEmpty(), "defaults never pre-enable an operational table");
        check(facts.size() == 47 && selection.getExcluded().size() == 20 && selection.getLocked().size() == 27,
                "47 grounded defaults: 20 capturable, 27 no-ID locked in shipped DDL");
        check(Boolean.TRUE.equals(facts.get("PREFERENCEINSTANCE")) && selection.getExcluded().contains("PREFERENCEINSTANCE")
                && !selection.getLocked().contains("PREFERENCEINSTANCE"),
                "PreferenceInstance DDL has IDA2A2 and supports a movable default");
        Properties preferenceOptIn = new Properties();
        preferenceOptIn.setProperty("includedTables", "PREFERENCEINSTANCE");
        check(catalog.includedTables(new TableFilter(preferenceOptIn, "")).equals(List.of("PREFERENCEINSTANCE")),
                "preference opt-in admits the grounded physical table");
        check(catalog.includedTables(defaults).isEmpty(), "every audited default excluded");
        for (String table : List.of("WorkflowVariableEventInfo", "CheckInEventInfo", "UserSessionEventInfo")) {
            retained(home, defaults, "db/sql3/wnc/Foundation/wt/audit/eventinfo/create_" + table + "_Table.sql", table);
        }
        for (String table : List.of("WfStateEventAudit", "WfVotingEventAudit", "WfAssignmentEventAudit", "WfProcess")) {
            retained(home, defaults, "db/sql3/wnc/Foundation/wt/workflow/engine/create_" + table + "_Table.sql", table);
        }
        retained(home, defaults,
                "db/sql3/wnc/Foundation/wt/workflow/work/create_WfAssignedActivity_Table.sql", "WfAssignedActivity");
        for (String table : List.of("TaskEvent", "TaskEventData", "TaskEventMessage", "TaskEventAffectedObjectsLink")) {
            retained(home, defaults, "db/sql3/wnc/CoreTask/com/ptc/core/task/create_" + table + "_Table.sql", table);
        }
        for (String table : List.of("ClientCacheState", "EPMUpdateCounter")) {
            retained(home, defaults, "db/sql3/wnc/Foundation/wt/epm/workspaces/create_" + table + "_Table.sql", table);
        }
        retained(home, defaults, "db/sql3/wnc/Foundation/wt/recent/create_RecentUpdate_Table.sql", "RecentUpdate");
        for (String table : List.of("IndexStatus", "BulkIndexListEntry", "IndexPolicy")) {
            retained(home, defaults, "db/sql3/wnc/Foundation/wt/index/create_" + table + "_Table.sql", table);
        }
        retained(home, defaults, "db/sql3/wnc/Foundation/wt/federation/create_FederationLock_Table.sql", "FederationLock");
        for (String table : List.of("DigestNotification", "DigestNotificationData")) {
            retained(home, defaults, "db/sql3/wnc/Foundation/wt/notify/templateProcessor/create_" + table + "_Table.sql", table);
        }
        retained(home, defaults, "db/sql3/pdml/DDLBasic/com/ptc/wpcfg/family/create_EngineState_Table.sql", "EngineState");
        retained(home, defaults, "db/sql3/wnc/Foundation/wt/fv/create_FvServicePersistentFlags_Table.sql", "FvServicePersistentFlags");
        retained(home, defaults, "db/sql3/wnc/Foundation/wt/fv/create_VaultCleanupAuditLogs_Table.sql", "VaultCleanupAuditLogs");
        retained(home, defaults, "db/sql3/wnc/Auditing/com/ptc/core/auditing/create_AuditLogPurgeScheduler_Table.sql", "AuditLogPurgeScheduler");
        retained(home, defaults, "db/sql3/wnc/DataOps/wt/dataops/purge/create_PurgeSchedule_Table.sql", "PurgeSchedule");
        retained(home, defaults, "db/sql3/wnc/Replication/wt/dataops/replication/create_ContentReplSchedule_Table.sql", "ContentReplSchedule");
        System.out.println("PASS: " + assertions + " shipped-DDL audit assertions; "
                + facts.size() + " exact capture defaults (20 movable, 27 locked without IDA2A2).");
    }

    private static void retained(Path home, TableFilter filter, String source, String name) throws Exception {
        String ddl = Files.readString(home.resolve(source));
        check(ROW_ID.matcher(ddl).find(), "retained business/audit table has row identity: " + name);
        check(filter.isIncluded(name) && MonitoringScope.defaultReason(name) == null,
                "retain user-action/business/audit state despite suggestive name: " + name);
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        assertions++;
    }
}

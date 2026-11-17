package com.workbuddy.pbccrc.dao;

import com.workbuddy.pbccrc.common.BizException;
import com.workbuddy.pbccrc.common.ResultCode;
import com.workbuddy.pbccrc.util.SqlColumnUtil;
import org.apache.ibatis.jdbc.SQL;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 明细表通用动态 SQL 提供者。
 *
 * <p>表名走白名单校验，杜绝 SQL 注入；插入列由实体反射生成。供 {@link DetailMapper}
 * 的 Provider 注解调用。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public class DetailSqlProvider {

    private static final Set<String> ALLOWED_TABLES = Set.of(
            "t_pbccrc_credit_record",
            "t_pbccrc_credit_card_record",
            "t_pbccrc_loan_record",
            "t_pbccrc_query_record",
            "t_pbccrc_asset_disposal",
            "t_pbccrc_compensate",
            "t_pbccrc_guarantee",
            "t_pbccrc_admin_punishment",
            "t_pbccrc_ccivil_decision",
            "t_pbccrc_court_decision",
            "t_pbccrc_tax_arrears",
            "t_pbccrc_telecom_owe"
    );

    /**
     * 生成按报告主键删除明细的 SQL。
     *
     * @param args 参数映射，含 {@code table} 与 {@code reportId}
     * @return DELETE SQL
     */
    public String deleteByReportId(Map<String, Object> args) {
        String table = table(args);
        return new SQL().DELETE_FROM(table).WHERE("report_id = #{reportId}").toString();
    }

    /**
     * 生成批量插入明细的 SQL。
     *
     * @param args 参数映射，含 {@code table} 与 {@code list}
     * @return INSERT SQL
     * @throws BizException 明细列表为空时抛出
     */
    public String insertBatch(Map<String, Object> args) {
        String table = table(args);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) args.get("list");
        if (list == null || list.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "空明细列表无需写入");
        }
        List<Field> fields = SqlColumnUtil.fields(list.get(0).getClass());

        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(SqlColumnUtil.toColumn(fields.get(i).getName()));
        }
        sb.append(") VALUES ");
        for (int row = 0; row < list.size(); row++) {
            if (row > 0) {
                sb.append(',');
            }
            sb.append('(');
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("#{list[").append(row).append("].").append(fields.get(i).getName()).append('}');
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /**
     * 校验并返回白名单内的表名。
     *
     * @param args 参数映射，含 {@code table}
     * @return 合法表名
     * @throws BizException 表名不在白名单内时抛出
     */
    private String table(Map<String, Object> args) {
        String table = String.valueOf(args.get("table"));
        if (!ALLOWED_TABLES.contains(table)) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "非法明细表名: " + table);
        }
        return table;
    }
}

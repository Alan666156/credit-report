package com.workbuddy.pbccrc.engine;

import com.workbuddy.pbccrc.model.dto.RuleRow;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 测试夹具：从 classpath 读真实配置规则（type / name / value / value_index）。 */
public final class RuleFixtures {

    private RuleFixtures() {
    }

    public static List<RuleRow> load() {
        List<RuleRow> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(RuleFixtures.class.getResourceAsStream("/rules.tsv")),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split("\t", -1);
                RuleRow row = new RuleRow();
                row.setType(p[0]);
                row.setName(p[1]);
                row.setValue(p[2]);
                row.setValueIndex(p.length > 3 ? p[3] : "1:1");
                rows.add(row);
            }
        } catch (Exception e) {
            throw new IllegalStateException("规则夹具读取失败", e);
        }
        return rows;
    }
}

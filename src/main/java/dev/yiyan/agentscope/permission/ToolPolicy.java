package dev.yiyan.agentscope.permission;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;

public final class ToolPolicy {
    private ToolPolicy() {}

    public static PermissionContextState create(boolean savingDisabled) {
        var builder = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addAllowRule("product_info", new PermissionRule(
                        "product_info", null, PermissionBehavior.ALLOW, "application"))
                .addAskRule("save_shortlist", new PermissionRule(
                        "save_shortlist", null, PermissionBehavior.ASK, "application"));
        if (savingDisabled) {
            builder.addDenyRule("save_shortlist", new PermissionRule(
                    "save_shortlist", null, PermissionBehavior.DENY, "maintenance"));
        }
        return builder.build();
    }
}

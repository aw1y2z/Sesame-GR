package io.github.aw1y2z.sesame.model.task.goldenbeans;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import io.github.aw1y2z.sesame.data.ConfigV2;
import io.github.aw1y2z.sesame.data.ModelFields;
import io.github.aw1y2z.sesame.data.modelFieldExt.SelectModelField;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.MessageUtil;
import io.github.aw1y2z.sesame.util.idMap.GoldenBeansTaskListMap;
import io.github.aw1y2z.sesame.util.idMap.UserIdMap;

/**
 * 金豆夺宝的入口日常与任务列表处理。
 * <p>
 * 每个入口独立执行：主页查询 → 每日签到 → 营销弹窗 → 任务列表。
 * 任务列表只处理 sceneCode 与本入口一致的任务；需真实付款、换豆承接类任务直接跳过；
 * 其余 TODO 任务走服务端完成契约，失败且命中不可重试错误时自动加入黑名单。
 */
public final class GoldenBeansTasks {

    /** 任务状态：已完成待领取 */
    private static final String STATUS_FINISHED = "FINISHED";
    /** 任务状态：已完成待领取（服务端另一套命名） */
    private static final String STATUS_TO_RECEIVE = "TO_RECEIVE";
    /** 任务状态：已领取 */
    private static final String STATUS_RECEIVED = "RECEIVED";
    /** 任务状态：已结束 */
    private static final String STATUS_DONE = "DONE";
    /** 任务状态：待完成 */
    private static final String STATUS_TODO = "TODO";

    private final SelectModelField blacklist;
    private final boolean autoBlacklist;

    /**
     * @param blacklist     黑名单配置字段，可为 null
     * @param autoBlacklist 是否自动维护默认黑白名单
     */
    public GoldenBeansTasks(SelectModelField blacklist, boolean autoBlacklist) {
        this.blacklist = blacklist;
        this.autoBlacklist = autoBlacklist;
    }

    /**
     * 处理单个入口。
     *
     * @return 该入口的任务列表是否已无待推进项
     */
    public boolean processEntry(GoldenBeansEntry entry, int interval,
                                boolean signEnabled, boolean popupEnabled, boolean taskEnabled) {
        try {
            JSONObject indexJo = GoldenBeansSupport.parse(
                    goldenbeansRpcCall.homeOf(entry.bizType, entry.source));
            if (!GoldenBeansSupport.ok(indexJo)) {
                Log.goldenBeans("金豆[" + entry.alias + "]主页⚠️查询失败["
                        + GoldenBeansSupport.describe(indexJo) + "]");
                return false;
            }

            if (signEnabled) {
                JSONObject signedSync = doSign(indexJo, entry, interval);
                if (signedSync != null) {
                    indexJo = signedSync;
                }
            }

            if (popupEnabled) {
                clickPopup(indexJo, entry, interval);
            }

            if (taskEnabled) {
                return runTaskList(entry, interval);
            }
            return true;
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "processEntry err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
            return false;
        }
    }

    /**
     * 每日签到：从主页 signInfo 中找出今日未签到的 signKey 并提交。
     *
     * @return 签到后的同步响应，供后续弹窗与任务使用
     */
    private JSONObject doSign(JSONObject indexJo, GoldenBeansEntry entry, int interval) {
        try {
            JSONObject signInfo = indexJo.optJSONObject("signInfo");
            if (signInfo == null) {
                Log.goldenBeans("金豆[" + entry.alias + "]签到⚠️未返回签到信息");
                return null;
            }
            JSONArray signList = signInfo.optJSONArray("signList");
            if (signList == null) {
                Log.goldenBeans("金豆[" + entry.alias + "]签到⚠️签到列表为空");
                return null;
            }
            for (int i = 0; i < signList.length(); i++) {
                JSONObject sign = signList.optJSONObject(i);
                if (sign == null || !sign.optBoolean("today", false) || sign.optBoolean("signed", false)) {
                    continue;
                }
                String signKey = sign.optString("signKey", "").trim();
                if (signKey.isEmpty()) {
                    Log.goldenBeans("金豆[" + entry.alias + "]签到⚠️缺少服务端signKey");
                    return null;
                }
                JSONObject signResponse = GoldenBeansSupport.parse(
                        goldenbeansRpcCall.checkInOf(entry.bizType, entry.source, signKey));
                if (!GoldenBeansSupport.ok(signResponse)) {
                    Log.goldenBeans("金豆[" + entry.alias + "]签到⚠️失败["
                            + GoldenBeansSupport.describe(signResponse) + "]");
                    return null;
                }
                GoldenBeansSupport.pause(interval);
                JSONObject syncResponse = GoldenBeansSupport.parse(
                        goldenbeansRpcCall.pullOf(entry.bizType, entry.source,
                                "JAR_INFO", "SIGN", "MARKETING_POPUP", "TASK_LIST"));
                if (GoldenBeansSupport.todaySigned(syncResponse)) {
                    int awardCount = GoldenBeansSupport.awardCount(signResponse);
                    if (awardCount <= 0) {
                        awardCount = sign.optInt("awardCount", 0);
                    }
                    int continuousDays = sign.optInt("currentContinuousCount",
                            sign.optInt("continuousCount", 0));
                    String dayInfo = continuousDays > 0 ? "[第" + continuousDays + "天]" : "";
                    String awardText = awardCount > 0 ? "#获得[" + awardCount + "豆]" : "";
                    Log.goldenBeans("金豆[" + entry.alias + "]签到📅" + dayInfo + awardText);
                } else {
                    Log.goldenBeans("金豆[" + entry.alias + "]签到⚠️未通过服务端状态确认");
                }
                return syncResponse;
            }
            Log.goldenBeans("金豆[" + entry.alias + "]签到📅今日已签到");
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "doSign err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
        }
        return null;
    }

    /** 营销弹窗触发（浏览类动作） */
    private void clickPopup(JSONObject indexJo, GoldenBeansEntry entry, int interval) {
        try {
            JSONObject marketingTask = indexJo.optJSONObject("marketingPopupTask");
            if (marketingTask == null) {
                return;
            }
            String taskId = marketingTask.optString("taskId", "").trim();
            String triggerType = marketingTask.optString("triggerType", "").trim();
            if (triggerType.isEmpty()) {
                triggerType = goldenbeansRpcCall.TRIGGER_MARKETING_POPUP;
            }
            if (taskId.isEmpty()) {
                Log.goldenBeans("金豆[" + entry.alias + "]弹窗⚠️缺少服务端taskId");
                return;
            }
            JSONObject triggerResponse = GoldenBeansSupport.parse(goldenbeansRpcCall.fireOf(
                    entry.bizType, entry.source, taskId, triggerType));
            if (!GoldenBeansSupport.ok(triggerResponse)) {
                Log.goldenBeans("金豆[" + entry.alias + "]弹窗⚠️触发失败[" + taskId + "]"
                        + GoldenBeansSupport.describe(triggerResponse));
                return;
            }
            GoldenBeansSupport.pause(interval);
            JSONObject syncResponse = GoldenBeansSupport.parse(goldenbeansRpcCall.pullOf(
                    entry.bizType, entry.source, "MARKETING_POPUP"));
            if (GoldenBeansSupport.ok(syncResponse)) {
                Log.goldenBeans("金豆[" + entry.alias + "]弹窗🖱️点击[" + taskId + "]");
            } else {
                Log.goldenBeans("金豆[" + entry.alias + "]弹窗⚠️回查失败[" + taskId + "]");
            }
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "clickPopup err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
        }
    }

    /**
     * 任务列表处理：领取已完成任务奖励、完成服务端允许直接完成的任务。
     *
     * @return 该入口是否已无待推进任务
     */
    private boolean runTaskList(GoldenBeansEntry entry, int interval) {
        boolean unresolved = false;
        try {
            JSONObject syncJo = GoldenBeansSupport.parse(goldenbeansRpcCall.pullOf(
                    entry.bizType, entry.source, "FARM_TASK", "TASK_LIST"));
            if (!GoldenBeansSupport.ok(syncJo)) {
                Log.goldenBeans("金豆[" + entry.alias + "]任务⚠️列表查询失败["
                        + GoldenBeansSupport.describe(syncJo) + "]");
                return false;
            }
            JSONArray taskList = syncJo.optJSONArray("taskList");
            if (taskList == null) {
                Log.goldenBeans("金豆[" + entry.alias + "]任务⚠️未返回任务列表");
                return true;
            }

            int total = 0;
            int handled = 0;
            boolean changed = false;
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                // 两个入口的任务场景不同，只处理属于当前入口的任务
                if (!entry.taskSceneCode.equals(task.optString("sceneCode", "").trim())) {
                    continue;
                }
                total++;
                String taskId = task.optString("taskId", "").trim();
                String taskStatus = task.optString("taskStatus", "").trim().toUpperCase();
                String actionType = task.optString("actionType", "").trim();
                String blacklistKey = blacklistKey(task);
                String taskName = displayName(task);

                // 黑名单任务跳过；已完成待领取的仍执行领奖，兼容以标题为键的旧黑名单项
                if (isBlacklisted(blacklistKey, taskName)) {
                    if (STATUS_FINISHED.equals(taskStatus) || STATUS_TO_RECEIVE.equals(taskStatus)) {
                        GoldenBeansSupport.pause(interval);
                        claimAward(entry, taskId, taskName);
                    } else {
                        Log.record("金豆[" + entry.alias + "]任务⏭️[" + taskName + "]黑名单跳过");
                    }
                    continue;
                }

                if (STATUS_RECEIVED.equals(taskStatus) || STATUS_DONE.equals(taskStatus)) {
                    handled++;
                    continue;
                }

                if (STATUS_FINISHED.equals(taskStatus) || STATUS_TO_RECEIVE.equals(taskStatus)) {
                    GoldenBeansSupport.pause(interval);
                    if (claimAward(entry, taskId, taskName)) {
                        handled++;
                        changed = true;
                    } else {
                        unresolved = true;
                    }
                    continue;
                }

                if (STATUS_TODO.equals(taskStatus)) {
                    if (isPayTask(taskId)) {
                        Log.record("金豆[" + entry.alias + "]任务⏭️[" + taskName + "]需真实付款#跳过");
                        continue;
                    }
                    if (goldenbeansRpcCall.TASK_TYPE_EXCHANGE.equals(taskId)) {
                        Log.record("金豆[" + entry.alias + "]任务⏭️[" + taskName + "]"
                                + (entry == GoldenBeansEntry.ALCHEMY ? "芝麻粒换豆处理" : "肥料换豆处理"));
                        continue;
                    }
                    GoldenBeansSupport.pause(interval);
                    if (finishTask(entry, taskId, taskName)) {
                        handled++;
                        changed = true;
                    } else {
                        Log.record("金豆[" + entry.alias + "]任务⚠️[" + taskName + "]完成失败["
                                + (actionType.isEmpty() ? "UNKNOWN" : actionType) + "]");
                        unresolved = true;
                    }
                    continue;
                }

                Log.record("金豆[" + entry.alias + "]任务⚠️[" + taskName + "]未知状态[" + taskStatus + "]");
                unresolved = true;
            }

            Log.record("金豆[" + entry.alias + "]任务🗂️共[" + total + "]个#完成[" + handled + "]个");
            if (changed) {
                GoldenBeansSupport.pause(interval);
                goldenbeansRpcCall.pullOf(entry.bizType, entry.source, "FARM_TASK", "TASK_LIST");
            }
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "runTaskList err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
            return false;
        }
        return !unresolved;
    }

    private boolean isBlacklisted(String blacklistKey, String taskName) {
        if (blacklist == null || blacklist.getValue() == null) {
            return false;
        }
        return blacklist.getValue().contains(blacklistKey) || blacklist.getValue().contains(taskName);
    }

    /**
     * 黑名单键：使用服务端稳定标识 taskId（缺失回退 groupId），
     * 避免展示标题变化导致黑名单匹配失效。
     */
    private String blacklistKey(JSONObject task) {
        String taskId = task.optString("taskId", "").trim();
        if (!taskId.isEmpty()) {
            return taskId;
        }
        String groupId = task.optString("groupId", "").trim();
        if (!groupId.isEmpty()) {
            return groupId;
        }
        return displayName(task);
    }

    /** 任务展示名：优先服务端标题（含 taskDisplayConfig.title） */
    private String displayName(JSONObject task) {
        String title = task.optString("title", "").trim();
        if (title.isEmpty()) {
            JSONObject displayConfig = task.optJSONObject("taskDisplayConfig");
            if (displayConfig != null) {
                title = displayConfig.optString("title", "").trim();
            }
        }
        if (title.isEmpty()) {
            title = task.optString("taskId", "").trim();
        }
        return title;
    }

    /** 支付类任务需要用户真实付款，无法自动完成 */
    private boolean isPayTask(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return false;
        }
        String upper = taskId.toUpperCase();
        return upper.contains("ZHIFU") || upper.contains("_PAY");
    }

    private boolean finishTask(GoldenBeansEntry entry, String taskId, String taskName) {
        if (taskId == null || taskId.isEmpty()) {
            Log.goldenBeans("金豆[" + entry.alias + "]任务⚠️[" + taskName + "]缺少taskId#跳过");
            return false;
        }
        try {
            JSONObject jo = GoldenBeansSupport.parse(goldenbeansRpcCall.submitTaskOf(
                    entry.bizType, entry.source, entry.taskSceneCode, taskId));
            if (GoldenBeansSupport.ok(jo)) {
                Log.goldenBeans("金豆[" + entry.alias + "]任务🧾完成[" + taskName + "]");
                return true;
            }
            // 命中不可自动完成的任务时按错误特征自动加入黑名单
            MessageUtil.checkResultCodeAndMarkTaskBlackList("GoldenBeansTaskList", taskId, jo);
            String failMessage = GoldenBeansSupport.describe(jo);
            if (failMessage.contains("不支持rpc调用") || failMessage.contains("不支持RPC调用")) {
                Log.goldenBeans("金豆[" + entry.alias + "]任务🏴[" + taskName + "]不支持自动化#已加入黑名单");
                return false;
            }
            Log.goldenBeans("金豆[" + entry.alias + "]任务⚠️[" + taskName + "]完成失败[" + failMessage + "]");
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "finishTask err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
        }
        return false;
    }

    private boolean claimAward(GoldenBeansEntry entry, String taskId, String taskName) {
        if (taskId == null || taskId.isEmpty()) {
            Log.goldenBeans("金豆[" + entry.alias + "]任务⚠️[" + taskName + "]缺少taskId#跳过领奖");
            return false;
        }
        try {
            JSONObject jo = GoldenBeansSupport.parse(goldenbeansRpcCall.claimAwardOf(
                    entry.bizType, entry.source, entry.taskSceneCode, taskId));
            if (GoldenBeansSupport.ok(jo)) {
                Log.goldenBeans("金豆[" + entry.alias + "]任务🎖️领取[" + taskName + "]"
                        + GoldenBeansSupport.awardText(jo));
                return true;
            }
            // 领奖失败同样按不可重试错误自动拉黑，避免每轮重复请求
            MessageUtil.checkResultCodeAndMarkTaskBlackList("GoldenBeansTaskList", taskId, jo);
            Log.goldenBeans("金豆[" + entry.alias + "]任务⚠️领取[" + taskName + "]失败["
                    + GoldenBeansSupport.describe(jo) + "]");
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "claimAward err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
        }
        return false;
    }

    /**
     * 初始化任务黑白名单。
     * <p>
     * 先把两个入口的任务列表同步到本地 idMap（供配置界面选择），
     * 再把默认黑名单任务写入模块黑名单配置。
     */
    public void initTaskListMap() {
        try {
            GoldenBeansTaskListMap.load();
            // 默认黑名单：重试也不会有不同结果的任务（键=服务端taskId，值=展示名）
            // 1) 需要真实付款 / 真实业务动作
            // 2) 需要用户亲自确认
            // 3) 芝麻炼金入口的外部游戏：缺少可验证的完成闭环
            Map<String, String> defaultBlackList = new LinkedHashMap<>();
            defaultBlackList.put("GOLDEN_BEAN_TASK_XIANSHANGZHIFU", "线上支付");
            defaultBlackList.put("GOLDEN_BEAN_TASK_XIANXIAZHIFU", "到店/线下支付");
            defaultBlackList.put("GOLDEN_BEAN_TASK_YUEBAO", "余额宝真实业务动作");
            defaultBlackList.put("TEST_PUSH_SUBSCRIBE", "订阅消息需真实确认");
            defaultBlackList.put("ZHIMA_youxi_dageluosi", "芝麻炼金游戏·大格罗斯");
            defaultBlackList.put("ZHIMA_youxi_zheguanwohenxing", "芝麻炼金游戏·这关我很行");
            defaultBlackList.put("ZHIMA_youxi_hebuguowoba", "芝麻炼金游戏·喝不过我吧");
            defaultBlackList.put("ZHIMA_youxi_wodehuayuanshijie", "芝麻炼金游戏·我的花园世界");
            defaultBlackList.put("ZHIMA_youxi_qingyunjuezhifumo", "芝麻炼金游戏·青云诀之伏魔");
            Set<String> defaultKeys = new LinkedHashSet<>(defaultBlackList.keySet());

            for (Map.Entry<String, String> item : defaultBlackList.entrySet()) {
                GoldenBeansTaskListMap.add(item.getKey(), item.getValue());
            }

            // 两个入口的任务场景不同，需要分别同步各自的列表
            for (GoldenBeansEntry entry : GoldenBeansEntry.ALL) {
                JSONObject syncJo = GoldenBeansSupport.parse(goldenbeansRpcCall.pullOf(
                        entry.bizType, entry.source, "FARM_TASK", "TASK_LIST"));
                if (!GoldenBeansSupport.ok(syncJo)) {
                    continue;
                }
                JSONArray taskList = syncJo.optJSONArray("taskList");
                if (taskList == null) {
                    continue;
                }
                for (int i = 0; i < taskList.length(); i++) {
                    JSONObject task = taskList.optJSONObject(i);
                    if (task == null || !entry.taskSceneCode.equals(task.optString("sceneCode", "").trim())) {
                        continue;
                    }
                    String key = blacklistKey(task);
                    if (!key.isEmpty()) {
                        GoldenBeansTaskListMap.add(key, displayName(task));
                    }
                }
            }
            GoldenBeansTaskListMap.save();
            Log.goldenBeans("同步任务🉑金豆夺宝任务列表");

            if (!autoBlacklist) {
                return;
            }
            ConfigV2 config = ConfigV2.INSTANCE;
            ModelFields modelFields = config.getModelFieldsMap().get("goldenbeans");
            if (modelFields == null) {
                return;
            }
            SelectModelField taskListField = (SelectModelField) modelFields.get("GoldenBeansTaskList");
            if (taskListField == null) {
                return;
            }

            // 补齐默认黑名单任务
            Set<String> currentValues = taskListField.getValue();
            if (currentValues != null) {
                for (String task : defaultKeys) {
                    if (!currentValues.contains(task)) {
                        taskListField.add(task, 0);
                    }
                }
            }
            if (ConfigV2.save(UserIdMap.getCurrentUid(), false)) {
                Log.goldenBeans("黑白名单🈲金豆夺宝任务自动设置: " + taskListField.getValue());
            } else {
                Log.goldenBeans("黑白名单⚠️金豆夺宝任务设置失败");
            }
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "initTaskListMap err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
        }
    }
}

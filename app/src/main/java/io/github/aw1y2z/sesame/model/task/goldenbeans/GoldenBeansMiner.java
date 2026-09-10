package io.github.aw1y2z.sesame.model.task.goldenbeans;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.aw1y2z.sesame.util.Log;

/**
 * 金猫矿工：按服务端下发的可抓取次数逐次抓取金豆。
 * <p>
 * 每轮抓取后都以响应里的 taskProgress 为准推进剩余次数；一旦服务端未推进次数、
 * 或要求观看广告，就立刻停止，避免无效重复请求。
 */
public final class GoldenBeansMiner {

    private GoldenBeansMiner() {
    }

    public static void run(int interval) {
        try {
            JSONObject indexJo = GoldenBeansSupport.parse(goldenbeansRpcCall.minerHome());
            if (!GoldenBeansSupport.ok(indexJo)) {
                Log.goldenBeans("金猫矿工⚠️首页查询失败[" + GoldenBeansSupport.describe(indexJo) + "]");
                return;
            }
            if (!indexJo.has("enabled")) {
                Log.goldenBeans("金猫矿工⚠️响应缺少enabled");
                return;
            }
            if (!indexJo.optBoolean("enabled", false)) {
                Log.goldenBeans("金猫矿工⏸️服务端未启用");
                return;
            }
            JSONObject minerInfo = indexJo.optJSONObject("minerInfo");
            if (minerInfo == null) {
                Log.goldenBeans("金猫矿工⚠️响应缺少minerInfo");
                return;
            }
            JSONObject taskProgress = minerInfo.optJSONObject("taskProgress");
            if (taskProgress == null || !taskProgress.has("canGrab") || !taskProgress.has("remainingTimes")) {
                Log.goldenBeans("金猫矿工⚠️响应缺少可抓取状态");
                return;
            }
            if (!taskProgress.optBoolean("canGrab", false)) {
                Log.goldenBeans("金猫矿工⏸️今日无可抓取次数");
                return;
            }

            // 已经抓取过的格子不再重复抓取
            Set<String> grabbedItemIds = new HashSet<>();
            JSONObject progress = minerInfo.optJSONObject("progress");
            JSONArray alreadyGrabbed = progress != null ? progress.optJSONArray("grabbedItemIds") : null;
            if (alreadyGrabbed != null) {
                for (int i = 0; i < alreadyGrabbed.length(); i++) {
                    String itemId = alreadyGrabbed.optString(i, "").trim();
                    if (!itemId.isEmpty()) {
                        grabbedItemIds.add(itemId);
                    }
                }
            }

            JSONObject currentLevel = minerInfo.optJSONObject("currentLevel");
            JSONArray items = currentLevel != null ? currentLevel.optJSONArray("items") : null;
            if (items == null) {
                Log.goldenBeans("金猫矿工⚠️首页缺少items");
                return;
            }
            List<String> beanItemIds = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String itemId = item.optString("itemId", "").trim();
                if ("BEAN".equals(item.optString("type", "")) && !itemId.isEmpty()
                        && !grabbedItemIds.contains(itemId)) {
                    beanItemIds.add(itemId);
                }
            }

            int candidateIndex = 0;
            int remainingTimes = taskProgress.optInt("remainingTimes", 0);
            boolean canGrab = taskProgress.optBoolean("canGrab", false);
            int grabbedTimes = 0;
            while (canGrab && remainingTimes > 0) {
                String itemId = candidateIndex < beanItemIds.size() ? beanItemIds.get(candidateIndex) : "";
                String expectedResult = itemId.isEmpty() ? "EMPTY" : "BEAN";
                GoldenBeansSupport.pause(interval);
                JSONObject grabResponse = GoldenBeansSupport.parse(
                        goldenbeansRpcCall.grabBean(expectedResult, itemId));
                if (!GoldenBeansSupport.ok(grabResponse)) {
                    Log.goldenBeans("金猫矿工⚠️抓取失败[" + GoldenBeansSupport.describe(grabResponse) + "]");
                    return;
                }
                goldenbeansRpcCall.pullBySource(goldenbeansRpcCall.MINER_PAGE_SOURCE, "JAR_INFO");
                if (grabResponse.optBoolean("needAd", false)) {
                    Log.goldenBeans("金猫矿工⚠️需观看广告#待人工处理");
                    return;
                }
                if ("BEAN".equals(expectedResult)) {
                    candidateIndex++;
                }
                JSONObject updated = grabResponse.optJSONObject("taskProgress");
                if (updated == null || !updated.has("canGrab") || !updated.has("remainingTimes")) {
                    Log.goldenBeans("金猫矿工⚠️响应缺少可抓取状态");
                    return;
                }
                int updatedRemaining = updated.optInt("remainingTimes", remainingTimes);
                if (updatedRemaining >= remainingTimes) {
                    Log.goldenBeans("金猫矿工⚠️次数未推进[" + remainingTimes + "→" + updatedRemaining + "]");
                    return;
                }
                remainingTimes = updatedRemaining;
                canGrab = updated.optBoolean("canGrab", false);
                grabbedTimes++;
                Log.goldenBeans("金猫矿工⛏️抓取[第" + grabbedTimes + "次]"
                        + GoldenBeansSupport.awardText(grabResponse));
            }
            Log.goldenBeans("金猫矿工⛏️完成#共抓取[" + grabbedTimes + "]次");
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "runMiner err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
        }
    }
}

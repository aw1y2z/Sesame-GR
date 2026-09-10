package io.github.aw1y2z.sesame.model.task.goldenbeans;

import org.json.JSONObject;

import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.Status;

/**
 * 金豆兑换处理。
 * <p>
 * 两个入口共用 {@code com.alipay.goldenbean.manureExchange} 接口：农场入口消耗肥料，
 * 芝麻炼金入口消耗芝麻粒。请求参数 exchangeBeanAmount 表示希望换到的金豆数量，
 * 实际到账以响应中的 beanDelta 为准。
 * <p>
 * 兑换量 = min(服务端可兑换额度, 用户配置的单日上限剩余)；上限为 0 表示不限制。
 */
public final class GoldenBeansExchange {

    /** 当日肥料换豆已消耗的肥料数 */
    private static final String FLAG_MANURE_AMOUNT = "goldenBeans::manureExchangeAmount";
    /** 当日芝麻粒换豆已获得的金豆数 */
    private static final String FLAG_SESAME_BEAN_AMOUNT = "goldenBeans::sesameExchangeBeanAmount";

    private GoldenBeansExchange() {
    }

    /**
     * 肥料换豆（农场入口）。
     *
     * @param dailyLimit 单日消耗肥料上限，0 表示不限
     */
    public static void exchangeManure(int interval, int dailyLimit) {
        try {
            JSONObject indexJo = GoldenBeansSupport.parse(goldenbeansRpcCall.home());
            if (!GoldenBeansSupport.ok(indexJo)) {
                Log.goldenBeans("金豆换豆⚠️资格查询失败[" + GoldenBeansSupport.describe(indexJo) + "]");
                return;
            }
            JSONObject info = indexJo.optJSONObject("manureExchangeInfo");
            if (info == null) {
                Log.goldenBeans("金豆换豆⚠️响应缺少manureExchangeInfo");
                return;
            }
            boolean farmOpened = info.optBoolean("farmOpened", false);
            boolean pageOpened = info.optBoolean("pageOpened", false);
            boolean taobaoBinding = info.optBoolean("taobaoBinding", false);
            int currentManure = info.optInt("currentManure", 0);
            int effectiveExchangeManure = info.optInt("effectiveExchangeManure", 0);
            int minExchangeAmount = info.optInt("minExchangeAmount", 0);
            int remainQuota = info.optInt("remainQuota", 0);

            if (!farmOpened || !pageOpened || !taobaoBinding) {
                Log.goldenBeans("金豆换豆⚠️资格未满足#跳过");
                return;
            }
            if (minExchangeAmount <= 0) {
                Log.goldenBeans("金豆换豆⚠️最低兑换量无效#跳过");
                return;
            }

            int exchangedToday = Status.getIntFlagToday(FLAG_MANURE_AMOUNT);
            // 服务端可兑换额度：当前肥料 / 有效可兑换量 / 剩余额度 三者最小值，再按用户上限收敛
            int serverLimit = Math.min(currentManure, Math.min(effectiveExchangeManure, remainQuota));
            int userRemaining = dailyLimit > 0
                    ? Math.max(dailyLimit - exchangedToday, 0) : Integer.MAX_VALUE;
            int reserved = Math.min(serverLimit, userRemaining);
            if (reserved <= 0) {
                Log.goldenBeans("金豆换豆⏸️可兑换额度不足#跳过");
                return;
            }
            if (reserved < minExchangeAmount) {
                Log.goldenBeans("金豆换豆⏸️可兑换[" + reserved + "]低于服务端最低["
                        + minExchangeAmount + "]#本轮不换");
                return;
            }

            GoldenBeansSupport.pause(interval);
            JSONObject exchangeResponse = GoldenBeansSupport.parse(goldenbeansRpcCall.exchangeBean(reserved));
            if (!GoldenBeansSupport.ok(exchangeResponse)) {
                Log.goldenBeans("金豆换豆⚠️失败[" + GoldenBeansSupport.describe(exchangeResponse) + "]");
                return;
            }
            Log.goldenBeans("金豆换豆🌱消耗[" + reserved + "肥料]"
                    + GoldenBeansSupport.awardText(exchangeResponse));
            Status.setIntFlagToday(FLAG_MANURE_AMOUNT, exchangedToday + reserved);
            GoldenBeansSupport.pause(interval);
            goldenbeansRpcCall.pull("JAR_INFO", "EXCHANGE_MANURE", "TASK_LIST");
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "exchangeManure err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
        }
    }

    /**
     * 芝麻粒换豆（芝麻炼金入口）。
     *
     * @param dailyBeanLimit 单日可兑换金豆数上限，0 表示不限
     */
    public static void exchangeSesame(int interval, int dailyBeanLimit) {
        try {
            JSONObject indexJo = GoldenBeansSupport.parse(goldenbeansRpcCall.homeOf(
                    GoldenBeansEntry.ALCHEMY.bizType, GoldenBeansEntry.ALCHEMY.source));
            if (!GoldenBeansSupport.ok(indexJo)) {
                Log.goldenBeans("金豆芝麻粒换豆⚠️资格查询失败[" + GoldenBeansSupport.describe(indexJo) + "]");
                return;
            }
            JSONObject info = indexJo.optJSONObject("manureExchangeInfo");
            if (info == null) {
                Log.goldenBeans("金豆芝麻粒换豆⚠️响应缺少manureExchangeInfo");
                return;
            }
            boolean pageOpened = info.optBoolean("pageOpened", false);
            int beanReward = info.optInt("beanReward", 0);
            int currentManure = info.optInt("currentManure", 0);
            int effectiveExchangeManure = info.optInt("effectiveExchangeManure", 0);
            int minExchangeAmount = info.optInt("minExchangeAmount", 0);
            int remainQuota = info.optInt("remainQuota", 0);
            int exchangedToday = Status.getIntFlagToday(FLAG_SESAME_BEAN_AMOUNT);

            // 服务端可兑换金豆：剩余额度 / 当前芝麻粒可换 / 有效芝麻粒可换 三者最小值，再按用户上限收敛
            long serverLimit = Math.min((long) remainQuota,
                    Math.min((long) currentManure * beanReward, (long) effectiveExchangeManure * beanReward));
            long userRemaining = dailyBeanLimit > 0
                    ? Math.max(dailyBeanLimit - exchangedToday, 0) : Long.MAX_VALUE;
            long available = Math.max(Math.min(serverLimit, userRemaining), 0);

            Log.goldenBeans("金豆芝麻粒换豆资格🔍页面[" + pageOpened + "]芝麻粒[" + currentManure
                    + "]可换芝麻粒[" + effectiveExchangeManure + "]单粒换豆[" + beanReward
                    + "]最低兑换[" + minExchangeAmount + "]剩余额度[" + remainQuota
                    + "]单日上限[" + (dailyBeanLimit > 0 ? String.valueOf(dailyBeanLimit) : "不限")
                    + "]今日已换[" + exchangedToday + "]可兑换[" + available + "]");

            if (!pageOpened || beanReward <= 0 || minExchangeAmount <= 0) {
                Log.goldenBeans("金豆芝麻粒换豆⏸️服务端资格未满足#跳过");
                return;
            }
            if (available < minExchangeAmount) {
                Log.goldenBeans("金豆芝麻粒换豆⏸️可兑换[" + available + "]低于服务端最低["
                        + minExchangeAmount + "]#本轮不换");
                return;
            }

            int exchangeBeanAmount = (int) Math.min(available, Integer.MAX_VALUE);
            GoldenBeansSupport.pause(interval);
            JSONObject exchangeResponse = GoldenBeansSupport.parse(goldenbeansRpcCall.exchangeBeanOf(
                    GoldenBeansEntry.ALCHEMY.bizType, GoldenBeansEntry.ALCHEMY.source, exchangeBeanAmount));
            if (!GoldenBeansSupport.ok(exchangeResponse)) {
                Log.goldenBeans("金豆芝麻粒换豆⚠️失败[" + GoldenBeansSupport.describe(exchangeResponse) + "]");
                return;
            }
            int beanDelta = exchangeResponse.optInt("beanDelta", 0);
            if (beanDelta <= 0) {
                Log.goldenBeans("金豆芝麻粒换豆⚠️响应缺少有效beanDelta#不记录额度");
                return;
            }

            GoldenBeansSupport.pause(interval);
            JSONObject syncResponse = GoldenBeansSupport.parse(goldenbeansRpcCall.pullOf(
                    GoldenBeansEntry.ALCHEMY.bizType, GoldenBeansEntry.ALCHEMY.source,
                    "JAR_INFO", "EXCHANGE_MANURE", "TASK_LIST"));
            if (!GoldenBeansSupport.ok(syncResponse)) {
                Log.goldenBeans("金豆芝麻粒换豆⚠️回查失败[" + GoldenBeansSupport.describe(syncResponse) + "]");
                return;
            }
            JSONObject afterInfo = syncResponse.optJSONObject("manureExchangeInfo");
            Status.setIntFlagToday(FLAG_SESAME_BEAN_AMOUNT, exchangedToday + beanDelta);
            Log.goldenBeans("金豆芝麻粒换豆🌾请求[" + exchangeBeanAmount + "]消耗["
                    + exchangeResponse.optInt("manureCost", -1) + "芝麻粒]#获得[" + beanDelta + "豆]"
                    + "剩余芝麻粒["
                    + (afterInfo != null ? afterInfo.optInt("currentManure", -1) : -1) + "]");
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "exchangeSesame err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
        }
    }
}

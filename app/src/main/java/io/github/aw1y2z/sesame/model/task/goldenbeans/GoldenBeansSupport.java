package io.github.aw1y2z.sesame.model.task.goldenbeans;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

import io.github.aw1y2z.sesame.data.modelFieldExt.BooleanModelField;
import io.github.aw1y2z.sesame.util.Log;

/**
 * 金豆夺宝的通用解析与辅助方法。
 * <p>
 * 这里只放与具体玩法无关的部分：布尔配置判定、响应解析、失败原因与奖励文案提取、
 * 递归查找、操作间隔休眠。各业务处理器（任务 / 兑换 / 乐园 / 矿工）共用这些方法。
 */
public final class GoldenBeansSupport {

    /** 模块日志标签 */
    public static final String TAG = "金豆夺宝";

    private GoldenBeansSupport() {
    }

    /** 布尔配置是否开启（null 视为关闭） */
    public static boolean enabled(BooleanModelField field) {
        return field != null && Boolean.TRUE.equals(field.getValue());
    }

    /** 解析服务端响应字符串，失败返回 null */
    public static JSONObject parse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(response);
        } catch (Exception e) {
            Log.goldenBeans("金豆夺宝响应解析失败：" + response);
            return null;
        }
    }

    /** 成功判定，兼容 success / resultCode / code 三种返回契约 */
    public static boolean ok(JSONObject jo) {
        if (jo == null) {
            return false;
        }
        if (jo.optBoolean("success", false)) {
            return true;
        }
        String resultCode = jo.optString("resultCode", "");
        if ("100".equals(resultCode) || "SUCCESS".equals(resultCode)) {
            return true;
        }
        return "100000000".equals(jo.optString("code", ""));
    }

    /** 提取失败原因，无任何文案字段时回退为原始响应 */
    public static String describe(JSONObject jo) {
        if (jo == null) {
            return "EMPTY";
        }
        String message = jo.optString("desc", "");
        if (message.isEmpty()) {
            message = jo.optString("resultDesc", "");
        }
        if (message.isEmpty()) {
            message = jo.optString("errorMessage", "");
        }
        if (message.isEmpty()) {
            message = jo.optString("memo", "");
        }
        return message.isEmpty() ? jo.toString() : message;
    }

    /** 从响应中提取奖励数量，兼容不同接口的字段命名 */
    public static int awardCount(JSONObject response) {
        if (response == null) {
            return 0;
        }
        int count = response.optInt("awardCount", 0);
        if (count <= 0) {
            count = response.optInt("beanCount", 0);
        }
        if (count <= 0) {
            count = response.optInt("beanDelta", 0);
        }
        if (count <= 0) {
            count = response.optInt("count", 0);
        }
        return count;
    }

    /** 奖励文案：#获得[N豆]；响应中没有数量时返回空串 */
    public static String awardText(JSONObject response) {
        int count = awardCount(response);
        return count > 0 ? "#获得[" + count + "豆]" : "";
    }

    /** 服务端是否已确认今日签到 */
    public static boolean todaySigned(JSONObject jo) {
        if (jo == null) {
            return false;
        }
        JSONObject signInfo = jo.optJSONObject("signInfo");
        if (signInfo == null) {
            return false;
        }
        if (signInfo.optBoolean("todaySigned", false)) {
            return true;
        }
        JSONArray signList = signInfo.optJSONArray("signList");
        if (signList == null) {
            return false;
        }
        for (int i = 0; i < signList.length(); i++) {
            JSONObject sign = signList.optJSONObject(i);
            if (sign != null && sign.optBoolean("today", false) && sign.optBoolean("signed", false)) {
                return true;
            }
        }
        return false;
    }

    /** 在任意层级的响应结构中查找首个指定 key 的对象 */
    public static JSONObject findObject(Object source, String targetKey) {
        if (source instanceof JSONObject) {
            JSONObject obj = (JSONObject) source;
            JSONObject direct = obj.optJSONObject(targetKey);
            if (direct != null) {
                return direct;
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                JSONObject result = findObject(obj.opt(keys.next()), targetKey);
                if (result != null) {
                    return result;
                }
            }
        } else if (source instanceof JSONArray) {
            JSONArray array = (JSONArray) source;
            for (int i = 0; i < array.length(); i++) {
                JSONObject result = findObject(array.opt(i), targetKey);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /** 从 ~ 分隔的 tracer 串中取出指定字段的值 */
    public static String tracerField(String tracer, String field) {
        if (tracer == null || tracer.isEmpty()) {
            return "";
        }
        for (String part : tracer.split("~")) {
            if (part.startsWith(field + ":")) {
                return part.substring(field.length() + 1);
            }
        }
        return "";
    }

    /** 按操作间隔休眠，下限 200ms；线程中断时恢复中断标记 */
    public static void pause(int interval) {
        try {
            Thread.sleep(Math.max(interval, 200));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

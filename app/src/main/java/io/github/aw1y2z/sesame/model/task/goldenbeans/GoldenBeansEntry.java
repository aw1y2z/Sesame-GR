package io.github.aw1y2z.sesame.model.task.goldenbeans;

/**
 * 金豆夺宝的入口定义。
 * <p>
 * 芭芭农场与芝麻炼金是两个互相独立的入口：除奖励共用外并不互通，
 * 请求必须使用各自配套的 bizType、source 与 sceneCode，混用会被服务端拒绝。
 * 两者的任务列表也按 sceneCode 区分，因此不能把入口信息散落在各处。
 */
public final class GoldenBeansEntry {

    /** 入口别名，仅用于日志区分 */
    public final String alias;
    /** 请求体 bizType */
    public final String bizType;
    /** 请求体 source */
    public final String source;
    /** 任务列表的 sceneCode */
    public final String taskSceneCode;

    private GoldenBeansEntry(String alias, String bizType, String source, String taskSceneCode) {
        this.alias = alias;
        this.bizType = bizType;
        this.source = source;
        this.taskSceneCode = taskSceneCode;
    }

    /** 芭芭农场入口：兑换消耗肥料 */
    public static final GoldenBeansEntry FARM = new GoldenBeansEntry(
            "农场",
            goldenbeansRpcCall.FARM_BIZ_TYPE,
            goldenbeansRpcCall.FARM_SOURCE,
            goldenbeansRpcCall.FARM_TASK_SCENE_CODE);

    /** 芝麻炼金入口：兑换消耗芝麻粒 */
    public static final GoldenBeansEntry ALCHEMY = new GoldenBeansEntry(
            "炼金",
            goldenbeansRpcCall.ALCHEMY_BIZ_TYPE,
            goldenbeansRpcCall.ALCHEMY_SOURCE,
            goldenbeansRpcCall.ALCHEMY_TASK_SCENE_CODE);

    /** 全部入口，按处理顺序排列 */
    public static final GoldenBeansEntry[] ALL = {FARM, ALCHEMY};
}

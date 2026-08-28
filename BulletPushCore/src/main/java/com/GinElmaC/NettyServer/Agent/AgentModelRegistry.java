package com.GinElmaC.NettyServer.Agent;

import java.util.List;

/**
 * 模型注册表。
 * 新增模型时在此处注册静态配置，AutoModelRouter 会自动纳入其可用候选集。
 */
public class AgentModelRegistry {
    // 前端与服务层使用的自动路由标识。
    public static final String AUTO = "auto";
    // 当前已接入模型的固定选择标识。
    public static final String DEEPSEEK_FLASH = "deepseek-flash";

    // 当前项目仅注册一个模型，后续可以按相同方式扩展为多个模型。
    private static final AgentModel DEEPSEEK_FLASH_MODEL = new AgentModel(
            AgentModelConfig.MODEL_NAME,
            AgentModelConfig.BASE_URL,
            AgentModelConfig.API_KEY,
            AgentModelConfig.DEEPSEEK_FLASH_MAX_CONCURRENCY
    );

    /**
     * 返回配置完整、可被路由的模型列表。
     */
    public List<AgentModel> enabledModels() {
        return DEEPSEEK_FLASH_MODEL.enabled() ? List.of(DEEPSEEK_FLASH_MODEL) : List.of();
    }

    /**
     * 根据前端传入的固定模型标识查询可用模型。
     */
    public AgentModel findEnabled(String modelName) {
        if (DEEPSEEK_FLASH.equals(modelName) && DEEPSEEK_FLASH_MODEL.enabled()) {
            return DEEPSEEK_FLASH_MODEL;
        }
        return null;
    }
}

package com.example.ai.model.controller;

import com.example.ai.model.client.ModelMetadataCatalog;
import com.example.ai.model.dto.ModelTestResult;
import com.example.ai.model.dto.ProviderRequest;
import com.example.ai.model.dto.ProviderResponse;
import com.example.ai.model.dto.ProviderTestResult;
import com.example.ai.model.dto.ReasoningLevel;
import com.example.ai.model.dto.RemoteModelInfo;
import com.example.ai.model.dto.RemoteModelQueryRequest;
import com.example.ai.model.service.ModelConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型配置接口（前端「系统管理 > 模型配置」页后端，挂 /api 前缀走 vite 代理）
 * <pre>
 * GET    /api/model/providers                    供应商列表（含各自模型列表）
 * GET    /api/model/providers/{id}               供应商详情
 * POST   /api/model/providers                    新增供应商（可一并保存模型）
 * PUT    /api/model/providers/{id}               编辑供应商（models=null 不改模型，[]=清空）
 * DELETE /api/model/providers/{id}               删除供应商（级联删除全部模型）
 * POST   /api/model/providers/{id}/test          测试连通性（仅测供应商可达 / 凭据）
 * GET    /api/model/providers/{id}/remote-models 一键获取供应商远程模型列表（含窗口信息补全）
 * POST   /api/model/remote-models                按草稿参数获取远程模型列表（新供应商未保存时用）
 * POST   /api/model/models/{id}/test             测试单个模型可用性（固定提示词最小对话）
 * POST   /api/model/models/test                  批量测试模型可用性（请求体为 id 数组）
 * POST   /api/model/providers/{id}/enrich        补全已保存模型缺失的窗口信息
 * GET    /api/model/types                        供应商类型预设（下拉 + 默认 Base URL）
 * GET    /api/model/reasoning-levels/default     默认推理档位（新建模型时预填）
 * GET    /api/model/chat-options                 聊天页模型选择器数据（按供应商分组）
 * GET    /api/model/metadata?modelName=&type=    单模型元信息查询（models.dev 目录）
 * </pre>
 * 错误形态：业务异常经本控制器局部 {@code @ExceptionHandler} 映射——
 * {@link IllegalArgumentException} → 400、{@link IllegalStateException} → 409，
 * 返回体 {@code {"status":..,"error":..,"message":..}} 供前端按状态码 + message 提示。
 */
@Slf4j
@Tag(name = "模型配置", description = "供应商维护 / 模型维护 / 连通性测试 / 远程模型获取 / 聊天页模型选项")
@RestController
@RequestMapping("/api/model")
public class ModelConfigController {

    @Resource
    private ModelConfigService modelConfigService;

    @Resource
    private ModelMetadataCatalog metadataCatalog;

    /**
     * 供应商列表（含各自模型列表，按创建时间倒序）
     */
    @Operation(summary = "供应商列表", description = "返回全部供应商及其模型列表（API Key 已脱敏）")
    @GetMapping("/providers")
    public List<ProviderResponse> listProviders() {
        return modelConfigService.listProviders();
    }

    /**
     * 供应商详情（含模型列表）
     */
    @Operation(summary = "供应商详情", description = "返回单个供应商及其模型列表")
    @GetMapping("/providers/{id}")
    public ProviderResponse getProvider(@PathVariable("id") Long id) {
        return modelConfigService.getProvider(id);
    }

    /**
     * 新增供应商（可一并提交模型列表）
     */
    @Operation(summary = "新增供应商", description = "models 非空时一并创建其模型；名称唯一")
    @PostMapping("/providers")
    public ProviderResponse createProvider(@RequestBody ProviderRequest request) {
        return modelConfigService.createProvider(request);
    }

    /**
     * 编辑供应商（models 为 null 表示不改模型；传列表则整体覆盖）
     */
    @Operation(summary = "编辑供应商",
            description = "API Key 留空表示不修改；models=null 不改模型，models=[] 清空模型，其余整体覆盖")
    @PutMapping("/providers/{id}")
    public ProviderResponse updateProvider(@PathVariable("id") Long id, @RequestBody ProviderRequest request) {
        return modelConfigService.updateProvider(id, request);
    }

    /**
     * 删除供应商（级联删除其全部模型）
     */
    @Operation(summary = "删除供应商", description = "级联删除该供应商下的全部模型")
    @DeleteMapping("/providers/{id}")
    public Map<String, Object> deleteProvider(@PathVariable("id") Long id) {
        modelConfigService.deleteProvider(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", true);
        body.put("providerId", id);
        return body;
    }

    /**
     * 测试供应商连通性（仅测可模型供应商的连通性，不校验具体模型）
     */
    @Operation(summary = "测试供应商连通性", description = "调用 GET {baseUrl}/models 验证可达性与凭据，不校验具体模型名")
    @PostMapping("/providers/{id}/test")
    public ProviderTestResult testProvider(@PathVariable("id") Long id) {
        return modelConfigService.testProvider(id);
    }

    /**
     * 一键获取供应商远程模型列表（上下文窗口 / 最大输出 / 输入类型按需补全）
     */
    @Operation(summary = "获取供应商远程模型列表",
            description = "解析 /models 响应；窗口信息先取供应商返回字段，缺失时回落 models.dev 目录")
    @GetMapping("/providers/{id}/remote-models")
    public List<RemoteModelInfo> listRemoteModels(@PathVariable("id") Long id) {
        return modelConfigService.listRemoteModels(id);
    }

    /**
     * 按草稿参数获取远程模型列表（新供应商尚未保存时使用；编辑页不必先保存再看模型清单）
     */
    @Operation(summary = "按草稿参数获取远程模型列表",
            description = "providerId 非空且 apiKey 留空时沿用已保存 Key；apiKey 可空（本地服务无需鉴权）")
    @PostMapping("/remote-models")
    public List<RemoteModelInfo> listRemoteModelsByDraft(@RequestBody RemoteModelQueryRequest request) {
        return modelConfigService.listRemoteModelsByDraft(request);
    }

    /**
     * 补全已保存模型缺失的窗口信息（上下文窗口 / 最大输出 tokens）
     */
    @Operation(summary = "补全模型窗口信息", description = "对已保存模型中窗口信息为空的项，按 models.dev 目录回填后落库")
    @PostMapping("/providers/{id}/enrich")
    public ProviderResponse enrichModels(@PathVariable("id") Long id) {
        return modelConfigService.enrichModels(id);
    }

    /**
     * 测试单个模型是否可用（用固定提示词「请回复pong」发起一次最小对话）
     */
    @Operation(summary = "测试单个模型可用性",
            description = "以固定提示词发起一次最小化同步对话（max_tokens 收紧、超时 30s），拿到非空回复即视为可用")
    @PostMapping("/models/{id}/test")
    public ModelTestResult testModel(@PathVariable("id") Long id) {
        return modelConfigService.testModel(id);
    }

    /**
     * 批量测试模型可用性（逐条测试，单条失败不影响其余）
     */
    @Operation(summary = "批量测试模型可用性",
            description = "请求体为模型 id 数组；逐条测试并返回各自结果，某条失败不中断整批")
    @PostMapping("/models/test")
    public List<ModelTestResult> testModels(@RequestBody List<Long> modelIds) {
        return modelConfigService.testModels(modelIds);
    }

    /**
     * 供应商类型预设（下拉选项 + 默认 Base URL，前端选择类型时自动填入）
     */
    @Operation(summary = "供应商类型预设", description = "返回类型 value / label / defaultBaseUrl")
    @GetMapping("/types")
    public List<Map<String, String>> listTypes() {
        return modelConfigService.listTypes();
    }

    /**
     * 默认推理档位（新建模型时前端预填 off / low / medium / high）
     */
    @Operation(summary = "默认推理档位", description = "off / low / medium / high 四档，apiValue 为下发给供应商的参数值")
    @GetMapping("/reasoning-levels/default")
    public List<ReasoningLevel> defaultReasoningLevels() {
        return modelConfigService.defaultReasoningLevels();
    }

    /**
     * 聊天页模型选择器数据（按供应商分组，只含启用项）
     */
    @Operation(summary = "聊天页模型选项", description = "按供应商分组返回启用模型（含推理档位），供流式对话页切换模型")
    @GetMapping("/chat-options")
    public List<ModelConfigService.ModelGroup> chatOptions() {
        return modelConfigService.listModelGroups();
    }

    /**
     * 单模型元信息查询（前端「一键获取」时对单独一行补全窗口信息）
     */
    @Operation(summary = "单模型元信息", description = "按模型名（+ 供应商类型）查 models.dev 目录，未命中返回空对象")
    @GetMapping("/metadata")
    public RemoteModelInfo metadata(
            @Parameter(description = "模型名称（如 gpt-4o）", required = true)
            @RequestParam("modelName") String modelName,
            @Parameter(description = "供应商类型（可选，提升命中优先级）")
            @RequestParam(value = "type", required = false) String type) {
        RemoteModelInfo info = metadataCatalog.lookupOne(type, modelName);
        return info == null ? new RemoteModelInfo() : info;
    }

    // ------------------------------------------------------------
    // 局部异常映射（不引入全局异常处理器）：前端统一按 HTTP 状态码 + message 提示

    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return errorBody(400, "Bad Request", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException e) {
        return errorBody(409, "Conflict", e.getMessage());
    }

    private static org.springframework.http.ResponseEntity<Map<String, Object>> errorBody(
            int status, String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", error);
        body.put("message", message == null || message.trim().isEmpty() ? "请求处理失败" : message);
        return org.springframework.http.ResponseEntity.status(status).body(body);
    }
}

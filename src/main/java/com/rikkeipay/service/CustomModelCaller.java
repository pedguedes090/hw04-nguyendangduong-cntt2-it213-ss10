package com.rikkeipay.service;

import io.langfuse.client.LangfuseClient;
import io.langfuse.client.model.Generation;
import io.langfuse.client.model.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CustomModelCaller - mô phỏng gọi một custom model KHÔNG hỗ trợ
 * tự động đếm token (VD: model tự host qua vLLM, model qua gateway nội bộ).
 *
 * Luồng:
 *   1. Tạo Trace (đại diện cho một lượt gọi AI).
 *   2. Gọi model thật (giả lập bằng sleep).
 *   3. Tự đếm token (TokenUsageReporter.estimateTokens) vì model không trả usage.
 *   4. Gửi Generation kèm Usage thủ công lên Langfuse -> Langfuse tính cost
 *      dựa trên Model Price List đã cấu hình cho modelName.
 */
@Service
public class CustomModelCaller {

    private static final Logger log = LoggerFactory.getLogger(CustomModelCaller.class);

    private final LangfuseClient langfuseClient;
    private final TokenUsageReporter tokenUsageReporter;

    public CustomModelCaller(LangfuseClient langfuseClient, TokenUsageReporter tokenUsageReporter) {
        this.langfuseClient = langfuseClient;
        this.tokenUsageReporter = tokenUsageReporter;
    }

    /**
     * Gọi custom model "gemini-2.5-flash-selfhost" và gửi token usage thủ công.
     */
    public void callCustomModel(String userId, String prompt) {
        // 1. Trace cha
        Trace trace = langfuseClient.trace(new Trace()
                .name("custom-model-inference")
                .userId(userId)
                .input(prompt));

        long start = System.currentTimeMillis();

        // 2. Gọi model (giả lập) — model này KHÔNG trả về token usage
        String output = simulateModelCall(prompt);

        long durationMs = System.currentTimeMillis() - start;

        // 3. Tự đếm token vì model không cung cấp usage
        int inputTokens = tokenUsageReporter.estimateTokens(prompt);
        int outputTokens = tokenUsageReporter.estimateTokens(output);

        // 4. Gửi generation + usage thủ công lên Langfuse
        tokenUsageReporter.reportManualUsage(
                trace.getId(),
                "gemini-2.5-flash",
                prompt,
                output,
                inputTokens,
                outputTokens);

        trace.output(output).metadata(java.util.Map.of(
                "userId", userId,
                "durationMs", durationMs,
                "manualTokenReporting", true));

        log.info("Hoàn tất gọi custom model: userId={}, inputTokens={}, outputTokens={}, durationMs={}",
                userId, inputTokens, outputTokens, durationMs);
    }

    /** Giả lập phản hồi model. */
    private String simulateModelCall(String prompt) {
        try {
            Thread.sleep(180);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "Xin chào, tôi là trợ lý RikkeiPay. Bạn vừa hỏi: \"" + prompt + "\".";
    }
}

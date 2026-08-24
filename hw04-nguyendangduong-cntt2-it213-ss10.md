# HW04 — Giám Sát Chi Phí & Phân Tích Latency

**Học viên:** Nguyễn Đăng Dương — **Lớp:** CNTT2 — **Bài:** SS10 — **HW04**

**Link GitHub:** https://github.com/pedguedes090/hw04-nguyendangduong-cntt2-it213-ss10.git

---

## 1. Cơ chế Token & Cost Tracking của Langfuse

### 1.1. Langfuse tự động đếm token từ cuộc gọi Spring AI

1. **Spring AI ghi token usage vào metadata:** khi gọi `ChatClient`, Spring AI nhận phản hồi từ provider (OpenAI/Anthropic/Gemini/DeepSeek...) kèm trường `usage` (prompt_tokens, completion_tokens, total_tokens). Các starter của Spring AI tự động đính kèm thông tin này vào generation event.
2. **Langfuse SDK đọc usage từ generation:** Langfuse Java SDK (khi kết nối qua auto-instrumentation hoặc khi gọi `generation(...)`) nhận cặp `input`/`output` và `usage` từ Spring AI, rồi đẩy lên server dưới dạng `Generation` với 3 trường:
   - `usage.input` — số token input (prompt)
   - `usage.output` — số token output (completion)
   - `usage.total` — tổng token
3. **Langfuse server lưu token count theo từng model:** mỗi `Generation` có trường `model` (VD: `gemini-2.5-flash`, `deepseek-v3`). Server dùng trường này để tra **Model Price List**.
4. **Tính chi phí:** chi phí = `inputTokens × pricePerMillionInput / 1.000.000 + outputTokens × pricePerMillionOutput / 1.000.000`. Kết quả hiển thị trên dashboard: cột **Total Cost**, **Usage**, và các biểu đồ **Cost over time** theo model.

### 1.2. Thiết lập bảng giá Model tùy chỉnh (Custom Model Prices)

Trên **Langfuse Dashboard → Settings → Model Prices** (hoặc mục *Model* trong project settings):

1. Bấm **"Add new model price"**.
2. Nhập **Model name** — phải khớp **chính xác** chuỗi `model` mà ứng dụng gửi lên (VD: `gemini-2.5-flash`, `deepseek-v3`, hoặc model tự host `llama-3.1-8b-selfhost`).
3. Nhập giá:
   - **Input price** — USD/1M token (đơn giá prompt).
   - **Output price** — USD/1M token (đơn giá completion).
   - (Tùy chọn) Cache read / Cache write / Reasoning price cho các model có token phân loại đặc biệt.
4. Lưu lại; Langfuse áp dụng ngay cho các generation mới ghi vào.
5. Để kiểm tra: vào **Traces → chọn 1 trace → tab Usage/Cost**, xem chi phí đã được tính đúng theo đơn giá vừa nhập chưa.

> **Lưu ý:** nếu model gửi lên chưa có trong Model Price List, Langfuse hiển thị **usage nhưng cost = 0** và đánh dấu model "untracked" — đây là dấu hiệu cần bổ sung giá.

---

## 2. Hướng dẫn phân tích biểu đồ Latency để xác định bottleneck RAG

### 2.1. Cấu trúc trace của một truy vấn RAG điển hình

```
Trace: rag-query (tổng latency)
├── Generation: embedding-query        (tạo embedding cho câu hỏi)        ~50-100ms
├── Span: vector-db-retrieval          (query pgvector / vector store)    ~30-200ms
└── Generation: llm-generation         (LLM sinh câu trả lời)             ~1-3s
```

### 2.2. Các bước phân tích trên Langfuse

1. **Mở trace của một truy vấn RAG** (Traces → chọn trace → tab **Timeline**). Mỗi span/generation hiển thị **Duration** riêng.
2. **Xác định thành phần chiếm thời gian lớn nhất:**
   - Nếu `llm-generation` chiếm ~80-90% tổng latency (thường 1-3 giây với model lớn) → **bottleneck ở bước Generation**. Hướng xử lý: giảm `max_tokens`, giảm context (top-k nhỏ hơn), dùng model nhanh hơn, tối ưu prompt ngắn gọn.
   - Nếu `vector-db-retrieval` hoặc `embedding-query` chiếm phần lớn → **bottleneck ở bước Retrieval** (Vector DB chậm). Hướng xử lý: tối ưu index HNSW, giảm top-k, dùng embedding model nhỏ hơn, kiểm tra network/connection pool, thêm cache embedding cho các câu hỏi lặp lại.
3. **So sánh nhiều trace qua bảng:** dùng bộ lọc theo `name=rag-query`, xem cột latency; hoặc xuất **Dataset** rồi vẽ phân bố latency theo từng span để thấy độ ổn định.
4. **Theo dõi theo thời gian:** biểu đồ **Latency over time** (filter theo model, theo version prompt) giúp phát hiện suy giảm hiệu năng sau khi deploy model/prompt mới.
5. **Kết hợp cost:** trace vừa chậm vừa đắt thường là do prompt quá dài (nhiều token) hoặc top-k quá lớn → giảm context vừa giảm latency vừa giảm cost.

### 2.3. Ví dụ minh họa kết luận

| Span | Duration | Nhận định |
|---|---|---|
| embedding-query | 80ms | Bình thường |
| vector-db-retrieval | 900ms | **Nghi ngờ bottleneck Retrieval** (query pgvector chậm do index/network) |
| llm-generation | 1.8s | Bình thường với model lớn |
| **Tổng** | **2.78s** | → tối ưu retrieval trước: kiểm tra HNSW index, connection pool, top-k |

---

## 3. Mã nguồn Java gửi token usage thủ công (custom model không tự đếm token)

### 3.1. `TokenUsageReporter.java` — gửi Usage + UsageDetails thủ công

```java
package com.rikkeipay.service;

import io.langfuse.client.LangfuseClient;
import io.langfuse.client.model.Generation;
import io.langfuse.client.model.Usage;
import io.langfuse.client.model.UsageDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * TokenUsageReporter - gửi token usage THỦ CÔNG lên Langfuse
 * cho các custom model KHÔNG hỗ trợ tự động đếm token.
 *
 * Khi Langfuse không biết bảng giá của model (custom/self-host model,
 * model mới, hoặc model gọi qua gateway riêng), ta phải:
 *   1. Tự đếm token input/output (vd: tiktoken, tokenizer riêng của model).
 *   2. Gửi Usage + UsageDetails kèm theo Generation.
 *   3. Langfuse sẽ ánh xạ modelName vào Model Price List đã cấu hình
 *      trong Settings -> Model Prices để tính ra chi phí.
 */
@Service
public class TokenUsageReporter {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageReporter.class);

    private final LangfuseClient langfuseClient;

    public TokenUsageReporter(LangfuseClient langfuseClient) {
        this.langfuseClient = langfuseClient;
    }

    /**
     * Ghi một generation có kèm token usage thủ công.
     *
     * @param traceId     id trace cha (đã tạo từ trước)
     * @param modelName   tên model, VD: "gemini-2.5-flash", "deepseek-v3"
     * @param input       prompt đầu vào
     * @param output      phản hồi model
     * @param inputTokens số token input do ta tự đếm
     * @param outputTokens số token output do ta tự đếm
     */
    public void reportManualUsage(String traceId,
                                  String modelName,
                                  String input,
                                  String output,
                                  int inputTokens,
                                  int outputTokens) {

        int totalTokens = inputTokens + outputTokens;

        Usage usage = new Usage()
                .input(inputTokens)
                .output(outputTokens)
                .total(totalTokens)
                // UsageDetails: chi tiết token theo từng loại (cache read/write, reasoning...)
                .inputDetails(new UsageDetails().input(inputTokens))
                .outputDetails(new UsageDetails().output(outputTokens));

        langfuseClient.generation(new Generation()
                .traceId(traceId)
                .name("custom-model-call")
                .model(modelName)
                .input(input)
                .output(output)
                .usage(usage));

        log.info("Đã gửi token usage thủ công cho model={}: input={}, output={}, total={}",
                modelName, inputTokens, outputTokens, totalTokens);

        // Ghi chú vận hành: chi phí được Langfuse tính = totalTokens * đơn giá
        // trong Model Price List. VD nếu cấu hình gemini-2.5-flash = 0.3$/1M input
        // và 1.5$/1M output, với 1000 input + 500 output:
        //   cost = 1000/1e6 * 0.3 + 500/1e6 * 1.5 = 0.00105$
        log.debug("Chi phí ước tính (tham khảo): {}", estimateCostUsd(modelName, inputTokens, outputTokens));
    }

    /**
     * Minh họa cách tự đếm token khi model không trả token usage
     * (thay thế bằng tokenizer thật của model nếu có).
     */
    public int estimateTokens(String text) {
        // Cách đơn giản: ~1 token / 4 ký tự tiếng Anh
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 4.0);
    }

    /** Bảng giá nội bộ (demo) - trên thực tế giá nằm trong Langfuse Model Price List. */
    private double estimateCostUsd(String modelName, int inputTokens, int outputTokens) {
        Map<String, double[]> pricePerMillion = Map.of(
                "gemini-2.5-flash", new double[]{0.30, 1.50},  // input, output (USD/1M token)
                "deepseek-v3", new double[]{0.27, 1.10}
        );
        double[] p = pricePerMillion.getOrDefault(modelName, new double[]{0.50, 1.50});
        return (inputTokens / 1_000_000.0) * p[0] + (outputTokens / 1_000_000.0) * p[1];
    }
}
```

### 3.2. `CustomModelCaller.java` — gọi custom model rồi gửi usage thủ công

```java
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
```

### 3.3. `application.yml`

```yaml
spring:
    application:
        name: hw04-nguyendangduong-cntt2-it213-ss10

langfuse:
    public-key: ${LANGFUSE_PUBLIC_KEY}
    secret-key: ${LANGFUSE_SECRET_KEY}
    base-url: ${LANGFUSE_BASE_URL:http://localhost:3000}
```

---

## 4. Tổng kết

- Langfuse tự đếm token khi provider trả `usage`; với custom model phải **gửi `Usage`/`UsageDetails` thủ công** kèm `Generation`, và cấu hình **Model Price List** (Settings → Model Prices) để server tính chi phí.
- Phân tích latency RAG: mở **Timeline** của trace, so sánh duration giữa `embedding-query` / `vector-db-retrieval` / `llm-generation` để xác định bottleneck nằm ở **Retrieval** hay **Generation**.

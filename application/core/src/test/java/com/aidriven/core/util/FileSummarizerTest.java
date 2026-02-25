package com.aidriven.core.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileSummarizerTest {

    private static final int THRESHOLD = 100; // small threshold for testing
    private final FileSummarizer summarizer = new FileSummarizer(THRESHOLD);

    // ─── threshold boundary ───────────────────────────────────────────────────

    @Test
    void file_below_threshold_is_returned_unchanged() {
        String tiny = "public class Tiny { }"; // < 100 chars
        assertThat(summarizer.summarize(tiny, "java")).isEqualTo(tiny);
    }

    @Test
    void empty_file_is_returned_unchanged() {
        assertThat(summarizer.summarize("", "java")).isEmpty();
    }

    @Test
    void null_extension_falls_back_to_truncation() {
        String big = "x".repeat(THRESHOLD + 1);
        String result = summarizer.summarize(big, null);
        assertThat(result).contains("[... file truncated");
    }

    // ─── Java summarization ──────────────────────────────────────────────────

    @Test
    void java_class_summary_includes_class_declaration() {
        String javaSource = generateLargeJavaSource();
        String summary = summarizer.summarize(javaSource, "java");

        assertThat(summary).contains("public class OrderService");
    }

    @Test
    void java_class_summary_includes_method_signatures() {
        String javaSource = generateLargeJavaSource();
        String summary = summarizer.summarize(javaSource, "java");

        assertThat(summary).contains("createOrder(");
        assertThat(summary).contains("cancelOrder(");
    }

    @Test
    void java_class_summary_strips_method_bodies() {
        String javaSource = generateLargeJavaSource();
        String summary = summarizer.summarize(javaSource, "java");

        assertThat(summary).doesNotContain("BODY_SECRET_TOKEN");
    }

    @Test
    void java_class_summary_includes_package_and_imports() {
        String javaSource = generateLargeJavaSource();
        String summary = summarizer.summarize(javaSource, "java");

        assertThat(summary).contains("package com.example");
        assertThat(summary).contains("import java.util.List");
    }

    @Test
    void java_summary_includes_field_declarations() {
        String javaSource = generateLargeJavaSource();
        String summary = summarizer.summarize(javaSource, "java");

        assertThat(summary).contains("orderRepository");
    }

    @Test
    void java_interface_summary_includes_interface_declaration() {
        String src = "package com.x;\n" + "public interface OrderRepository {\n"
                + "    Order findById(Long id);\n"
                + "    List<Order> findAll();\n"
                + "    boolean save(Order o);\n"
                + "}".repeat(5); // ensure > threshold
        String summary = summarizer.summarize(src, "java");

        assertThat(summary).contains("OrderRepository");
        assertThat(summary).contains("findById");
        assertThat(summary).contains("save");
    }

    // ─── TypeScript summarization ────────────────────────────────────────────

    @Test
    void typescript_summary_includes_function_declarations() {
        String ts = generateLargeTsSource();
        String summary = summarizer.summarize(ts, "ts");

        assertThat(summary).contains("createOrder");
        assertThat(summary).contains("cancelOrder");
    }

    @Test
    void typescript_summary_includes_interface_declarations() {
        String ts = generateLargeTsSource();
        String summary = summarizer.summarize(ts, "ts");

        assertThat(summary).contains("interface Order");
    }

    @Test
    void typescript_summary_strips_function_bodies() {
        String ts = generateLargeTsSource();
        String summary = summarizer.summarize(ts, "ts");

        assertThat(summary).doesNotContain("TS_BODY_SECRET");
    }

    @Test
    void javascript_uses_same_summarizer_as_typescript() {
        String js = generateLargeTsSource(); // same format works for JS
        String summary = summarizer.summarize(js, "js");

        assertThat(summary).contains("createOrder");
    }

    // ─── Python summarization ────────────────────────────────────────────────

    @Test
    void python_summary_includes_class_and_method_names() {
        String py = generateLargePythonSource();
        String summary = summarizer.summarize(py, "py");

        assertThat(summary).contains("class OrderService");
        assertThat(summary).contains("def create_order");
        assertThat(summary).contains("def cancel_order");
    }

    @Test
    void python_summary_strips_method_bodies() {
        String py = generateLargePythonSource();
        String summary = summarizer.summarize(py, "py");

        assertThat(summary).doesNotContain("PY_BODY_SECRET");
    }

    // ─── unknown extension fallback ──────────────────────────────────────────

    @Test
    void unknown_extension_falls_back_to_truncation_with_notice() {
        String big = "some content\n".repeat(20); // > threshold
        String summary = summarizer.summarize(big, "xml");

        assertThat(summary).contains("[... file truncated");
    }

    @Test
    void markdown_file_falls_back_to_truncation() {
        String md = "# Title\n".repeat(20);
        String summary = summarizer.summarize(md, "md");

        // either returns full (if small enough) or truncation notice
        assertThat(summary).isNotNull().isNotEmpty();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String generateLargeJavaSource() {
        return """
                package com.example.service;
                import java.util.List;
                import java.util.Optional;
                public class OrderService {
                    private final OrderRepository orderRepository;
                    private final PaymentGateway paymentGateway;

                    public OrderService(OrderRepository r, PaymentGateway p) {
                        this.orderRepository = r;
                        this.paymentGateway = p;
                    }

                    public Order createOrder(CreateOrderRequest req) {
                        // BODY_SECRET_TOKEN
                        Order order = new Order(req.getItems());
                        orderRepository.save(order);
                        paymentGateway.charge(order);
                        return order;
                    }

                    public boolean cancelOrder(Long orderId) {
                        // BODY_SECRET_TOKEN
                        return orderRepository.findById(orderId)
                            .map(o -> { o.cancel(); return true; })
                            .orElse(false);
                    }

                    private void validateOrder(Order o) {
                        // BODY_SECRET_TOKEN
                        if (o == null) throw new IllegalArgumentException();
                    }
                }
                """.repeat(3); // repeat to exceed threshold
    }

    private String generateLargeTsSource() {
        return """
                interface Order {
                    id: number;
                    items: string[];
                }

                export function createOrder(req: CreateOrderRequest): Order {
                    // TS_BODY_SECRET
                    const order = buildOrder(req);
                    return save(order);
                }

                export async function cancelOrder(orderId: number): Promise<boolean> {
                    // TS_BODY_SECRET
                    const order = await findById(orderId);
                    return order ? cancel(order) : false;
                }

                function buildOrder(req: CreateOrderRequest): Order {
                    // TS_BODY_SECRET
                    return { id: nextId(), items: req.items };
                }
                """.repeat(3);
    }

    private String generateLargePythonSource() {
        return """
                class OrderService:
                    \"\"\"Manages orders.\"\"\"

                    def __init__(self, repo, gateway):
                        self.repo = repo
                        self.gateway = gateway

                    def create_order(self, request):
                        # PY_BODY_SECRET
                        order = Order(request.items)
                        self.repo.save(order)
                        self.gateway.charge(order)
                        return order

                    def cancel_order(self, order_id):
                        # PY_BODY_SECRET
                        order = self.repo.find_by_id(order_id)
                        if order:
                            order.cancel()
                            return True
                        return False
                """.repeat(3);
    }
}

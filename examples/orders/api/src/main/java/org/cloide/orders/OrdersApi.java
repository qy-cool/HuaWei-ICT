package org.cloide.orders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrdersApi {
    record Create(@NotBlank @Size(max=120) String item) {}
    private final Orders orders;
    private final StringRedisTemplate cache;
    private final ObjectMapper json;
    public OrdersApi(Orders orders, StringRedisTemplate cache, ObjectMapper json) {
        this.orders = orders;
        this.cache = cache;
        this.json = json;
    }

    @GetMapping public List<Orders.Order> latest() { return orders.latest(); }

    @GetMapping("/{id}") public Orders.Order get(@PathVariable UUID id) {
        int version = orders.version(id);
        try {
            String found = cache.opsForValue().get(key(id, version));
            if (found != null) {
                var cached = json.readValue(found, Orders.Order.class);
                if (id.equals(cached.id()) && version == cached.version()) return cached;
            }
        } catch (DataAccessException | JsonProcessingException ignored) {
            // 缓存不可用时从订单库读取。
        }
        var order = orders.find(id);
        try {
            cache.opsForValue().set(key(id, order.version()), json.writeValueAsString(order), Duration.ofMinutes(10));
        } catch (DataAccessException | JsonProcessingException ignored) {
            // 保存缓存失败不改变订单状态。
        }
        return order;
    }

    private static String key(UUID id, int version) { return "orders:" + id + ":" + version; }
    @PostMapping public Orders.Order create(@Valid @RequestBody Create body) { return orders.create(body.item()); }
    @PostMapping("/{id}/cancel") public Orders.Order cancel(@PathVariable UUID id) { return orders.transition(id, "cancelled"); }
    @PostMapping("/{id}/ship") public Orders.Order ship(@PathVariable UUID id) { return orders.transition(id, "shipped"); }
}

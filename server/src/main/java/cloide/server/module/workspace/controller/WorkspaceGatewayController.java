package cloide.server.module.workspace.controller;

import cloide.server.module.workspace.service.WorkspaceService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspace-gateway")
public class WorkspaceGatewayController {
    public static final String COOKIE = "__Host-cloide-workspace";
    private final WorkspaceService workspaces;

    public WorkspaceGatewayController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @PostMapping("/session")
    public ResponseEntity<String> exchange(@RequestHeader(value = "X-Cloide-Gateway-Key", required = false) String key,
                                           @RequestHeader("X-Cloide-Workspace-Host") String host,
                                           @RequestHeader("X-Cloide-Browser-Origin") String origin,
                                           @RequestBody String ticket) {
        workspaces.verifyGateway(key);
        // 从 HTML 表单的正文读票据。禁用查询参数领取，访问日志不会出现入场凭据。
        String session = workspaces.exchange(host, origin, ticket.matches("ticket=[A-Za-z0-9_-]{43}") ? ticket.substring(7) : "");
        ResponseCookie cookie = ResponseCookie.from(COOKIE, session).path("/").httpOnly(true)
                .secure(true).sameSite("Strict").maxAge(WorkspaceService.SESSION_TTL_SECONDS).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer").header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'")
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body("<!doctype html><html lang=zh-CN><meta charset=utf-8><meta http-equiv=refresh content='0;url=/'><title>进入工作区</title><a href='/'>进入工作区</a></html>");
    }

    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(@RequestHeader(value = "X-Cloide-Gateway-Key", required = false) String key,
                                          @RequestHeader("X-Cloide-Workspace-Host") String host,
                                          @CookieValue(value = COOKIE, required = false) String session) {
        workspaces.verifyGateway(key);
        workspaces.authorize(host, session);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}

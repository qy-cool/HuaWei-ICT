package cloide.server.module.repository;

import cloide.server.common.ApiResponse;
import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.websocket.AgentAuthenticationService;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * A2 节点侧同步端点：先凭节点凭据领取绑定同步的短期下载授权，再以节点凭据加
 * 短期授权下载 bundle；每次下载请求由服务端重查撤权与期限，支持 Range 断点续传。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/source-agent/syncs")
public class SourceAgentController {
    private final AgentAuthenticationService authentication;
    private final SourceSyncService syncs;

    @PostMapping("/{syncId}/grant")
    public ApiResponse<SourceSyncService.GrantView> grant(@PathVariable String syncId, @RequestParam long serverId,
            @RequestHeader("X-Agent-Token") String token) {
        authentication.authenticate(serverId, token);
        return ApiResponse.success(syncs.grant(serverId, syncId));
    }

    @GetMapping("/{syncId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable String syncId, @RequestParam long serverId,
            @RequestHeader("X-Agent-Token") String token, @RequestHeader(value = "X-Sync-Grant", required = false) String grant,
            @RequestHeader(value = "Range", required = false) String range) throws IOException {
        authentication.authenticate(serverId, token);
        if (grant == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        int separator = grant.indexOf('.');
        if (separator <= 0 || separator == grant.length() - 1) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        Path bundle = syncs.content(serverId, syncId, grant.substring(0, separator), grant.substring(separator + 1));
        long size = Files.size(bundle);
        if (range != null && range.matches("bytes=[0-9]{1,16}-")) {
            // 同一摘要的分段复用：节点携带已下载字节数请求续传。
            long offset = Long.parseLong(range.substring(6, range.length() - 1));
            if (offset >= size) return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).build();
            FileChannel channel = FileChannel.open(bundle, StandardOpenOption.READ);
            channel.position(offset);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + offset + "-" + (size - 1) + "/" + size)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentLength(size - offset)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new InputStreamResource(Channels.newInputStream(channel)));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentLength(size)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(Files.newInputStream(bundle)));
    }
}

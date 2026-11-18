package cloide.server.module.workspace.service;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.project.service.ProjectAccess;
import cloide.server.module.project.service.ProjectService.MemberAccessRevoked;
import cloide.server.module.project.service.ProjectService.NodeUnassigned;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceService {
    public record Workspace(String id, long projectId, long serverId, long ownerId,
                            String name, String hostname, boolean active, boolean managed, String desired,
                            long generation, String observed, long observedGeneration, long observedAt) {}
    public record Runtime(String id, String hostname, long serverId, long projectId, String desired, long generation) {}
    public record Ticket(String ticket, String origin, long expiresAt) {}

    public static final long SESSION_TTL_SECONDS = 1800;
    private static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern DOMAIN = Pattern.compile(LABEL + "(?:\\." + LABEL + ")+");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final RowMapper<Workspace> ROW = (rs, n) -> new Workspace(rs.getString("id"),
            rs.getLong("project_id"), rs.getLong("server_id"), rs.getLong("owner_id"),
            rs.getString("name"), rs.getString("hostname"), rs.getBoolean("active"),rs.getBoolean("managed"),
            rs.getString("desired_state"),rs.getLong("runtime_generation"),rs.getString("observed_state"),
            rs.getLong("observed_generation"),rs.getLong("observed_at"));
    private static final String FIELDS = "w.id,w.project_id,w.server_id,w.owner_id,w.name,w.hostname,w.active,"
            + "w.managed,w.desired_state,w.runtime_generation,w.observed_state,w.observed_generation,w.observed_at";

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final Clock clock;
    private final String domain;
    private final String consoleOrigin;
    private final byte[] gatewayKey;
    private final boolean recovery;
    private final SecureRandom random = new SecureRandom();

    public WorkspaceService(JdbcTemplate jdbc, ProjectAccess access, Clock clock,
                            @Value("${WORKSPACE_DOMAIN:}") String domain,
                            @Value("${WORKSPACE_CONSOLE_ORIGIN:}") String consoleOrigin,
                            @Value("${WORKSPACE_GATEWAY_KEY:}") String gatewayKey,
                            @Value("${cloide.engineering.recovery:false}") boolean recovery) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
        this.domain = domain;
        this.consoleOrigin = consoleOrigin;
        this.gatewayKey = gatewayKey.getBytes(StandardCharsets.UTF_8);
        this.recovery = recovery;
        if (!domain.isEmpty() || !consoleOrigin.isEmpty() || !gatewayKey.isEmpty()) {
            URI console = URI.create(consoleOrigin);
            if (!DOMAIN.matcher(domain).matches() || !"https".equals(console.getScheme())
                    || console.getHost() == null || console.getRawUserInfo() != null
                    || !consoleOrigin.equals("https://" + console.getRawAuthority())
                    || console.getHost().equals(domain) || console.getHost().endsWith("." + domain)
                    || this.gatewayKey.length < 32) {
                throw new IllegalArgumentException("请成套配置独立工作区域名、管理台 HTTPS origin 和至少 32 字节的工作区网关密钥");
            }
        }
    }

    public List<Workspace> list(long projectId) {
        access.requireRead(projectId);
        return jdbc.query("SELECT " + FIELDS + " FROM workspaces w WHERE w.project_id=? AND w.owner_id=?"
                + " AND w.active=TRUE ORDER BY w.name,w.id", ROW, projectId, access.principal().id());
    }

    @Transactional
    public Workspace register(long projectId, long serverId, long ownerId, String name, String slug,boolean managed) {
        access.requireAdmin();
        access.requireRead(projectId);
        if (recovery) throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        if (gatewayKey.length == 0) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (slug == null || !slug.matches(LABEL) || slug.startsWith("xn--") || name == null || name.isBlank()
                || name.length() > 100 || slug.length() + domain.length() + 1 > 253) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        if (!access.canRun(ownerId, projectId, serverId)) throw new BusinessException(ErrorCode.FORBIDDEN);
        Workspace row = new Workspace(UUID.randomUUID().toString(), projectId, serverId, ownerId, name.strip(),
                slug + "." + domain, true,managed,managed ? "running" : "sleeping",1,"unknown",0,0);
        try {
            jdbc.update("INSERT INTO workspaces(id,project_id,server_id,owner_id,name,hostname,managed,desired_state) VALUES(?,?,?,?,?,?,?,?)",
                    row.id, projectId, serverId, ownerId, row.name, row.hostname,managed,row.desired);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }
        return row;
    }

    @Transactional
    public void disable(long projectId, String id) {
        access.requireAdmin();
        if (jdbc.update("UPDATE workspaces SET active=FALSE, desired_state='sleeping',runtime_generation=runtime_generation+1 "
                + "WHERE project_id=? AND id=? AND active=TRUE", projectId, id) == 0
                && jdbc.queryForList("SELECT id FROM workspaces WHERE id=? AND project_id=?", String.class, id, projectId).isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        jdbc.update("DELETE FROM workspace_credentials WHERE workspace_id=?", id);
    }

    @Transactional
    public Ticket issue(long projectId, String id) {
        access.requireRead(projectId);
        Workspace row = jdbc.query("SELECT " + FIELDS + " FROM workspaces w WHERE w.id=? AND w.project_id=?"
                        + " AND w.owner_id=? AND w.active=TRUE FOR UPDATE", ROW, id, projectId, access.principal().id())
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        verifyCurrentAccess(row);
        long expires = clock.instant().getEpochSecond() + 60;
        return new Ticket(mint(row.id, "ticket", expires), "https://" + row.hostname, expires);
    }

    public void verifyGateway(String key) {
        if (gatewayKey.length == 0 || key == null
                || !MessageDigest.isEqual(gatewayKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    @Transactional
    public String exchange(String host, String origin, String ticket) {
        if (!consoleOrigin.equals(origin)) throw new BusinessException(ErrorCode.FORBIDDEN);
        Workspace workspace = credential(host, ticket, "ticket");
        if (jdbc.queryForList("SELECT id FROM workspaces WHERE id=? AND active=TRUE FOR UPDATE",
                String.class, workspace.id).isEmpty()) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        // 原子消费，两个并发请求只有一个能写入会话。
        if (jdbc.update("DELETE FROM workspace_credentials WHERE token_hash=? AND kind='ticket' AND expires_at>?",
                digest(ticket), clock.instant().getEpochSecond()) != 1) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return mint(workspace.id, "session", clock.instant().getEpochSecond() + SESSION_TTL_SECONDS);
    }

    public void authorize(String host, String session) {
        credential(host, session, "session");
    }

    // 在成员/节点撤权事务内串行清除旧凭据，即使管理员立即重新授权，旧连接也无法续用。
    @EventListener
    public void revokeMember(MemberAccessRevoked event) {
        clearCredentials(jdbc.queryForList("SELECT id FROM workspaces WHERE project_id=? AND owner_id=? ORDER BY id FOR UPDATE",
                String.class, event.projectId(), event.userId()));
    }

    @EventListener
    public void revokeNode(NodeUnassigned event) {
        clearCredentials(jdbc.queryForList("SELECT id FROM workspaces WHERE project_id=? AND server_id=? ORDER BY id FOR UPDATE",
                String.class, event.projectId(), event.serverId()));
    }

    private void clearCredentials(List<String> ids) {
        for (String id : ids) {
            jdbc.update("DELETE FROM workspace_credentials WHERE workspace_id=?", id);
            jdbc.update("UPDATE workspaces SET desired_state='sleeping', runtime_generation=runtime_generation+1 "
                    + "WHERE id=? AND managed=TRUE AND desired_state<>'sleeping'",id);
        }
    }

    private Workspace credential(String host, String token, String kind) {
        if (token == null || !TOKEN.matcher(token).matches()) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        Workspace row = jdbc.query("SELECT " + FIELDS + " FROM workspace_credentials c JOIN workspaces w ON w.id=c.workspace_id"
                        + " WHERE c.token_hash=? AND c.kind=? AND c.expires_at>? AND w.hostname=? AND w.active=TRUE",
                ROW, digest(token), kind, clock.instant().getEpochSecond(), host)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        verifyCurrentAccess(row);
        return row;
    }

    private void verifyCurrentAccess(Workspace row) {
        if (gatewayKey.length == 0 || !row.hostname.endsWith("." + domain)
                || !access.canRun(row.ownerId, row.projectId, row.serverId)) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (row.managed && (!"running".equals(row.desired) || !"running".equals(row.observed)
                || row.generation!=row.observedGeneration || row.observedAt<clock.instant().getEpochSecond()-45)) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Transactional
    public Workspace desired(long project,String id,String desired) {
        access.requireRead(project);
        if (recovery && "running".equals(desired)) throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        var row=jdbc.query("SELECT "+FIELDS+" FROM workspaces w WHERE w.id=? AND w.project_id=? AND w.owner_id=?"
                +" AND w.active=TRUE AND w.managed=TRUE FOR UPDATE",ROW,id,project,access.principal().id())
                .stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!access.canRun(row.ownerId,project,row.serverId)) throw new BusinessException(ErrorCode.FORBIDDEN);
        boolean retry = "failed".equals(row.observed) && row.generation==row.observedGeneration;
        if (!desired.equals(row.desired) || retry) {
            jdbc.update("UPDATE workspaces SET desired_state=?,runtime_generation=runtime_generation+1 WHERE id=?",desired,id);
        }
        if ("sleeping".equals(desired)) jdbc.update("DELETE FROM workspace_credentials WHERE workspace_id=?",id);
        return jdbc.queryForObject("SELECT "+FIELDS+" FROM workspaces w WHERE w.id=?",ROW,id);
    }

    @Transactional
    public Runtime runtime(long server,String id) {
        var row=jdbc.query("SELECT "+FIELDS+" FROM workspaces w WHERE w.id=? AND w.server_id=? AND w.managed=TRUE FOR UPDATE",ROW,id,server)
                .stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if ("running".equals(row.desired) && (!row.active || !access.canRun(row.ownerId,row.projectId,server))) {
            clearCredentials(List.of(id));
            return new Runtime(id,row.hostname,server,row.projectId,"sleeping",row.generation+1);
        }
        return new Runtime(id,row.hostname,server,row.projectId,
                recovery && "running".equals(row.desired) ? "observe" : row.desired,row.generation);
    }

    @Transactional
    public void observe(long server,String id,long generation,String state) {
        var runtime=runtime(server,id);
        if (generation!=runtime.generation || !("observe".equals(runtime.desired) || "failed".equals(state) || runtime.desired.equals(state))) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }
        jdbc.update("UPDATE workspaces SET observed_state=?,observed_generation=?,observed_at=? WHERE id=? AND server_id=?",
                state,generation,clock.instant().getEpochSecond(),id,server);
    }

    private String mint(String workspace, String kind, long expires) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO workspace_credentials(token_hash,workspace_id,kind,expires_at) VALUES (?,?,?,?)",
                digest(token), workspace, kind, expires);
        return token;
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Scheduled(fixedDelay = 300000, initialDelay = 300000)
    public void purgeExpired() {
        if (gatewayKey.length != 0) {
            jdbc.update("DELETE FROM workspace_credentials WHERE expires_at <= ? LIMIT 10000", clock.instant().getEpochSecond());
        }
    }
}
